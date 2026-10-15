package md.lab2.failure;

import md.lab2.client.ProducerClient;
import md.lab2.store.SqliteMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupRestoreTest {
    @TempDir
    Path tempDir;

    @Test
    void sqliteBackupCanBeOpenedAsIndependentRestoredStore() {
        Path primary = tempDir.resolve("primary.db");
        Path backup = tempDir.resolve("backup.db");
        SqliteMessageStore store = new SqliteMessageStore(primary, 3, 500, 100, 500);
        store.createTopic("orders.v1");
        store.activateSubscription("inventory-service", "orders.v1");
        store.publish("orders.v1", ProducerClient.orderCreated("ORD-BACKUP", "{}"));

        store.backupTo(backup);
        assertTrue(Files.exists(backup));

        SqliteMessageStore restored = new SqliteMessageStore(backup, 3, 500, 100, 500);
        assertEquals(1, restored.stats("orders.v1", "").messages());
        assertEquals(1, restored.stats("orders.v1", "inventory-service").pending());
    }
}
