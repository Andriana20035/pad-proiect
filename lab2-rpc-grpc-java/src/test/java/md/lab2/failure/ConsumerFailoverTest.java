package md.lab2.failure;

import md.lab2.TestSupport;
import md.lab2.client.ConsumerClient;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsumerFailoverTest {
    @TempDir
    Path tempDir;

    @Test
    void consumerReconnectsToSecondEndpointAfterFirstBrokerStops() throws Exception {
        Path sharedDb = tempDir.resolve("shared.db");
        AppConfig base = TestSupport.config(sharedDb, "base");
        BrokerServer brokerA = new BrokerServer(base.withPortAndNode(0, "broker-a"));
        BrokerServer brokerB = new BrokerServer(base.withPortAndNode(0, "broker-b"));
        brokerA.start();
        brokerB.start();
        try {
            Endpoint endpointA = new Endpoint("localhost", brokerA.port());
            Endpoint endpointB = new Endpoint("localhost", brokerB.port());
            List<Endpoint> endpoints = List.of(endpointA, endpointB);

            try (ProducerClient admin = new ProducerClient(List.of(endpointA), 1000)) {
                admin.createTopic("orders.v1");
            }

            ConsumerLocalStore localStore = new ConsumerLocalStore(
                    tempDir.resolve("inventory.db"));
            ConsumerClient consumer = new ConsumerClient(
                    "inventory-service",
                    "orders.v1",
                    endpoints,
                    1000,
                    40,
                    new PersistentConsumerHandler(
                            "inventory-service",
                            localStore,
                            message -> message.getKey()),
                    ConsumerFailureInjector.NONE);
            Thread thread = new Thread(consumer, "failover-consumer");
            thread.start();
            try {
                TestSupport.waitFor(
                        () -> brokerB.store().isActiveSubscription(
                                "inventory-service", "orders.v1"),
                        Duration.ofSeconds(3));

                brokerA.close();
                Thread.sleep(200);

                try (ProducerClient producer = new ProducerClient(List.of(endpointB), 1000)) {
                    producer.publishOrderCreated("orders.v1", "ORD-FAILOVER", "{}");
                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "inventory-service")
                                    .getCompleted() == 1,
                            Duration.ofSeconds(5));
                }
                assertEquals(1, localStore.effectCount("ORD-FAILOVER"));
            } finally {
                consumer.stop();
                thread.interrupt();
                thread.join(1500);
            }
        } finally {
            brokerA.close();
            brokerB.close();
        }
    }
}
