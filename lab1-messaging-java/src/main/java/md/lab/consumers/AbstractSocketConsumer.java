package md.lab.consumers;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.lab.api.MessageEnvelope;
import md.lab.api.WireMessage;
import md.lab.integrationservice.LogUtil;
import md.lab.integrationservice.AppConfig;
import md.lab.integrationservice.Network;
import md.lab.api.MessageValidator;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public abstract class AbstractSocketConsumer implements Runnable {
    private final String host;
    private final int port;
    private final String consumerId;
    private final String channel;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean running = true;
    private volatile Socket currentSocket;
    private volatile boolean haltProcessOnCrash;

    public void haltProcessOnCrash(boolean halt) { haltProcessOnCrash = halt; }

    protected AbstractSocketConsumer(String host, int port, String consumerId, String channel) {
        this.host = host;
        this.port = port;
        this.consumerId = consumerId;
        this.channel = channel;
    }

    protected abstract void process(MessageEnvelope message);

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try (Socket socket = Network.connect(host, port);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                currentSocket = socket;
                out.println(mapper.writeValueAsString(WireMessage.subscribe(consumerId, channel)));

                String line;
                while (running && (line = in.readLine()) != null) {
                    WireMessage wire = mapper.readValue(line, WireMessage.class);
                    if ("SUBSCRIBED".equals(wire.command())) {
                        LogUtil.log(consumerId, "SUBSCRIBED", null, "channel=" + channel);
                        // Waiting for work on an established subscription is intentionally unbounded.
                        socket.setSoTimeout(0);
                        continue;
                    }
                    if ("NACK".equals(wire.command())) {
                        if ("Consumer already connected".equals(wire.reason())) break;
                        System.err.println(consumerId + " subscription rejected: " + wire.reason());
                        return;
                    }
                    if (!"DELIVER".equals(wire.command())) continue;
                    MessageEnvelope message = wire.message();
                    LogUtil.log(consumerId, "RECEIVED", message, "");
                    try {
                        MessageValidator.validate(message);
                        process(message);
                        out.println(mapper.writeValueAsString(WireMessage.ack(consumerId, message.id())));
                        LogUtil.log(consumerId, "ACK_SENT", message, "");
                    } catch (SimulatedCrashBeforeAckException crash) {
                        LogUtil.log(consumerId, "CRASH_BEFORE_ACK", message, crash.getMessage());
                        if (haltProcessOnCrash) Runtime.getRuntime().halt(23);
                        running = false; // Demo/test creates a NEW consumer with its durable state.
                        // Inchidem socket-ul fara ACK. Brokerul va face retry.
                        break;
                    } catch (Exception e) {
                        boolean retryable = e instanceof java.io.UncheckedIOException;
                        out.println(mapper.writeValueAsString(WireMessage.nack(consumerId, message.id(), e.getMessage(), retryable)));
                        LogUtil.log(consumerId, "NACK_SENT", message, e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println(consumerId + " reconectare dupa eroare: " + e.getMessage());
                }
            }

            currentSocket = null;

            if (running) {
                try {
                    Thread.sleep(AppConfig.number("consumer.reconnectMs"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void stop() {
        running = false;
        Socket socket = currentSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
