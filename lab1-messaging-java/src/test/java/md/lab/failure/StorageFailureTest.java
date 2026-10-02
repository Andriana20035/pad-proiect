package md.lab.failure;

import md.lab.api.MessageEnvelope;
import md.lab.consumers.DedupStore;
import md.lab.integrationservice.MinimalBroker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class StorageFailureTest {
    @TempDir Path directory;
    private static final String PAYLOAD = "{\"product\":\"Laptop\",\"quantity\":1}";
    @Test void failedBrokerCommitDoesNotBecomeAcceptedAndRequiresRecovery() throws Exception {
        Path file = directory.resolve("broker.json");
        MinimalBroker broker = new MinimalBroker(3, file);
        broker.registerConsumer("c1", "orders");
        // An existing directory blocks the temporary snapshot file, without altering the committed snapshot.
        Path blocked = directory.resolve("broker.json.tmp");
        Files.createDirectory(blocked);
        MessageEnvelope message = MessageEnvelope.orderCreated("A", PAYLOAD);
        assertThrows(UncheckedIOException.class, () -> broker.publish("orders", message));
        assertThrows(IllegalStateException.class, () -> broker.publish("orders", message));
        Files.delete(blocked);
        MinimalBroker recovered = new MinimalBroker(3, file);
        assertTrue(recovered.isIdle());
        recovered.publish("orders", message);
        assertEquals(1, recovered.pendingCount("c1"));
    }
    @Test void failedEffectCommitCanBeRetriedWithoutDuplicateAndCorruptionIsNotDiscarded() throws Exception {
        Path file = directory.resolve("inventory.json");
        DedupStore store = new DedupStore(file);
        Path blocked = directory.resolve("inventory.json.tmp");
        Files.createDirectory(blocked);
        MessageEnvelope message = MessageEnvelope.orderCreated("A", PAYLOAD);
        assertThrows(UncheckedIOException.class, () -> store.apply(message));
        assertEquals(0, store.effectCount("A"));
        Files.delete(blocked);
        assertTrue(store.apply(message));
        assertFalse(new DedupStore(file).apply(message));
        Files.writeString(file, "not-json");
        assertThrows(UncheckedIOException.class, () -> new DedupStore(file));
        assertEquals("not-json", Files.readString(file));
    }
}
