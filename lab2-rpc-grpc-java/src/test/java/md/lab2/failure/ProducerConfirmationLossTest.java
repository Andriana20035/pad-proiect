package md.lab2.failure;

import io.grpc.Status;
import md.lab2.TestSupport;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.server.BrokerServer;
import md.lab2.server.PublishFailureInjector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProducerConfirmationLossTest {
    @TempDir
    Path tempDir;

    @Test
    void retryWithSameMessageIdDoesNotDuplicatePersistedMessage() throws Exception {
        AppConfig config = TestSupport.config(tempDir.resolve("broker.db"), "broker-test");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        PublishFailureInjector injector = (request, outcome) -> {
            if (failOnce.compareAndSet(true, false)) {
                throw Status.UNAVAILABLE
                        .withDescription("simulated response lost after durable persist")
                        .asRuntimeException();
            }
        };

        try (BrokerServer server = new BrokerServer(config, injector)) {
            server.start();
            server.store().createTopic("orders.v1");
            server.store().activateSubscription("inventory-service", "orders.v1");
            List<Endpoint> endpoints = List.of(new Endpoint("localhost", server.port()));

            try (ProducerClient producer = new ProducerClient(endpoints, 1000, 3)) {
                var message = ProducerClient.orderCreated("ORD-LOST-ACK", "{\"q\":1}");
                var reply = producer.publish("orders.v1", message);

                assertTrue(reply.getAccepted());
                assertTrue(reply.getDuplicate(),
                        "second RPC should see the already persisted message");
                assertEquals(1, server.store().countMessagesWithId(message.getId()));
                assertEquals(1, server.store().countDeliveriesForMessage(message.getId()));
            }
        }
    }
}
