package md.lab2.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import md.lab2.config.Endpoint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GrpcChannelPool implements AutoCloseable {
    private final Map<Endpoint, ManagedChannel> channels = new ConcurrentHashMap<>();

    public ManagedChannel channel(Endpoint endpoint) {
        return channels.computeIfAbsent(endpoint, key ->
                ManagedChannelBuilder.forAddress(key.host(), key.port())
                        .usePlaintext()
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .build());
    }

    public void invalidate(Endpoint endpoint) {
        ManagedChannel channel = channels.remove(endpoint);
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Override
    public void close() {
        for (ManagedChannel channel : channels.values()) {
            channel.shutdownNow();
        }
        channels.clear();
    }
}
