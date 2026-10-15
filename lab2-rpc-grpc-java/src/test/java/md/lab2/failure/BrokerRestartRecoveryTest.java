package md.lab2.failure;

import md.lab2.client.ProducerClient;
import md.lab2.rpc.v1.AckStage;
import md.lab2.store.DeliveryRecord;
import md.lab2.store.SqliteMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerRestartRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void inFlightDeliverySurvivesBrokerRestartAndIsRetried() throws Exception {
        Path db = tempDir.resolve("broker.db");
        SqliteMessageStore beforeRestart = new SqliteMessageStore(db, 3, 180, 60, 200);
        beforeRestart.createTopic("orders.v1");
        beforeRestart.activateSubscription("inventory-service", "orders.v1");
        var message = ProducerClient.orderCreated("ORD-RESTART", "{}");
        beforeRestart.publish("orders.v1", message);
        DeliveryRecord first = beforeRestart.claimNextDelivery(
                "inventory-service", "orders.v1").orElseThrow();
        assertEquals(1, first.attempt());

        // New store instance represents a restarted broker reading the same SQLite database.
        SqliteMessageStore afterRestart = new SqliteMessageStore(db, 3, 180, 60, 200);
        Thread.sleep(220);
        assertTrue(afterRestart.claimNextDelivery(
                "inventory-service", "orders.v1").isEmpty(),
                "expired lease is first requeued with backoff");
        Thread.sleep(90);
        DeliveryRecord retried = afterRestart.claimNextDelivery(
                "inventory-service", "orders.v1").orElseThrow();
        assertEquals(2, retried.attempt());
        afterRestart.ack(retried.deliveryId(), "inventory-service", AckStage.PERSISTED);
        assertEquals(1, afterRestart.stats("orders.v1", "inventory-service").completed());
    }
}
