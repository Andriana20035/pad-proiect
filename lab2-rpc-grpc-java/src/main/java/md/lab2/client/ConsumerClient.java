package md.lab2.client;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerHandler;
import md.lab2.consumer.ProcessResult;
import md.lab2.consumer.ProcessingException;
import md.lab2.consumer.SimulatedConsumerCrashException;
import md.lab2.logging.CorrelatedLog;
import md.lab2.rpc.v1.AckRequest;
import md.lab2.rpc.v1.AckStage;
import md.lab2.rpc.v1.Delivery;
import md.lab2.rpc.v1.MessagingServiceGrpc;
import md.lab2.rpc.v1.NackRequest;
import md.lab2.rpc.v1.SubscribeRequest;
import md.lab2.rpc.v1.UnsubscribeRequest;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ConsumerClient implements Runnable, AutoCloseable {
    private final String consumerId;
    private final String topic;
    private final RoundRobinEndpointPool endpoints;
    private final GrpcChannelPool channels = new GrpcChannelPool();
    private final int unaryDeadlineMs;
    private final long reconnectBaseMs;
    private final ConsumerHandler handler;
    private final ConsumerFailureInjector failureInjector;
    private volatile boolean running = true;
    private volatile Endpoint currentEndpoint;

    public ConsumerClient(
            String consumerId,
            String topic,
            List<Endpoint> endpoints,
            int unaryDeadlineMs,
            long reconnectBaseMs,
            ConsumerHandler handler,
            ConsumerFailureInjector failureInjector) {
        this.consumerId = consumerId;
        this.topic = topic;
        this.endpoints = new RoundRobinEndpointPool(endpoints);
        this.unaryDeadlineMs = unaryDeadlineMs;
        this.reconnectBaseMs = reconnectBaseMs;
        this.handler = handler;
        this.failureInjector = failureInjector;
    }

    @Override
    public void run() {
        int consecutiveFailures = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            Endpoint endpoint = endpoints.next();
            currentEndpoint = endpoint;
            ManagedChannel channel = channels.channel(endpoint);
            try {
                CorrelatedLog.event(
                        consumerId,
                        "CONNECT",
                        "-",
                        "-",
                        "endpoint=" + endpoint);

                MessagingServiceGrpc.MessagingServiceBlockingStub streamStub =
                        MessagingServiceGrpc.newBlockingStub(channel);
                Iterator<Delivery> deliveries = streamStub.subscribe(
                        SubscribeRequest.newBuilder()
                                .setConsumerId(consumerId)
                                .setTopic(topic)
                                .build());
                consecutiveFailures = 0;

                while (running && deliveries.hasNext()) {
                    Delivery delivery = deliveries.next();
                    handleDelivery(endpoint, delivery);
                }
            } catch (SimulatedConsumerCrashException e) {
                CorrelatedLog.event(
                        consumerId,
                        "CRASH_BEFORE_FINAL_ACK",
                        "-",
                        "-",
                        e.getMessage() + " endpoint=" + endpoint);
                channels.invalidate(endpoint);
                consecutiveFailures++;
            } catch (StatusRuntimeException e) {
                if (!running) {
                    break;
                }
                CorrelatedLog.event(
                        consumerId,
                        "STREAM_DISCONNECTED",
                        "-",
                        "-",
                        "endpoint=" + endpoint
                                + " status=" + e.getStatus().getCode());
                channels.invalidate(endpoint);
                consecutiveFailures++;
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                CorrelatedLog.event(
                        consumerId,
                        "CONSUMER_ERROR",
                        "-",
                        "-",
                        e.toString());
                channels.invalidate(endpoint);
                consecutiveFailures++;
            }

            if (running) {
                sleepQuietly(reconnectDelay(consecutiveFailures));
            }
        }
    }

    public void unsubscribe() {
        running = false;
        Endpoint active = currentEndpoint;
        if (active != null) {
            channels.invalidate(active);
        }
        StatusRuntimeException last = null;
        int attempts = Math.max(2, endpoints.size());
        for (int i = 0; i < attempts; i++) {
            Endpoint endpoint = endpoints.next();
            try {
                unaryStub(endpoint).unsubscribe(UnsubscribeRequest.newBuilder()
                        .setConsumerId(consumerId)
                        .setTopic(topic)
                        .build());
                return;
            } catch (StatusRuntimeException e) {
                last = e;
                if (!transportRetryable(e)) {
                    throw e;
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }

    public void stop() {
        running = false;
        Endpoint endpoint = currentEndpoint;
        if (endpoint != null) {
            channels.invalidate(endpoint);
        }
    }

    private void handleDelivery(Endpoint endpoint, Delivery delivery) {
        CorrelatedLog.event(
                consumerId,
                "RECEIVED",
                delivery.getMessage(),
                "deliveryId=" + delivery.getDeliveryId()
                        + " attempt=" + delivery.getAttempt()
                        + " broker=" + delivery.getBrokerNode());
        ack(endpoint, delivery, AckStage.RECEIVED);

        try {
            ProcessResult result = handler.process(delivery.getMessage());
            ack(endpoint, delivery, AckStage.PROCESSED);
            failureInjector.afterLocalPersist(delivery, result);
            ack(endpoint, delivery, AckStage.PERSISTED);
            CorrelatedLog.event(
                    consumerId,
                    "COMPLETED",
                    delivery.getMessage(),
                    "deliveryId=" + delivery.getDeliveryId()
                            + " applied=" + result.applied());
        } catch (SimulatedConsumerCrashException e) {
            throw e;
        } catch (ProcessingException e) {
            nack(endpoint, delivery, e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            nack(endpoint, delivery, e.getMessage(), true);
        }
    }

    private void ack(Endpoint endpoint, Delivery delivery, AckStage stage) {
        unaryStub(endpoint).ack(AckRequest.newBuilder()
                .setDeliveryId(delivery.getDeliveryId())
                .setConsumerId(consumerId)
                .setStage(stage)
                .build());
        CorrelatedLog.event(
                consumerId,
                "ACK_SENT_" + stage.name(),
                delivery.getMessage(),
                "deliveryId=" + delivery.getDeliveryId());
    }

    private void nack(
            Endpoint endpoint,
            Delivery delivery,
            String reason,
            boolean retryable) {
        unaryStub(endpoint).nack(NackRequest.newBuilder()
                .setDeliveryId(delivery.getDeliveryId())
                .setConsumerId(consumerId)
                .setReason(reason == null ? "processing error" : reason)
                .setRetryable(retryable)
                .build());
        CorrelatedLog.event(
                consumerId,
                "NACK_SENT",
                delivery.getMessage(),
                "deliveryId=" + delivery.getDeliveryId()
                        + " retryable=" + retryable);
    }

    private MessagingServiceGrpc.MessagingServiceBlockingStub unaryStub(
            Endpoint endpoint) {
        return MessagingServiceGrpc.newBlockingStub(channels.channel(endpoint))
                .withDeadlineAfter(unaryDeadlineMs, TimeUnit.MILLISECONDS);
    }

    private long reconnectDelay(int failures) {
        int shift = Math.min(Math.max(0, failures - 1), 5);
        return Math.min(reconnectBaseMs * (1L << shift), 5000L);
    }

    private boolean transportRetryable(StatusRuntimeException error) {
        Status.Code code = error.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
        channels.close();
    }
}
