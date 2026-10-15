package md.lab2.integration;

import md.lab2.TestSupport;
import md.lab2.client.ConsumerClient;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcPublishSubscribeTest {
    @TempDir
    Path tempDir;

    @Test
    void publishIsPersistedDeliveredAndConfirmed() throws Exception {
        AppConfig config = TestSupport.config(tempDir.resolve("broker.db"), "broker-test");
        try (BrokerServer server = new BrokerServer(config)) {
            server.start();
            List<Endpoint> endpoints = List.of(new Endpoint("localhost", server.port()));

            try (ProducerClient producer = new ProducerClient(endpoints, 1000)) {
                producer.createTopic("orders.v1");

                ConsumerLocalStore localStore = new ConsumerLocalStore(
                        tempDir.resolve("inventory.db"));
                ConsumerClient consumer = new ConsumerClient(
                        "inventory-service",
                        "orders.v1",
                        endpoints,
                        1000,
                        50,
                        new PersistentConsumerHandler(
                                "inventory-service",
                                localStore,
                                message -> message.getKey()),
                        ConsumerFailureInjector.NONE);
                Thread thread = new Thread(consumer, "test-consumer");
                thread.start();
                try {
                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "")
                                    .getActiveSubscriptions() == 1,
                            Duration.ofSeconds(3));

                    PublishReply reply = producer.publishOrderCreated(
                            "orders.v1",
                            "ORD-I-1",
                            "{\"quantity\":1}");
                    assertTrue(reply.getAccepted());
                    assertEquals(1, reply.getRoutedCount());

                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "inventory-service")
                                    .getCompleted() == 1,
                            Duration.ofSeconds(4));
                    assertEquals(1, localStore.effectCount("ORD-I-1"));
                    assertEquals(
                            1,
                            server.store().deliveryAttemptForMessageAndConsumer(
                                    reply.getMessageId(),
                                    "inventory-service"));
                } finally {
                    consumer.close();
                    thread.interrupt();
                    thread.join(3000);
                }
            }
        }
    }
}
