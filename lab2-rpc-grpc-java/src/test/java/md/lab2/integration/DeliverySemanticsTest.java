package md.lab2.integration;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.TestSupport;
import md.lab2.client.ProducerClient;
import md.lab2.config.Endpoint;
import md.lab2.rpc.v1.AckStage;
import md.lab2.server.BrokerServer;
import md.lab2.store.SqliteMessageStore;
import md.lab2.store.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliverySemanticsTest {
    @TempDir
    Path tempDir;

    @Test
    void sameIdCannotSilentlyReplacePayloadOrTopic() throws Exception {
        try (BrokerServer server = new BrokerServer(TestSupport.config(tempDir.resolve("broker.db"), "conflict"))) {
            server.start();
            try (ProducerClient producer = new ProducerClient(List.of(new Endpoint("localhost", server.port())), 1000)) {
                producer.createTopic("orders.v1");
                producer.createTopic("other.v1");
                var original = ProducerClient.orderCreated("ORD-CONFLICT", "{\"q\":1}");
                assertTrue(producer.publish("orders.v1", original).getAccepted());
                var conflicting = ProducerClient.orderCreated("ORD-CONFLICT", "{\"q\":2}");
                var payloadError = assertThrows(StatusRuntimeException.class,
                        () -> producer.publish("orders.v1", conflicting));
                assertEquals(Status.Code.ALREADY_EXISTS, payloadError.getStatus().getCode());
                var topicError = assertThrows(StatusRuntimeException.class,
                        () -> producer.publish("other.v1", original));
                assertEquals(Status.Code.ALREADY_EXISTS, topicError.getStatus().getCode());
                assertTrue(producer.publish("orders.v1", original).getDuplicate());
                assertEquals(1, server.store().countMessagesWithId(original.getId()));
            }
        }
    }

    @Test
    void permanentNackGoesDirectlyToDlqAndUnspecifiedAckIsRejected() {
        var store = new SqliteMessageStore(tempDir.resolve("permanent.db"), 3, 500, 100, 500);
        store.createTopic("orders.v1");
        store.activateSubscription("inventory", "orders.v1");
        store.publish("orders.v1", ProducerClient.orderCreated("ORD-PERMANENT", "{}"));
        var delivery = store.claimNextDelivery("inventory", "orders.v1").orElseThrow();
        assertThrows(StoreException.class,
                () -> store.ack(delivery.deliveryId(), "inventory", AckStage.ACK_STAGE_UNSPECIFIED));
        assertTrue(store.nack(delivery.deliveryId(), "inventory", "invalid order", false).movedToDlq());
        assertEquals(1, store.stats("orders.v1", "inventory").dlq());
        assertTrue(store.claimNextDelivery("inventory", "orders.v1").isEmpty());
    }

    @Test
    void laterDueMessageMayOvertakeAnEarlierMessageInBackoff() {
        var store = new SqliteMessageStore(tempDir.resolve("ordering.db"), 3, 500, 3000, 3000);
        store.createTopic("orders.v1");
        store.activateSubscription("inventory", "orders.v1");
        store.publish("orders.v1", ProducerClient.orderCreated("ORD-FIRST", "{}"));
        var first = store.claimNextDelivery("inventory", "orders.v1").orElseThrow();
        store.nack(first.deliveryId(), "inventory", "retry later", true);
        var secondMessage = ProducerClient.orderCreated("ORD-SECOND", "{}");
        store.publish("orders.v1", secondMessage);
        var second = store.claimNextDelivery("inventory", "orders.v1").orElseThrow();
        assertEquals(secondMessage.getId(), second.message().getId());
        assertTrue(store.claimNextDelivery("inventory", "orders.v1").isEmpty(),
                "At most one delivery may be in flight for a consumer/topic");
        store.ack(second.deliveryId(), "inventory", AckStage.PERSISTED);
        assertEquals(1, store.stats("orders.v1", "inventory").completed());
        assertEquals(1, store.stats("orders.v1", "inventory").pending());
    }
}
