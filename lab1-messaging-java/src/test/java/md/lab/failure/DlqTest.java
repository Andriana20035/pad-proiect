package md.lab.failure;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.Delivery;
import md.lab.integrationservice.MinimalBroker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DlqTest {
    @Test
    void messageGoesToDlqAfterRetryLimit() throws Exception {
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("bad-consumer", "orders");
        MessageEnvelope message = MessageEnvelope.orderCreated("ORD-DLQ", "{\"product\":\"Laptop\",\"quantity\":1}");
        broker.publish("orders", message);

        Delivery d1 = broker.poll("bad-consumer", 100);
        assertNotNull(d1);
        broker.retryOrDlq("bad-consumer", d1, "failure 1");

        Delivery d2 = broker.poll("bad-consumer", 100);
        broker.retryOrDlq("bad-consumer", d2, "failure 2");

        Delivery d3 = broker.poll("bad-consumer", 100);
        broker.retryOrDlq("bad-consumer", d3, "failure 3");

        assertEquals(1, broker.deadLetters().size());
        assertEquals(message.id(), broker.deadLetters().get(0).message().id());
    }
}
