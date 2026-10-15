package md.lab2.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import md.lab2.config.Endpoint;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public final class GrpcChannelPool implements AutoCloseable {
    private final Map<Endpoint, ManagedChannel> channels = new HashMap<>();
    private boolean closed;

    public synchronized ManagedChannel channel(Endpoint endpoint) {
        if (closed) {
            throw new IllegalStateException("gRPC channel pool is closed");
        }
        return channels.computeIfAbsent(endpoint, key ->
                ManagedChannelBuilder.forAddress(key.host(), key.port())
                        .usePlaintext()
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .build());
    }

    public synchronized void invalidate(Endpoint endpoint) {
        ManagedChannel channel = channels.remove(endpoint);
        if (channel != null) {
            shutdownChannel(channel);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (ManagedChannel channel : channels.values()) {
            shutdownChannel(channel);
        }
        channels.clear();
    }

    private static void shutdownChannel(ManagedChannel channel) {
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
