package md.lab2.failure;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import md.lab2.client.ConsumerClient;
import md.lab2.client.ProducerClient;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.rpc.v1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerAckTransportFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void lostProcessedAckReconnectsWithoutNackingTheSuccessfulLocalEffect() throws Exception {
        AtomicBoolean loseAck = new AtomicBoolean(true);
        AtomicInteger nacks = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch persisted = new CountDownLatch(1);
        var message = ProducerClient.orderCreated("ORD-ACK-LOSS", "{}");
        var service = new MessagingServiceGrpc.MessagingServiceImplBase() {
            @Override
            public void subscribe(SubscribeRequest request, StreamObserver<Delivery> observer) {
                observer.onNext(Delivery.newBuilder().setDeliveryId(1).setTopic("orders.v1")
                        .setConsumerId("inventory").setMessage(message)
                        .setAttempt(attempts.incrementAndGet()).setBrokerNode("ack-loss").build());
            }

            @Override
            public void ack(AckRequest request, StreamObserver<AckReply> observer) {
                if (request.getStage() == AckStage.PROCESSED && loseAck.compareAndSet(true, false)) {
                    observer.onError(Status.UNAVAILABLE.withDescription("lost ACK response").asRuntimeException());
                    return;
                }
                observer.onNext(AckReply.newBuilder().setAccepted(true).setStoredStage(request.getStage())
                        .setCompleted(request.getStage() == AckStage.PERSISTED).build());
                observer.onCompleted();
                if (request.getStage() == AckStage.PERSISTED) persisted.countDown();
            }

            @Override
            public void nack(NackRequest request, StreamObserver<NackReply> observer) {
                nacks.incrementAndGet();
                observer.onNext(NackReply.newBuilder().setAccepted(true).build());
                observer.onCompleted();
            }
        };
        Server server = ServerBuilder.forPort(0).addService(service).build().start();
        var local = new ConsumerLocalStore(tempDir.resolve("inventory.db"));
        try (ConsumerClient consumer = new ConsumerClient("inventory", "orders.v1",
                List.of(new Endpoint("localhost", server.getPort())), 1000, 20,
                new PersistentConsumerHandler("inventory", local, value -> value.getKey()),
                ConsumerFailureInjector.NONE)) {
            Thread thread = new Thread(consumer, "ack-loss-consumer");
            thread.start();
            try {
                assertTrue(persisted.await(5, TimeUnit.SECONDS), "Consumer must reconnect and send final ACK");
                assertEquals(0, nacks.get(), "An ACK transport failure is not a processing failure");
                assertEquals(2, attempts.get());
                assertEquals(1, local.effectCount("ORD-ACK-LOSS"));
            } finally {
                consumer.close();
                thread.interrupt();
                thread.join(3000);
                assertFalse(thread.isAlive());
            }
        } finally {
            server.shutdownNow();
            assertTrue(server.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
