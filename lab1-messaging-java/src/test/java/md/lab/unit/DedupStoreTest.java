package md.lab.unit;

import md.lab.consumers.DedupStore;
import md.lab.api.MessageEnvelope;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DedupStoreTest {
    @TempDir Path directory;
    @Test
    void storesProcessedMessageIds() {
        Path file = directory.resolve("effects.json");
        DedupStore store = new DedupStore(file);
        MessageEnvelope message = MessageEnvelope.orderCreated("m1", "{\"product\":\"Laptop\",\"quantity\":1}");
        assertTrue(store.apply(message));
        assertFalse(new DedupStore(file).apply(message));
        assertEquals(1, new DedupStore(file).effectCount("m1"));
        assertEquals(1, store.size());
    }
}
