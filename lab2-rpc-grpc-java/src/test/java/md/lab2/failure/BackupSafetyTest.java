package md.lab2.failure;

import md.lab2.client.ProducerClient;
import md.lab2.store.SqliteMessageStore;
import md.lab2.store.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupSafetyTest {
    @TempDir
    Path tempDir;

    @Test
    void backupCannotOverwriteTheLiveDatabase() {
        Path primary = tempDir.resolve("primary.db");
        SqliteMessageStore store = new SqliteMessageStore(primary, 3, 500, 100, 500);
        store.createTopic("orders.v1");
        store.publish("orders.v1", ProducerClient.orderCreated("ORD-SAFE-BACKUP", "{}"));

        assertThrows(StoreException.class, () -> store.backupTo(primary));
        assertEquals(1, store.stats("orders.v1", "").messages(),
                "A rejected backup must preserve the original database");
    }
}
