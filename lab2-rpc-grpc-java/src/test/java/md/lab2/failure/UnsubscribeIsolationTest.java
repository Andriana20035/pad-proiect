package md.lab2.failure;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.TestSupport;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.rpc.v1.AckRequest;
import md.lab2.rpc.v1.AckStage;
import md.lab2.rpc.v1.MessagingServiceGrpc;
import md.lab2.rpc.v1.UnsubscribeRequest;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnsubscribeIsolationTest {
    @TempDir
    Path tempDir;

    @Test
    void unsubscribedConsumerGetsNoNewDeliveriesAndCannotAck() throws Exception {
        AppConfig config = TestSupport.config(tempDir.resolve("broker.db"), "broker-test");
        try (BrokerServer server = new BrokerServer(config)) {
            server.start();
            Endpoint endpoint = new Endpoint("localhost", server.port());
            server.store().createTopic("orders.v1");
            server.store().activateSubscription("inventory-service", "orders.v1");

            var firstMessage = ProducerClient.orderCreated("ORD-U-1", "{}");
            server.store().publish("orders.v1", firstMessage);
            var firstDelivery = server.store().claimNextDelivery(
                    "inventory-service", "orders.v1").orElseThrow();

            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(endpoint.host(), endpoint.port())
                    .usePlaintext()
                    .build();
            try {
                var stub = MessagingServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(1, TimeUnit.SECONDS);
                stub.unsubscribe(UnsubscribeRequest.newBuilder()
                        .setConsumerId("inventory-service")
                        .setTopic("orders.v1")
                        .build());

                try (ProducerClient producer = new ProducerClient(List.of(endpoint), 1000)) {
                    var second = producer.publishOrderCreated(
                            "orders.v1", "ORD-U-2", "{}");
                    assertEquals(0, second.getRoutedCount());
                }

                StatusRuntimeException error = assertThrows(
                        StatusRuntimeException.class,
                        () -> stub.ack(AckRequest.newBuilder()
                                .setDeliveryId(firstDelivery.deliveryId())
                                .setConsumerId("inventory-service")
                                .setStage(AckStage.PERSISTED)
                                .build()));
                assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
            } finally {
                channel.shutdownNow();
                channel.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }
}
