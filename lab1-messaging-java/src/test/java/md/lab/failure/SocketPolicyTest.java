package md.lab.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.lab.api.*;
import md.lab.integrationservice.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SocketPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PAYLOAD = "{\"product\":\"Laptop\",\"quantity\":1}";
    private BufferedReader reader(Socket socket) throws Exception {
        socket.setSoTimeout(AppConfig.number("network.deadlineMs"));
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }
    private void send(Socket socket, WireMessage wire) throws Exception {
        new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                .println(mapper.writeValueAsString(wire));
    }
    @Test void rejectsDuplicateSubscriptionAndInvalidPublication() throws Exception {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("c1", "orders");
        try (SocketBrokerServer server = new SocketBrokerServer(0, broker)) {
            server.start();
            try (Socket first = Network.connect("localhost", server.port());
                 Socket second = Network.connect("localhost", server.port());
                 Socket publisher = Network.connect("localhost", server.port())) {
                send(first, WireMessage.subscribe("c1", "orders"));
                assertEquals("SUBSCRIBED", mapper.readValue(reader(first).readLine(), WireMessage.class).command());
                send(second, WireMessage.subscribe("c1", "orders"));
                assertEquals("NACK", mapper.readValue(reader(second).readLine(), WireMessage.class).command());
                MessageEnvelope good = MessageEnvelope.orderCreated("A", PAYLOAD);
                MessageEnvelope invalid = new MessageEnvelope(good.id(), good.timestamp(), good.correlationId(),
                        "2.0", good.type(), good.key(), good.payload());
                send(publisher, WireMessage.publish("orders", invalid));
                assertEquals("NACK", mapper.readValue(reader(publisher).readLine(), WireMessage.class).command());
                assertTrue(broker.isIdle());
                try (Socket missing = Network.connect("localhost", server.port())) {
                    MessageEnvelope absentId = new MessageEnvelope(null, good.timestamp(), good.correlationId(),
                            "1.0", good.type(), good.key(), good.payload());
                    send(missing, WireMessage.publish("orders", absentId));
                    assertEquals("NACK", mapper.readValue(reader(missing).readLine(), WireMessage.class).command());
                    assertTrue(broker.isIdle());
                }
            }
        }
    }
    @Test void ackTimeoutRetriesSameIdAheadOfNextMessage() throws Exception {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("c1", "orders");
        MessageEnvelope a = MessageEnvelope.orderCreated("A", PAYLOAD);
        MessageEnvelope b = MessageEnvelope.orderCreated("B", PAYLOAD);
        broker.publish("orders", a);
        broker.publish("orders", b);
        try (SocketBrokerServer server = new SocketBrokerServer(0, broker)) {
            server.start();
            try (Socket socket = Network.connect("localhost", server.port())) {
                send(socket, WireMessage.subscribe("c1", "orders"));
                BufferedReader input = reader(socket);
                assertEquals("SUBSCRIBED", mapper.readValue(input.readLine(), WireMessage.class).command());
                assertEquals(a.id(), mapper.readValue(input.readLine(), WireMessage.class).messageId());
                assertNull(input.readLine(), "Broker closes connection after ACK timeout");
                Delivery retry = broker.poll("c1", 1000);
                assertNotNull(retry);
                assertEquals(a.id(), retry.message().id());
                assertEquals(2, retry.attempt());
                broker.ack("c1", retry);
                Delivery next = broker.poll("c1", 100);
                assertEquals(b, next.message());
                broker.retryOrDlq("c1", next, "permanent", false);
                assertEquals(1, broker.deadLetters().size());
            }
        }
    }
    @Test void nonRetryableNackGoesDirectlyToDlqOverTcp() throws Exception {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("c1", "orders");
        MessageEnvelope message = MessageEnvelope.orderCreated("A", PAYLOAD);
        broker.publish("orders", message);
        try (SocketBrokerServer server = new SocketBrokerServer(0, broker)) {
            server.start();
            try (Socket socket = Network.connect("localhost", server.port())) {
                send(socket, WireMessage.subscribe("c1", "orders"));
                BufferedReader input = reader(socket);
                input.readLine();
                assertEquals(message.id(), mapper.readValue(input.readLine(), WireMessage.class).messageId());
                send(socket, WireMessage.nack("c1", message.id(), "unsupported operation", false));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (broker.deadLetters().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(1, broker.deadLetters().size());
                assertEquals(1, broker.deadLetters().get(0).attempts());
                assertTrue(broker.isIdle());
            }
        }
    }
}
