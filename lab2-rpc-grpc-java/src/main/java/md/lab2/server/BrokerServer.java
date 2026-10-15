package md.lab2.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import md.lab2.config.AppConfig;
import md.lab2.store.SqliteMessageStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BrokerServer implements AutoCloseable {
    private final AppConfig config;
    private final SqliteMessageStore store;
    private final RpcMessageService service;
    private final ExecutorService executor;
    private Server server;

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
        this.service = new RpcMessageService(
                store,
                config,
                publishFailureInjector);
        this.executor = Executors.newFixedThreadPool(config.serverThreads());
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        server = ServerBuilder.forPort(config.port())
                .executor(executor)
                .addService(service)
                .addService(ProtoReflectionService.newInstance())
                .build()
                .start();
        service.setBoundPort(server.getPort());
        System.out.printf(
                "Broker %s started on port %d, db=%s, transport=HTTP/2%n",
                config.nodeId(),
                server.getPort(),
                store.databasePath());
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
        if (server != null) {
            server.awaitTermination();
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.shutdownNow();
            try {
                server.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server = null;
        }
        executor.shutdownNow();
    }
}
