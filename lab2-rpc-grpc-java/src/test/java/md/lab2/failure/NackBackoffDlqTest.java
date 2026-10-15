package md.lab2.failure;

import md.lab2.client.ProducerClient;
import md.lab2.rpc.v1.AckStage;
import md.lab2.store.DeliveryRecord;
import md.lab2.store.SqliteMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NackBackoffDlqTest {
    @TempDir
    Path tempDir;

    @Test
    void nackUsesBackoffAndMovesToDlqAfterThirdAttempt() throws Exception {
        SqliteMessageStore store = new SqliteMessageStore(
                tempDir.resolve("broker.db"), 3, 500, 80, 300);
        store.createTopic("orders.v1");
        store.activateSubscription("inventory-service", "orders.v1");
        var message = ProducerClient.orderCreated("ORD-DLQ", "{}");
        store.publish("orders.v1", message);

        DeliveryRecord first = store.claimNextDelivery(
                "inventory-service", "orders.v1").orElseThrow();
        assertEquals(1, first.attempt());
        var nack1 = store.nack(first.deliveryId(), "inventory-service", "temporary", true);
        assertFalse(nack1.movedToDlq());
        assertTrue(nack1.retryAfterMs() >= 80);
        assertTrue(store.claimNextDelivery("inventory-service", "orders.v1").isEmpty(),
                "retry must not happen immediately");

        Thread.sleep(nack1.retryAfterMs() + 30);
        DeliveryRecord second = store.claimNextDelivery(
                "inventory-service", "orders.v1").orElseThrow();
        assertEquals(2, second.attempt());
        var nack2 = store.nack(second.deliveryId(), "inventory-service", "temporary", true);
        assertFalse(nack2.movedToDlq());

        Thread.sleep(nack2.retryAfterMs() + 30);
        DeliveryRecord third = store.claimNextDelivery(
                "inventory-service", "orders.v1").orElseThrow();
        assertEquals(3, third.attempt());
        var nack3 = store.nack(third.deliveryId(), "inventory-service", "still failing", true);
        assertTrue(nack3.movedToDlq());
        assertEquals(1, store.listDlq("orders.v1", "inventory-service").size());
        assertEquals(0, store.stats("orders.v1", "inventory-service").pending());
        assertEquals(0, store.stats("orders.v1", "inventory-service").inFlight());
        assertEquals(1, store.stats("orders.v1", "inventory-service").dlq());
    }
}
