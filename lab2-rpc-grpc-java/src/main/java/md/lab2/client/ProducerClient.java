package md.lab2.client;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.config.Endpoint;
import md.lab2.logging.CorrelatedLog;
import md.lab2.rpc.v1.CreateTopicRequest;
import md.lab2.rpc.v1.DlqReply;
import md.lab2.rpc.v1.DlqRequest;
import md.lab2.rpc.v1.Envelope;
import md.lab2.rpc.v1.MessagingServiceGrpc;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.rpc.v1.PublishRequest;
import md.lab2.rpc.v1.StatsReply;
import md.lab2.rpc.v1.StatsRequest;
import md.lab2.rpc.v1.TopicReply;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ProducerClient implements AutoCloseable {
    private final RoundRobinEndpointPool endpoints;
    private final GrpcChannelPool channels;
    private final int deadlineMs;
    private final int maxRpcAttempts;

    public ProducerClient(List<Endpoint> endpoints, int deadlineMs) {
        this(endpoints, deadlineMs, Math.max(3, endpoints.size()));
    }

    public ProducerClient(
            List<Endpoint> endpoints,
            int deadlineMs,
            int maxRpcAttempts) {
        this.endpoints = new RoundRobinEndpointPool(endpoints);
        this.channels = new GrpcChannelPool();
        this.deadlineMs = deadlineMs;
        this.maxRpcAttempts = maxRpcAttempts;
    }

    public TopicReply createTopic(String topic) {
        return callUnary(stub -> stub.createTopic(
                CreateTopicRequest.newBuilder().setName(topic).build()));
    }

    public PublishReply publish(String topic, Envelope message) {
        PublishRequest request = PublishRequest.newBuilder()
                .setTopic(topic)
                .setMessage(message)
                .build();
        StatusRuntimeException last = null;
        for (int attempt = 1; attempt <= maxRpcAttempts; attempt++) {
            Endpoint endpoint = endpoints.next();
            try {
                MessagingServiceGrpc.MessagingServiceBlockingStub stub =
                        blockingStub(endpoint);
                PublishReply reply = stub.publish(request);
                CorrelatedLog.event(
                        "producer",
                        "PUBLISH_CONFIRMED",
                        message,
                        "endpoint=" + endpoint
                                + " broker=" + reply.getBrokerNode()
                                + " duplicate=" + reply.getDuplicate()
                                + " routedTo=" + reply.getRoutedCount());
                return reply;
            } catch (StatusRuntimeException e) {
                last = e;
                if (!isRetryableTransportError(e) || attempt == maxRpcAttempts) {
                    throw e;
                }
                CorrelatedLog.event(
                        "producer",
                        "RPC_RETRY",
                        message,
                        "endpoint=" + endpoint
                                + " attempt=" + attempt
                                + " status=" + e.getStatus().getCode());
                channels.invalidate(endpoint);
                sleepQuietly(Math.min(100L * attempt, 500L));
            }
        }
        throw last == null
                ? Status.UNAVAILABLE.withDescription("No endpoint available").asRuntimeException()
                : last;
    }

    public PublishReply publishOrderCreated(
            String topic,
            String orderId,
            String payloadJson) {
        return publish(topic, orderCreated(orderId, payloadJson));
    }

    public StatsReply stats(String topic, String consumerId) {
        return callUnary(stub -> stub.getStats(StatsRequest.newBuilder()
                .setTopic(topic == null ? "" : topic)
                .setConsumerId(consumerId == null ? "" : consumerId)
                .build()));
    }

    public DlqReply listDlq(String topic, String consumerId) {
        return callUnary(stub -> stub.listDlq(DlqRequest.newBuilder()
                .setTopic(topic == null ? "" : topic)
                .setConsumerId(consumerId == null ? "" : consumerId)
                .build()));
    }

    public static Envelope orderCreated(String orderId, String payloadJson) {
        String id = UUID.nameUUIDFromBytes(
                ("LAB2:ORDER_CREATED:" + orderId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        String correlationId = UUID.nameUUIDFromBytes(
                ("LAB2:ORDER_FLOW:" + orderId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return Envelope.newBuilder()
                .setId(id)
                .setTimestamp(Instant.now().toString())
                .setCorrelationId(correlationId)
                .setSchemaVersion("1.0")
                .setType("ORDER_CREATED")
                .setKey(orderId)
                .setPayload(ByteString.copyFromUtf8(payloadJson))
                .build();
    }

    private MessagingServiceGrpc.MessagingServiceBlockingStub blockingStub(
            Endpoint endpoint) {
        ManagedChannel channel = channels.channel(endpoint);
        return MessagingServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }

    private <T> T callUnary(StubCall<T> call) {
        StatusRuntimeException last = null;
        for (int attempt = 1; attempt <= maxRpcAttempts; attempt++) {
            Endpoint endpoint = endpoints.next();
            try {
                return call.execute(blockingStub(endpoint));
            } catch (StatusRuntimeException e) {
                last = e;
                if (!isRetryableTransportError(e) || attempt == maxRpcAttempts) {
                    throw e;
                }
                channels.invalidate(endpoint);
                sleepQuietly(Math.min(100L * attempt, 500L));
            }
        }
        throw last == null
                ? Status.UNAVAILABLE.asRuntimeException()
                : last;
    }

    private boolean isRetryableTransportError(StatusRuntimeException error) {
        Status.Code code = error.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        channels.close();
    }

    @FunctionalInterface
    private interface StubCall<T> {
        T execute(MessagingServiceGrpc.MessagingServiceBlockingStub stub);
    }
}
