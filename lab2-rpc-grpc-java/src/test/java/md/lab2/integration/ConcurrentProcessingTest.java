package md.lab2.integration;

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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentProcessingTest {
    @TempDir
    Path tempDir;

    @Test
    void concurrentProducersAndDistinctConsumersCompleteAcrossIndependentBrokerNotifiers() throws Exception {
        var base = TestSupport.config(tempDir.resolve("shared.db"), "base");
        var config = new AppConfig(base.databasePath(), 0, "broker-a", 3, 1500,
                80, 250, 100, 2000, base.endpoints());
        try (BrokerServer a = new BrokerServer(config);
             BrokerServer b = new BrokerServer(config.withPortAndNode(0, "broker-b"))) {
            a.start();
            b.start();
            var endpointA = new Endpoint("localhost", a.port());
            var endpointB = new Endpoint("localhost", b.port());
            var inventoryStore = new ConsumerLocalStore(tempDir.resolve("inventory.db"));
            var auditStore = new ConsumerLocalStore(tempDir.resolve("audit.db"));
            try (ProducerClient left = new ProducerClient(List.of(endpointB), 2000);
                 ProducerClient right = new ProducerClient(List.of(endpointB), 2000);
                 ConsumerClient inventory = new ConsumerClient("inventory", "orders.v1", List.of(endpointA),
                         2000, 40, new PersistentConsumerHandler("inventory", inventoryStore, value -> value.getKey()),
                         ConsumerFailureInjector.NONE);
                 ConsumerClient audit = new ConsumerClient("audit", "orders.v1", List.of(endpointA),
                         2000, 40, new PersistentConsumerHandler("audit", auditStore, value -> value.getKey()),
                         ConsumerFailureInjector.NONE)) {
                left.createTopic("orders.v1");
                Thread inventoryThread = new Thread(inventory, "concurrent-inventory");
                Thread auditThread = new Thread(audit, "concurrent-audit");
                var workers = Executors.newFixedThreadPool(4);
                inventoryThread.start();
                auditThread.start();
                try {
                    TestSupport.waitFor(() -> left.stats("orders.v1", "").getActiveSubscriptions() == 2,
                            Duration.ofSeconds(4));
                    List<Callable<Boolean>> tasks = IntStream.range(0, 12).mapToObj(index ->
                            (Callable<Boolean>) () -> {
                                var reply = (index % 2 == 0 ? left : right).publishOrderCreated(
                                        "orders.v1", "ORD-CONCURRENT-" + index, "{}");
                                return reply.getAccepted() && reply.getRoutedCount() == 2
                                        && reply.getBrokerNode().equals("broker-b");
                            }).toList();
                    for (var future : workers.invokeAll(tasks, 10, TimeUnit.SECONDS)) {
                        assertTrue(future.get());
                    }
                    TestSupport.waitFor(() -> left.stats("orders.v1", "").getCompleted() == 24,
                            Duration.ofSeconds(8));
                    assertEquals(12, inventoryStore.processedCount());
                    assertEquals(12, auditStore.processedCount());
                    for (int i = 0; i < 12; i++) {
                        assertEquals(1, inventoryStore.effectCount("ORD-CONCURRENT-" + i));
                        assertEquals(1, auditStore.effectCount("ORD-CONCURRENT-" + i));
                    }
                    assertEquals(0, left.stats("orders.v1", "").getDlq());
                } finally {
                    workers.shutdownNow();
                    inventory.close();
                    audit.close();
                    inventoryThread.interrupt();
                    auditThread.interrupt();
                    inventoryThread.join(3000);
                    auditThread.join(3000);
                    assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
                    assertFalse(inventoryThread.isAlive());
                    assertFalse(auditThread.isAlive());
                }
            }
        }
    }
}
