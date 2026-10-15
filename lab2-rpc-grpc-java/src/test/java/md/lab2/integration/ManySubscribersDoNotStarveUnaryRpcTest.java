package md.lab2.integration;

import md.lab2.TestSupport;
import md.lab2.client.ConsumerClient;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ProcessResult;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManySubscribersDoNotStarveUnaryRpcTest {
    private static final int SUBSCRIBER_COUNT = 12;

    @TempDir
    Path tempDir;

    @Test
    void openSubscribeStreamsLeavePublishAndAckResponsive() throws Exception {
        AppConfig config = TestSupport.config(
                tempDir.resolve("broker.db"),
                "broker-many-subscribers");

        try (BrokerServer server = new BrokerServer(config)) {
            server.start();
            List<Endpoint> endpoints = List.of(
                    new Endpoint("localhost", server.port()));

            try (ProducerClient producer = new ProducerClient(endpoints, 1500)) {
                producer.createTopic("orders.v1");

                List<ConsumerClient> consumers = new ArrayList<>();
                List<Thread> threads = new ArrayList<>();
                try {
                    for (int i = 0; i < SUBSCRIBER_COUNT; i++) {
                        ConsumerClient consumer = new ConsumerClient(
                                "consumer-" + i,
                                "orders.v1",
                                endpoints,
                                1500,
                                30,
                                message -> new ProcessResult(true, "processed"),
                                ConsumerFailureInjector.NONE);
                        Thread thread = new Thread(
                                consumer,
                                "many-subscribers-client-" + i);
                        consumers.add(consumer);
                        threads.add(thread);
                        thread.start();
                    }

                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "")
                                    .getActiveSubscriptions() == SUBSCRIBER_COUNT,
                            Duration.ofSeconds(6));

                    assertTrue(producer.health().getServing());

                    PublishReply reply = producer.publishOrderCreated(
                            "orders.v1",
                            "ORD-MANY-1",
                            "{\"quantity\":1}");
                    assertTrue(reply.getAccepted());
                    assertEquals(SUBSCRIBER_COUNT, reply.getRoutedCount());

                    TestSupport.waitFor(
                            () -> producer.stats("orders.v1", "")
                                    .getCompleted() == SUBSCRIBER_COUNT,
                            Duration.ofSeconds(8));
                    assertEquals(0, producer.stats("orders.v1", "").getDlq(),
                            "Healthy subscribers must not be pushed into DLQ");
                } finally {
                    for (ConsumerClient consumer : consumers) {
                        consumer.close();
                    }
                    for (Thread thread : threads) {
                        thread.interrupt();
                    }
                    for (Thread thread : threads) {
                        thread.join(3000);
                    }
                    for (Thread thread : threads) {
                        assertFalse(thread.isAlive(),
                                "Consumer thread must terminate before temp directory cleanup: "
                                        + thread.getName());
                    }
                }
            }
        }
    }
}
