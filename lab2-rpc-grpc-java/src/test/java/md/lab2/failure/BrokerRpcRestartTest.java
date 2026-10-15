package md.lab2.failure;

import io.grpc.stub.StreamObserver;
import md.lab2.TestSupport;
import md.lab2.client.ConsumerClient;
import md.lab2.client.GrpcChannelPool;
import md.lab2.client.ProducerClient;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.rpc.v1.Delivery;
import md.lab2.rpc.v1.MessagingServiceGrpc;
import md.lab2.rpc.v1.SubscribeRequest;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BrokerRpcRestartTest {
    @TempDir
    Path tempDir;

    @Test
    void restartedGrpcBrokerRedeliversTheUnacknowledgedMessageFromDisk() throws Exception {
        var config = TestSupport.config(tempDir.resolve("broker.db"), "before-restart");
        var message = ProducerClient.orderCreated("ORD-RPC-RESTART", "{}");
        AtomicReference<Delivery> first = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (BrokerServer before = new BrokerServer(config);
             GrpcChannelPool channels = new GrpcChannelPool()) {
            before.start();
            Endpoint endpoint = new Endpoint("localhost", before.port());
            before.store().createTopic("orders.v1");
            MessagingServiceGrpc.newStub(channels.channel(endpoint)).subscribe(
                    SubscribeRequest.newBuilder().setConsumerId("inventory").setTopic("orders.v1").build(),
                    new StreamObserver<>() {
                        public void onNext(Delivery value) {
                            first.compareAndSet(null, value);
                            delivered.countDown();
                        }

                        public void onError(Throwable error) {
                        }

                        public void onCompleted() {
                        }
                    });
            TestSupport.waitFor(() -> before.store().isActiveSubscription("inventory", "orders.v1"),
                    Duration.ofSeconds(3));
            try (ProducerClient producer = new ProducerClient(List.of(endpoint), 1000)) {
                producer.publish("orders.v1", message);
            }
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(message, first.get().getMessage());
            assertEquals(1, first.get().getAttempt());
            assertEquals(0, before.store().stats("orders.v1", "inventory").completed());
        }
        try (BrokerServer after = new BrokerServer(config.withPortAndNode(0, "after-restart"))) {
            after.start();
            var local = new ConsumerLocalStore(tempDir.resolve("inventory.db"));
            try (ConsumerClient consumer = new ConsumerClient("inventory", "orders.v1",
                    List.of(new Endpoint("localhost", after.port())), 1000, 40,
                    new PersistentConsumerHandler("inventory", local, value -> value.getKey()),
                    ConsumerFailureInjector.NONE)) {
                Thread thread = new Thread(consumer, "rpc-restart-consumer");
                thread.start();
                try {
                    TestSupport.waitFor(() -> after.store().stats("orders.v1", "inventory").completed() == 1,
                            Duration.ofSeconds(6));
                    assertEquals(1, local.effectCount("ORD-RPC-RESTART"));
                    assertEquals(2, after.store().deliveryAttemptForMessageAndConsumer(message.getId(), "inventory"));
                    assertEquals(0, after.store().stats("orders.v1", "inventory").dlq());
                } finally {
                    consumer.close();
                    thread.interrupt();
                    thread.join(3000);
                    assertFalse(thread.isAlive());
                }
            }
        }
    }
}
