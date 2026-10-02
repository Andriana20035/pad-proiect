package md.lab.integrationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.lab.api.WireMessage;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SocketBrokerServer implements AutoCloseable {
    private final int requestedPort;
    private final MinimalBroker broker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<String> activeConsumers = ConcurrentHashMap.newKeySet();

    public SocketBrokerServer(int port, MinimalBroker broker) {
        this.requestedPort = port;
        this.broker = broker;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        running = true;
        pool.submit(this::acceptLoop);
        System.out.println("Broker pornit pe portul " + port());
    }

    public int port() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                pool.submit(() -> handleConnection(socket));
            } catch (SocketException e) {
                if (running) e.printStackTrace();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void handleConnection(Socket socket) {
        String subscription = null;
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(AppConfig.number("network.deadlineMs"));
            String firstLine = in.readLine();
            if (firstLine == null) return;
            WireMessage first;
            try {
                first = mapper.readValue(firstLine, WireMessage.class);
                if (first == null) throw new IllegalArgumentException("Empty protocol message");
            } catch (IOException | IllegalArgumentException invalid) {
                out.println(mapper.writeValueAsString(WireMessage.nack(null, null, "Invalid JSON/protocol")));
                return;
            }

            if ("PUBLISH".equals(first.command())) {
                try {
                    if (first.message() == null || !java.util.Objects.equals(first.message().id(), first.messageId()))
                        throw new IllegalArgumentException("Envelope and protocol ID must match");
                    broker.publish(first.channel(), first.message(), first.consumerId());
                    out.println(mapper.writeValueAsString(WireMessage.published(first.message().id())));
                } catch (IllegalArgumentException e) {
                    out.println(mapper.writeValueAsString(WireMessage.nack(first.consumerId(), first.messageId(), e.getMessage())));
                }
                return;
            }

            if ("SUBSCRIBE".equals(first.command())) {
                try {
                    broker.registerConsumer(first.consumerId(), first.channel());
                    if (!activeConsumers.add(first.consumerId())) {
                        out.println(mapper.writeValueAsString(WireMessage.nack(first.consumerId(), null, "Consumer already connected")));
                        return;
                    }
                    subscription = first.consumerId();
                    out.println(mapper.writeValueAsString(WireMessage.subscribed(subscription, first.channel())));
                } catch (IllegalArgumentException invalid) {
                    out.println(mapper.writeValueAsString(WireMessage.nack(first.consumerId(), null, invalid.getMessage())));
                    return;
                }
                handleSubscriber(socket, in, out, first.consumerId(), first.channel());
                return;
            }

            out.println(mapper.writeValueAsString(WireMessage.nack(first.consumerId(), first.messageId(), "Comanda necunoscuta")));
        } catch (Exception e) {
            if (running && !(e instanceof InterruptedException)) System.err.println("Connection closed: " + e.getMessage());
        } finally {
            if (subscription != null) activeConsumers.remove(subscription);
            sockets.remove(socket);
        }
    }

    private void handleSubscriber(Socket socket, BufferedReader in, PrintWriter out,
                                  String consumerId, String channel) throws InterruptedException {
        while (running && !socket.isClosed()) {
            Delivery delivery = broker.poll(consumerId, 100);
            if (delivery == null) {
                try {
                    socket.setSoTimeout(100);
                    // Detect a disconnected idle subscriber before accepting its replacement.
                    if (in.readLine() == null) break;
                    break; // Unsolicited ACK/protocol input is invalid.
                } catch (SocketTimeoutException idle) {
                    continue;
                } catch (IOException disconnected) {
                    break;
                }
            }
            try (Network.Deadline guard = Network.deadline(socket, System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(AppConfig.number("network.ackTimeoutMs")))) {
                socket.setSoTimeout(AppConfig.number("network.ackTimeoutMs"));
                LogUtil.log("broker", "DELIVER", delivery.message(),
                        "consumer=" + consumerId + " attempt=" + delivery.attempt());
                out.println(mapper.writeValueAsString(WireMessage.deliver(consumerId, channel, delivery.message())));
                if (out.checkError()) throw new IOException("Delivery write failed");
                String responseLine = in.readLine();
                if (responseLine == null) {
                    broker.retryOrDlq(consumerId, delivery, "consumer disconnected before ACK");
                    break;
                }
                WireMessage response = mapper.readValue(responseLine, WireMessage.class);
                if (response == null || !consumerId.equals(response.consumerId())
                        || !delivery.message().id().equals(response.messageId())
                        || !("ACK".equals(response.command()) || "NACK".equals(response.command()))) {
                    broker.retryOrDlq(consumerId, delivery, "Invalid confirmation", false);
                    break;
                }
                if ("ACK".equals(response.command())) {
                    broker.ack(consumerId, delivery);
                } else {
                    String reason = response.reason() == null ? "NACK" : response.reason();
                    broker.retryOrDlq(consumerId, delivery, reason, Boolean.TRUE.equals(response.retryable()));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                broker.retryOrDlq(consumerId, delivery, "Invalid confirmation JSON", false);
                break;
            } catch (SocketTimeoutException e) {
                broker.retryOrDlq(consumerId, delivery, "ACK timeout");
                break;
            } catch (IOException e) {
                broker.retryOrDlq(consumerId, delivery, "connection lost before ACK");
                break;
            }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        for (Socket socket : sockets) socket.close();
        pool.shutdownNow();
        try {
            pool.awaitTermination(AppConfig.number("network.deadlineMs"), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
