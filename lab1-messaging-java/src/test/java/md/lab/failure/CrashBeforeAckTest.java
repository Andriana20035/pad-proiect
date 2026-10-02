package md.lab.failure;

import md.lab.api.MessageEnvelope;
import md.lab.consumers.InventoryHandler;
import md.lab.consumers.SimulatedCrashBeforeAckException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrashBeforeAckTest {
    @Test
    void duplicateDeliveryDoesNotRepeatLocalEffect() {
        InventoryHandler handler = new InventoryHandler(true);
        MessageEnvelope message = MessageEnvelope.orderCreated("ORD-FAIL", "{\"product\":\"Laptop\",\"quantity\":1}");

        assertThrows(SimulatedCrashBeforeAckException.class, () -> handler.handle(message));
        assertEquals(1, handler.effectCount("ORD-FAIL"));

        // Brokerul retrimite acelasi mesaj deoarece nu a primit ACK.
        handler.handle(message);

        assertEquals(1, handler.effectCount("ORD-FAIL"));
        assertEquals(1, handler.dedupCount());
    }
}
