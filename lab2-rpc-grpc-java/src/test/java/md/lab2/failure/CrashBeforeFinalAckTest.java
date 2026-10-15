package md.lab2.failure;

import md.lab2.TestSupport;
import md.lab2.client.ConsumerClient;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.consumer.SoftCrashOnceInjector;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CrashBeforeFinalAckTest {
    @TempDir
    Path tempDir;

    @Test
    void localEffectIsNotRepeatedAfterCrashBeforePersistedAck() throws Exception {
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
                        40,
                        new PersistentConsumerHandler(
                                "inventory-service",
                                localStore,
                                message -> message.getKey()),
                        new SoftCrashOnceInjector());
                Thread thread = new Thread(consumer, "crash-test-consumer");
                thread.start();
                try {
                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "")
                                    .getActiveSubscriptions() == 1,
                            Duration.ofSeconds(3));
                    PublishReply reply = producer.publishOrderCreated(
                            "orders.v1",
                            "ORD-FAIL-1",
                            "{\"quantity\":1}");

                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "inventory-service")
                                    .getCompleted() == 1,
                            Duration.ofSeconds(5));

                    assertEquals(1, localStore.effectCount("ORD-FAIL-1"));
                    assertEquals(2, server.store().deliveryAttemptForMessageAndConsumer(
                            reply.getMessageId(), "inventory-service"));
                    assertEquals(0, producer.stats("orders.v1", "").getDlq());
                } finally {
                    consumer.close();
                    thread.interrupt();
                    thread.join(3000);
                    assertFalse(thread.isAlive(), "Crash-test consumer must terminate before cleanup");
                }
            }
        }
    }
}
