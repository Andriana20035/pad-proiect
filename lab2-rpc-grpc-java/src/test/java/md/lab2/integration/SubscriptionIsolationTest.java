package md.lab2.integration;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.TestSupport;
import md.lab2.client.GrpcChannelPool;
import md.lab2.client.ProducerClient;
import md.lab2.config.Endpoint;
import md.lab2.rpc.v1.*;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionIsolationTest {
    @TempDir
    Path tempDir;

    @Test
    void unsubscribeOnlyAffectsTheRequestedConsumerAndTopicAndRejectsWrongOwner() throws Exception {
        try (BrokerServer server = new BrokerServer(TestSupport.config(tempDir.resolve("broker.db"), "isolation"))) {
            server.start();
            Endpoint endpoint = new Endpoint("localhost", server.port());
            try (ProducerClient producer = new ProducerClient(List.of(endpoint), 1000);
                 GrpcChannelPool channels = new GrpcChannelPool()) {
                producer.createTopic("orders.v1");
                producer.createTopic("other.v1");
                server.store().activateSubscription("inventory", "orders.v1");
                server.store().activateSubscription("inventory", "other.v1");
                server.store().activateSubscription("audit", "orders.v1");
                var stub = MessagingServiceGrpc.newBlockingStub(channels.channel(endpoint))
                        .withDeadlineAfter(1, TimeUnit.SECONDS);
                stub.unsubscribe(UnsubscribeRequest.newBuilder()
                        .setConsumerId("inventory").setTopic("orders.v1").build());
                var orders = producer.publishOrderCreated("orders.v1", "ORD-ISOLATION", "{}");
                var other = producer.publishOrderCreated("other.v1", "ORD-OTHER", "{}");
                assertEquals(1, orders.getRoutedCount());
                assertEquals(1, other.getRoutedCount());
                assertFalse(server.store().isActiveSubscription("inventory", "orders.v1"));
                assertTrue(server.store().isActiveSubscription("inventory", "other.v1"));
                assertTrue(server.store().isActiveSubscription("audit", "orders.v1"));
                var delivery = server.store().claimNextDelivery("audit", "orders.v1").orElseThrow();
                StatusRuntimeException wrongOwner = assertThrows(StatusRuntimeException.class,
                        () -> stub.ack(AckRequest.newBuilder().setDeliveryId(delivery.deliveryId())
                                .setConsumerId("inventory").setStage(AckStage.PERSISTED).build()));
                assertEquals(Status.Code.FAILED_PRECONDITION, wrongOwner.getStatus().getCode());
                StatusRuntimeException wrongNack = assertThrows(StatusRuntimeException.class,
                        () -> stub.nack(NackRequest.newBuilder().setDeliveryId(delivery.deliveryId())
                                .setConsumerId("inventory").setRetryable(false).build()));
                assertEquals(Status.Code.FAILED_PRECONDITION, wrongNack.getStatus().getCode());
                StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                        () -> stub.ack(AckRequest.newBuilder().setDeliveryId(Long.MAX_VALUE)
                                .setConsumerId("audit").setStage(AckStage.PERSISTED).build()));
                assertEquals(Status.Code.NOT_FOUND, missing.getStatus().getCode());
                stub.ack(AckRequest.newBuilder().setDeliveryId(delivery.deliveryId())
                        .setConsumerId("audit").setStage(AckStage.RECEIVED).build());
                assertEquals(0, producer.stats("orders.v1", "audit").getCompleted());
                stub.ack(AckRequest.newBuilder().setDeliveryId(delivery.deliveryId())
                        .setConsumerId("audit").setStage(AckStage.PROCESSED).build());
                var repeated = stub.ack(AckRequest.newBuilder().setDeliveryId(delivery.deliveryId())
                        .setConsumerId("audit").setStage(AckStage.RECEIVED).build());
                assertEquals(AckStage.PROCESSED, repeated.getStoredStage());
                assertFalse(repeated.getCompleted());
                assertTrue(stub.ack(AckRequest.newBuilder().setDeliveryId(delivery.deliveryId())
                        .setConsumerId("audit").setStage(AckStage.PERSISTED).build()).getCompleted());
            }
        }
    }
}
