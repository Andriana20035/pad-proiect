package md.lab.contract;

import md.lab.api.*;
import md.lab.integrationservice.MinimalBroker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    private static final String PAYLOAD = "{\"product\":\"Laptop\",\"quantity\":1}";
    @Test void rejectsInvalidFieldsAndUnknownVersion() {
        MessageEnvelope good = MessageEnvelope.orderCreated("A", PAYLOAD);
        MessageValidator.validate(good);
        assertThrows(IllegalArgumentException.class, () -> MessageValidator.validate(null));
        assertThrows(IllegalArgumentException.class, () -> MessageValidator.validate(MessageEnvelope.orderCreated("", PAYLOAD)));
        assertThrows(IllegalArgumentException.class, () -> MessageValidator.validate(MessageEnvelope.orderCreated("A", "{}")));
        assertThrows(IllegalArgumentException.class, () -> MessageValidator.validate(MessageEnvelope.orderCreated("A", "{\"product\":\"X\",\"quantity\":0}")));
        assertThrows(IllegalArgumentException.class, () -> MessageValidator.validate(
                new MessageEnvelope(good.id(), good.timestamp(), good.correlationId(), "2.0", good.type(), good.key(), good.payload())));
        assertThrows(IllegalArgumentException.class, () -> MessageValidator.validate(
                new MessageEnvelope(null, good.timestamp(), good.correlationId(), "1.0", good.type(), good.key(), good.payload())));
    }
    @Test void stableIdentityAndConflictingRequestRejected() {
        MessageEnvelope a = MessageEnvelope.orderCreated("A", PAYLOAD);
        MessageEnvelope repeat = MessageEnvelope.orderCreated("A", PAYLOAD);
        assertEquals(a.id(), repeat.id());
        assertEquals(a.correlationId(), repeat.correlationId());
        MinimalBroker broker = new MinimalBroker(3);
        broker.registerConsumer("c1", "orders");
        broker.publish("orders", a);
        broker.publish("orders", repeat);
        assertEquals(1, broker.pendingCount("c1"));
        assertThrows(IllegalArgumentException.class, () -> broker.publish("orders",
                MessageEnvelope.orderCreated("A", "{\"product\":\"Other\",\"quantity\":1}")));
        assertThrows(IllegalArgumentException.class, () -> broker.publish("orders", a, "c1"));
    }
}
