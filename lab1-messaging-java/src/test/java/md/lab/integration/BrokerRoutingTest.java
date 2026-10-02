package md.lab.integration;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.MinimalBroker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrokerRoutingTest {
    @Test
    void routesMessageToTwoConsumers() {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("c1", "orders");
        broker.registerConsumer("c2", "orders");

        broker.publish("orders", MessageEnvelope.orderCreated("ORD-1", "{\"product\":\"Laptop\",\"quantity\":1}"));

        assertEquals(1, broker.pendingCount("c1"));
        assertEquals(1, broker.pendingCount("c2"));
    }
}
