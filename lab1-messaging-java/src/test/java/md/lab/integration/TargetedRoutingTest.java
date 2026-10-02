package md.lab.integration;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.MinimalBroker;
import md.lab.integrationservice.Delivery;
import md.lab.integrationservice.SocketBrokerServer;
import md.lab.orderservice.OrderProducer;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TargetedRoutingTest {
    private static final String PAYLOAD = "{\"product\":\"Laptop\",\"quantity\":1}";
    @Test
    void routesTargetedAndBroadcastMessagesThroughTcpInOrder() throws Exception {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("inventory-consumer", "orders");
        broker.registerConsumer("audit-consumer", "orders");
        try (SocketBrokerServer server = new SocketBrokerServer(0, broker)) {
            server.start();
            OrderProducer producer = new OrderProducer("localhost", server.port());
            MessageEnvelope inventory = producer.sendOrderCreated("I", PAYLOAD, "inventory-consumer");
            MessageEnvelope audit = producer.sendOrderCreated("A", PAYLOAD, "audit-consumer");
            MessageEnvelope both = producer.sendOrderCreated("B", PAYLOAD);
            assertEquals(2, broker.pendingCount("inventory-consumer"));
            assertEquals(2, broker.pendingCount("audit-consumer"));
            takeAndAck(broker, "inventory-consumer", inventory);
            takeAndAck(broker, "audit-consumer", audit);
            takeAndAck(broker, "inventory-consumer", both);
            takeAndAck(broker, "audit-consumer", both);
            assertThrows(IOException.class, () -> producer.sendOrderCreated("X", PAYLOAD, "unknown"));
            producer.sendOrderCreated("B", PAYLOAD);
            assertTrue(broker.isIdle());
            assertThrows(IOException.class, () -> producer.sendOrderCreated("B", "{\"product\":\"Other\",\"quantity\":1}"));
            assertEquals(0, broker.pendingCount("inventory-consumer"));
            assertEquals(0, broker.pendingCount("audit-consumer"));
        }
    }

    private void takeAndAck(MinimalBroker broker, String id, MessageEnvelope expected) throws Exception {
        Delivery delivery = broker.poll(id, 100);
        assertNotNull(delivery);
        assertEquals(expected, delivery.message());
        broker.ack(id, delivery);
    }

    @Test
    void rejectsWrongChannelWithoutEnqueuing() {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("c1", "orders");
        assertThrows(IllegalArgumentException.class,
                () -> broker.publish("other", MessageEnvelope.orderCreated("X", PAYLOAD), "c1"));
        assertEquals(0, broker.pendingCount("c1"));
    }
}
