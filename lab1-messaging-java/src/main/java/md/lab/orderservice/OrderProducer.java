package md.lab.orderservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.lab.api.MessageEnvelope;
import md.lab.api.WireMessage;
import md.lab.integrationservice.LogUtil;
import md.lab.integrationservice.MessageConsole;
import md.lab.integrationservice.AppConfig;
import md.lab.integrationservice.Network;
import md.lab.api.MessageValidator;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class OrderProducer {
    private final String host;
    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrderProducer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public MessageEnvelope sendOrderCreated(String orderId, String payload) throws IOException {
        return sendOrderCreated(orderId, payload, null);
    }

    public MessageEnvelope sendOrderCreated(String orderId, String payload, String consumerId) throws IOException {
        MessageEnvelope message = MessageEnvelope.orderCreated(orderId, payload);
        MessageValidator.validate(message);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(AppConfig.number("network.deadlineMs"));
        LogUtil.log("producer", "SENDING", message,
                "destination=" + (consumerId == null ? "toti abonatii orders" : consumerId));
        try (Socket socket = Network.connect(host, port, deadline);
             Network.Deadline guard = Network.deadline(socket, deadline);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(Network.remaining(deadline));
            out.println(mapper.writeValueAsString(WireMessage.publish("orders", message, consumerId)));
            String responseLine = in.readLine();
            if (responseLine == null) throw new IOException("Brokerul a inchis conexiunea fara confirmare");
            WireMessage response = mapper.readValue(responseLine, WireMessage.class);
            if (!"PUBLISHED".equals(response.command()) || !message.id().equals(response.messageId())) {
                throw new IOException("Publicare respinsa: " + response.reason());
            }
            LogUtil.log("producer", "BROKER_ACCEPTED", message,
                    "response=" + response.command() + " (nu inseamna inca procesare confirmata de consumer)");
        }
        return message;
    }

    public static void main(String[] args) throws Exception {
        OrderProducer producer = new OrderProducer(AppConfig.value("broker.host"), AppConfig.number("broker.port"));
        MessageConsole.run(producer, null);
    }
}
