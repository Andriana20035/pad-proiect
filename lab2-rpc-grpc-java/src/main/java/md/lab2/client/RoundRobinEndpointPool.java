package md.lab2.client;

import md.lab2.config.Endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class RoundRobinEndpointPool {
    private final List<Endpoint> endpoints;
    private final AtomicInteger cursor = new AtomicInteger();

    public RoundRobinEndpointPool(List<Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("At least one gRPC endpoint is required");
        }
        this.endpoints = List.copyOf(endpoints);
    }

    public Endpoint next() {
        int index = Math.floorMod(cursor.getAndIncrement(), endpoints.size());
        return endpoints.get(index);
    }

    public List<Endpoint> nextCandidates(int count) {
        int actualCount = Math.max(1, count);
        List<Endpoint> result = new ArrayList<>(actualCount);
        for (int i = 0; i < actualCount; i++) {
            result.add(next());
        }
        return result;
    }

    public int size() {
        return endpoints.size();
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }
}
