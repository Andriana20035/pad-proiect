package md.lab2.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import md.lab2.config.AppConfig;
import md.lab2.store.SqliteMessageStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class BrokerServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(BrokerServer.class.getName());
    private final AppConfig config;
    private final SqliteMessageStore store;
    private final SubscriptionNotifier subscriptionNotifier;
    private final ExecutorService rpcExecutor;
    private final ExecutorService subscriptionExecutor;
    private final RpcMessageService service;
    private volatile Server server;
    private boolean closed;

    public BrokerServer(AppConfig config) {
        this(config, PublishFailureInjector.NONE);
    }

    public BrokerServer(
            AppConfig config,
            PublishFailureInjector publishFailureInjector) {
        this.config = config;
        this.store = new SqliteMessageStore(
                config.databasePath(),
                config.maxAttempts(),
                config.leaseMs(),
                config.retryBaseMs(),
                config.retryMaxMs());

        /*
         * Short RPC handlers and long-lived Subscribe workers use different pools.
         * The cached subscription pool has no fixed eight-thread ceiling, so open
         * streams cannot consume all threads needed by Publish/Ack/Health.
         */
        this.rpcExecutor = Executors.newCachedThreadPool(
                namedThreadFactory("grpc-rpc-"));
        this.subscriptionExecutor = Executors.newCachedThreadPool(
                namedThreadFactory("grpc-subscribe-"));
        this.subscriptionNotifier = new SubscriptionNotifier();
        this.service = new RpcMessageService(
                store,
                config,
                publishFailureInjector,
                subscriptionExecutor,
                subscriptionNotifier);
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("BrokerServer is closed; create a new instance to restart");
        }
        if (server != null) {
            return;
        }
        server = ServerBuilder.forPort(config.port())
                .executor(rpcExecutor)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        service.setBoundPort(server.getPort());
        LOGGER.info(String.format(
                "Broker %s started on port %d, db=%s, transport=HTTP/2",
                config.nodeId(),
                server.getPort(),
                store.databasePath()));
    }

    public int port() {
        return server == null ? config.port() : server.getPort();
    }

    public String nodeId() {
        return config.nodeId();
    }

    public SqliteMessageStore store() {
        return store;
    }

    public void blockUntilShutdown() throws InterruptedException {
        Server currentServer = server;
        if (currentServer != null) {
            currentServer.awaitTermination();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        if (server != null) {
            server.shutdownNow();
        }
        subscriptionNotifier.signalAll();
        subscriptionExecutor.shutdownNow();

        boolean interrupted = false;
        try {
            // Keep the RPC executor available for gRPC cancellation callbacks.
            if (server != null) {
                if (!server.awaitTermination(
                        Math.max(0L, deadlineNanos - System.nanoTime()),
                        TimeUnit.NANOSECONDS)) {
                    LOGGER.warning("gRPC server did not terminate within the shutdown timeout");
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
        }
        rpcExecutor.shutdownNow();

        try {
            // gRPC termination alone does not wait for our SQLite-using workers.
            awaitExecutorTermination(subscriptionExecutor, "Subscription", deadlineNanos);
            awaitExecutorTermination(rpcExecutor, "RPC", deadlineNanos);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            server = null;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitExecutorTermination(
            ExecutorService executor,
            String name,
            long deadlineNanos) throws InterruptedException {
        if (!executor.awaitTermination(
                Math.max(0L, deadlineNanos - System.nanoTime()),
                TimeUnit.NANOSECONDS)) {
            LOGGER.warning(name + " executor did not terminate within the shutdown timeout");
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(
                    task,
                    prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
