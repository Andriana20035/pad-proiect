package md.lab2.server;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import md.lab2.config.AppConfig;
import md.lab2.logging.CorrelatedLog;
import md.lab2.rpc.v1.AckReply;
import md.lab2.rpc.v1.AckRequest;
import md.lab2.rpc.v1.CreateTopicRequest;
import md.lab2.rpc.v1.Delivery;
import md.lab2.rpc.v1.DlqEntry;
import md.lab2.rpc.v1.DlqReply;
import md.lab2.rpc.v1.DlqRequest;
import md.lab2.rpc.v1.HealthReply;
import md.lab2.rpc.v1.HealthRequest;
import md.lab2.rpc.v1.MessagingServiceGrpc;
import md.lab2.rpc.v1.NackReply;
import md.lab2.rpc.v1.NackRequest;
import md.lab2.rpc.v1.OperationReply;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.rpc.v1.PublishRequest;
import md.lab2.rpc.v1.StatsReply;
import md.lab2.rpc.v1.StatsRequest;
import md.lab2.rpc.v1.SubscribeRequest;
import md.lab2.rpc.v1.TopicReply;
import md.lab2.rpc.v1.UnsubscribeRequest;
import md.lab2.store.AckOutcome;
import md.lab2.store.DeliveryRecord;
import md.lab2.store.DlqRecord;
import md.lab2.store.NackOutcome;
import md.lab2.store.PublishOutcome;
import md.lab2.store.SqliteMessageStore;
import md.lab2.store.StatsSnapshot;
import md.lab2.store.StoreException;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RpcMessageService
        extends MessagingServiceGrpc.MessagingServiceImplBase {
    private final SqliteMessageStore store;
    private final AppConfig config;
    private final PublishFailureInjector publishFailureInjector;
    private final ExecutorService subscriptionExecutor;
    private final SubscriptionNotifier subscriptionNotifier;
    private volatile int boundPort;

    public RpcMessageService(
            SqliteMessageStore store,
            AppConfig config,
            PublishFailureInjector publishFailureInjector,
            ExecutorService subscriptionExecutor,
            SubscriptionNotifier subscriptionNotifier) {
        this.store = store;
        this.config = config;
        this.publishFailureInjector = publishFailureInjector;
        this.subscriptionExecutor = subscriptionExecutor;
        this.subscriptionNotifier = subscriptionNotifier;
        this.boundPort = config.port();
    }

    public void setBoundPort(int boundPort) {
        this.boundPort = boundPort;
    }

    @Override
    public void createTopic(
            CreateTopicRequest request,
            StreamObserver<TopicReply> responseObserver) {
        unary(responseObserver, () -> {
            String topic = validateTopicName(request.getName());
            boolean created = store.createTopic(topic);
            CorrelatedLog.event(
                    "broker:" + config.nodeId(),
                    "TOPIC_READY",
                    "-",
                    "-",
                    "topic=" + topic + " created=" + created);
            return TopicReply.newBuilder()
                    .setName(topic)
                    .setCreated(created)
                    .setBrokerNode(config.nodeId())
                    .build();
        });
    }

    @Override
    public void publish(
            PublishRequest request,
            StreamObserver<PublishReply> responseObserver) {
        unary(responseObserver, () -> {
            String topic = validateTopicName(request.getTopic());
            PublishOutcome outcome = store.publish(topic, request.getMessage());

            /*
             * Wake subscribers immediately after durable persistence. This happens
             * before the optional failure injector so a lost producer response does
             * not delay delivery of an already-persisted message.
             */
            subscriptionNotifier.signal(topic);

            CorrelatedLog.event(
                    "broker:" + config.nodeId(),
                    "PERSISTED",
                    request.getMessage(),
                    "topic=" + topic
                            + " routedTo=" + outcome.routedCount()
                            + " duplicate=" + outcome.duplicate());

            publishFailureInjector.afterPersist(request, outcome);

            return PublishReply.newBuilder()
                    .setAccepted(true)
                    .setDuplicate(outcome.duplicate())
                    .setRoutedCount(outcome.routedCount())
                    .setMessageId(request.getMessage().getId())
                    .setBrokerNode(config.nodeId())
                    .setDetail("Persisted before response")
                    .build();
        });
    }

    @Override
    public void subscribe(
            SubscribeRequest request,
            StreamObserver<Delivery> responseObserver) {
        String consumerId = request.getConsumerId();
        String topic = request.getTopic();
        try {
            requireText(consumerId, "consumerId");
            validateTopicName(topic);
            store.activateSubscription(consumerId, topic);

            CorrelatedLog.event(
                    "broker:" + config.nodeId(),
                    "SUBSCRIBED",
                    "-",
                    "-",
                    "consumer=" + consumerId + " topic=" + topic);

            Context callContext = Context.current();
            AtomicBoolean cancelled = new AtomicBoolean(false);
            ServerCallStreamObserver<Delivery> serverObserver = serverObserver(
                    responseObserver);

            if (serverObserver != null) {
                serverObserver.setOnCancelHandler(() -> {
                    cancelled.set(true);
                    subscriptionNotifier.signal(topic);
                });
                serverObserver.setOnReadyHandler(() ->
                        subscriptionNotifier.signal(topic));
            }

            /*
             * Important: do not run the long-lived delivery loop on the gRPC RPC
             * executor. Submit it to a dedicated elastic subscription executor and
             * return immediately so Publish/Ack/Nack/Health keep their RPC threads.
             */
            subscriptionExecutor.execute(() -> runSubscription(
                    callContext,
                    cancelled,
                    consumerId,
                    topic,
                    responseObserver,
                    serverObserver));
        } catch (RuntimeException e) {
            responseObserver.onError(toStatus(e).asRuntimeException());
        }
    }

    @Override
    public void unsubscribe(
            UnsubscribeRequest request,
            StreamObserver<OperationReply> responseObserver) {
        unary(responseObserver, () -> {
            store.deactivateSubscription(
                    request.getConsumerId(),
                    request.getTopic());
            subscriptionNotifier.signalAll();
            return OperationReply.newBuilder()
                    .setOk(true)
                    .setDetail("Subscription paused; pending deliveries are retained")
                    .setBrokerNode(config.nodeId())
                    .build();
        });
    }

    @Override
    public void ack(
            AckRequest request,
            StreamObserver<AckReply> responseObserver) {
        unary(responseObserver, () -> {
            AckOutcome outcome = store.ack(
                    request.getDeliveryId(),
                    request.getConsumerId(),
                    request.getStage());
            CorrelatedLog.event(
                    "broker:" + config.nodeId(),
                    "ACK_" + outcome.storedStage().name(),
                    "-",
                    "-",
                    "consumer=" + request.getConsumerId()
                            + " deliveryId=" + request.getDeliveryId()
                            + " completed=" + outcome.completed());

            if (outcome.completed()) {
                subscriptionNotifier.signalAll();
            }

            return AckReply.newBuilder()
                    .setAccepted(true)
                    .setCompleted(outcome.completed())
                    .setStoredStage(outcome.storedStage())
                    .setDetail(outcome.completed()
                            ? "Delivery completed durably"
                            : "ACK stage recorded; final PERSISTED ACK still required")
                    .setBrokerNode(config.nodeId())
                    .build();
        });
    }

    @Override
    public void nack(
            NackRequest request,
            StreamObserver<NackReply> responseObserver) {
        unary(responseObserver, () -> {
            NackOutcome outcome = store.nack(
                    request.getDeliveryId(),
                    request.getConsumerId(),
                    request.getReason(),
                    request.getRetryable());
            subscriptionNotifier.signalAll();
            return NackReply.newBuilder()
                    .setAccepted(true)
                    .setMovedToDlq(outcome.movedToDlq())
                    .setRetryAfterMs(outcome.retryAfterMs())
                    .setAttempt(outcome.attempt())
                    .setDetail(outcome.movedToDlq()
                            ? "Delivery moved to persistent DLQ"
                            : "Retry scheduled with exponential backoff")
                    .setBrokerNode(config.nodeId())
                    .build();
        });
    }

    @Override
    public void getStats(
            StatsRequest request,
            StreamObserver<StatsReply> responseObserver) {
        unary(responseObserver, () -> {
            StatsSnapshot stats = store.stats(
                    request.getTopic(),
                    request.getConsumerId());
            return StatsReply.newBuilder()
                    .setMessages(stats.messages())
                    .setPending(stats.pending())
                    .setInFlight(stats.inFlight())
                    .setCompleted(stats.completed())
                    .setDlq(stats.dlq())
                    .setActiveSubscriptions(stats.activeSubscriptions())
                    .setBrokerNode(config.nodeId())
                    .build();
        });
    }

    @Override
    public void listDlq(
            DlqRequest request,
            StreamObserver<DlqReply> responseObserver) {
        unary(responseObserver, () -> {
            DlqReply.Builder builder = DlqReply.newBuilder()
                    .setBrokerNode(config.nodeId());
            for (DlqRecord record : store.listDlq(
                    request.getTopic(),
                    request.getConsumerId())) {
                builder.addEntries(DlqEntry.newBuilder()
                        .setDeliveryId(record.deliveryId())
                        .setMessageId(record.messageId())
                        .setConsumerId(record.consumerId())
                        .setTopic(record.topic())
                        .setAttempts(record.attempts())
                        .setReason(record.reason())
                        .setMovedAt(record.movedAt())
                        .build());
            }
            return builder.build();
        });
    }

    @Override
    public void health(
            HealthRequest request,
            StreamObserver<HealthReply> responseObserver) {
        responseObserver.onNext(HealthReply.newBuilder()
                .setServing(true)
                .setBrokerNode(config.nodeId())
                .setPort(boundPort)
                .setTransport("gRPC over HTTP/2")
                .setStorage("SQLite WAL")
                .build());
        responseObserver.onCompleted();
    }

    private void runSubscription(
            Context callContext,
            AtomicBoolean cancelled,
            String consumerId,
            String topic,
            StreamObserver<Delivery> responseObserver,
            ServerCallStreamObserver<Delivery> serverObserver) {
        long observedVersion = subscriptionNotifier.snapshot(topic);
        try {
            while (subscriptionActive(callContext, cancelled)
                    && !Thread.currentThread().isInterrupted()) {
                if (serverObserver != null && !serverObserver.isReady()) {
                    observedVersion = subscriptionNotifier.awaitChange(
                            topic,
                            observedVersion,
                            config.crossNodeRefreshMs());
                    continue;
                }

                Optional<DeliveryRecord> next = store.claimNextDelivery(
                        consumerId,
                        topic);
                if (next.isPresent()) {
                    DeliveryRecord record = next.get();
                    Delivery delivery = Delivery.newBuilder()
                            .setDeliveryId(record.deliveryId())
                            .setTopic(record.topic())
                            .setConsumerId(record.consumerId())
                            .setMessage(record.message())
                            .setAttempt(record.attempt())
                            .setLeaseDeadlineEpochMs(record.leaseDeadlineEpochMs())
                            .setBrokerNode(config.nodeId())
                            .build();
                    CorrelatedLog.event(
                            "broker:" + config.nodeId(),
                            "DELIVER",
                            record.message(),
                            "consumer=" + consumerId
                                    + " deliveryId=" + record.deliveryId()
                                    + " attempt=" + record.attempt());
                    responseObserver.onNext(delivery);
                    continue;
                }

                long waitMs = store.nextWakeDelayMs(
                        consumerId,
                        topic,
                        config.crossNodeRefreshMs());
                observedVersion = subscriptionNotifier.awaitChange(
                        topic,
                        observedVersion,
                        waitMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (subscriptionActive(callContext, cancelled)) {
                responseObserver.onError(Status.CANCELLED
                        .withDescription("Subscription worker interrupted")
                        .asRuntimeException());
            }
        } catch (RuntimeException e) {
            if (subscriptionActive(callContext, cancelled)) {
                responseObserver.onError(toStatus(e).asRuntimeException());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ServerCallStreamObserver<Delivery> serverObserver(
            StreamObserver<Delivery> observer) {
        if (observer instanceof ServerCallStreamObserver<?>) {
            return (ServerCallStreamObserver<Delivery>) observer;
        }
        return null;
    }

    private boolean subscriptionActive(
            Context callContext,
            AtomicBoolean cancelled) {
        return !cancelled.get() && !callContext.isCancelled();
    }

    private String validateTopicName(String topic) {
        requireText(topic, "topic");
        if (!topic.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "Topic must match [A-Za-z0-9._-]{1,100}");
        }
        return topic;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    field + " is required");
        }
    }

    private <T> void unary(
            StreamObserver<T> observer,
            CheckedSupplier<T> supplier) {
        try {
            observer.onNext(supplier.get());
            observer.onCompleted();
        } catch (RuntimeException e) {
            observer.onError(toStatus(e).asRuntimeException());
        }
    }

    private Status toStatus(RuntimeException error) {
        if (error instanceof io.grpc.StatusRuntimeException statusError) {
            return statusError.getStatus();
        }
        if (error instanceof StoreException storeError) {
            return switch (storeError.kind()) {
                case NOT_FOUND -> Status.NOT_FOUND.withDescription(storeError.getMessage());
                case CONFLICT -> Status.ALREADY_EXISTS.withDescription(storeError.getMessage());
                case PRECONDITION -> Status.FAILED_PRECONDITION
                        .withDescription(storeError.getMessage());
                case STORAGE -> Status.INTERNAL.withDescription(storeError.getMessage());
            };
        }
        return Status.INTERNAL
                .withDescription(error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage());
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get();
    }
}
