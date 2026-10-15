package md.lab2.failure;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.TestSupport;
import md.lab2.client.ProducerClient;
import md.lab2.config.Endpoint;
import md.lab2.server.BrokerServer;
import md.lab2.store.SqliteMessageStore;
import md.lab2.store.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void invalidDatabasePathFailsClosedInsteadOfPretendingToPersist() throws Exception {
        Path parentThatIsAFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentThatIsAFile, "block directory creation");
        Path impossibleDb = parentThatIsAFile.resolve("broker.db");

        StoreException error = assertThrows(
                StoreException.class,
                () -> new SqliteMessageStore(impossibleDb, 3, 500, 100, 500));
        assertEquals(StoreException.Kind.STORAGE, error.kind());
    }

    @Test
    void failedSqliteWriteReturnsInternalAndNeverAcceptsThePublish() throws Exception {
        Path db = tempDir.resolve("write-failure.db");
        try (BrokerServer server = new BrokerServer(TestSupport.config(db, "write-failure"))) {
            server.start();
            try (ProducerClient producer = new ProducerClient(
                    List.of(new Endpoint("localhost", server.port())), 1000)) {
                producer.createTopic("orders.v1");
                server.store().activateSubscription("inventory-service", "orders.v1");
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                     var statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE TRIGGER reject_message BEFORE INSERT ON messages
                            BEGIN SELECT RAISE(ABORT, 'injected SQLite write failure'); END
                            """);
                }
                StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                        () -> producer.publishOrderCreated("orders.v1", "ORD-WRITE-FAIL", "{}"));
                assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
                assertEquals(0, server.store().stats("orders.v1", "").messages());
                assertEquals(0, server.store().stats("orders.v1", "").pending());
            }
        }
    }
}
