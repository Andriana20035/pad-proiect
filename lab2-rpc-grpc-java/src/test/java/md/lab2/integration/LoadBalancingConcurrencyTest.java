package md.lab2.integration;

import md.lab2.TestSupport;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadBalancingConcurrencyTest {
    @TempDir
    Path tempDir;

    @Test
    void concurrentPublishesAreDistributedAcrossTwoBrokerNodes() throws Exception {
        Path sharedDb = tempDir.resolve("shared-broker.db");
        AppConfig base = TestSupport.config(sharedDb, "base");
        try (BrokerServer brokerA = new BrokerServer(base.withPortAndNode(0, "broker-a"));
             BrokerServer brokerB = new BrokerServer(base.withPortAndNode(0, "broker-b"))) {
            brokerA.start();
            brokerB.start();
            List<Endpoint> endpoints = List.of(
                    new Endpoint("localhost", brokerA.port()),
                    new Endpoint("localhost", brokerB.port()));

            try (ProducerClient producer = new ProducerClient(endpoints, 1500)) {
                producer.createTopic("orders.v1");
                assertEquals("broker-b", producer.health().getBrokerNode());
                assertEquals("broker-a", producer.health().getBrokerNode());
                brokerA.store().activateSubscription("sink-service", "orders.v1");

                ExecutorService workers = Executors.newFixedThreadPool(8);
                try {
                    List<Callable<PublishReply>> tasks = new ArrayList<>();
                    for (int i = 0; i < 40; i++) {
                        int index = i;
                        tasks.add(() -> producer.publishOrderCreated(
                                "orders.v1",
                                "ORD-C-" + index,
                                "{\"index\":" + index + "}"));
                    }
                    List<Future<PublishReply>> futures = workers.invokeAll(tasks);
                    int nodeA = 0;
                    int nodeB = 0;
                    for (Future<PublishReply> future : futures) {
                        PublishReply reply = future.get();
                        if ("broker-a".equals(reply.getBrokerNode())) {
                            nodeA++;
                        }
                        if ("broker-b".equals(reply.getBrokerNode())) {
                            nodeB++;
                        }
                    }

                    assertEquals(40, brokerA.store().stats("orders.v1", "").messages());
                    assertEquals(40, brokerA.store().stats("orders.v1", "sink-service").pending());
                    assertTrue(nodeA > 0, "broker-a should receive traffic");
                    assertTrue(nodeB > 0, "broker-b should receive traffic");
                } finally {
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
                }
            }
        }
    }
}
