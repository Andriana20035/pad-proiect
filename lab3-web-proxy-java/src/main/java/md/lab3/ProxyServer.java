package md.lab3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

final class ProxyServer implements AutoCloseable {
    private static final class Endpoint {
        final URI uri;
        final CircuitBreaker breaker;
        final LongAdder chosen = new LongAdder();
        volatile boolean healthy = true;
        Endpoint(URI uri, int threshold, int breakerOpenMs) {
            this.uri = uri;
            this.breaker = new CircuitBreaker(threshold, breakerOpenMs);
        }
    }
    private record Upstream(int status, byte[] data, String contentType, String node) {}

    private record FlightKey(String cacheKey, long generation) {}

    private final HttpServer server;
    private final HttpClient client;
    private final List<Endpoint> backends = new ArrayList<>();
    private final AtomicInteger rr = new AtomicInteger();
    private final Semaphore inFlight;
    private final int maxConcurrent, requestTimeoutMs;
    private final TtlCache cache;
    private final LatencyMetrics metrics = new LatencyMetrics();
    private final ScheduledExecutorService healthChecks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r,"lab3-health-check"); t.setDaemon(true); return t;
    });
    private final Object cacheGenerationLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong requestIds = new AtomicLong();
    // Client request registry: used by the smart proxy and exposed in /metrics.
    private record ClientRequest(String correlationId, String clientAddress, String path, long startedNanos) {}
    private final ConcurrentHashMap<Long,ClientRequest> activeClients = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<FlightKey, CompletableFuture<Upstream>> pendingGets =
        new ConcurrentHashMap<>();

    ProxyServer(int port, List<String> addresses, int ttlMs, int staleMs, int maxConcurrent,
                int requestTimeoutMs, int breakerThreshold, int breakerOpenMs) throws IOException {
        if (addresses.isEmpty()) throw new IllegalArgumentException("At least one backend required");
        for (String address : addresses) backends.add(new Endpoint(URI.create(address), breakerThreshold, breakerOpenMs));
        this.server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("LAB3_BIND_HOST", "127.0.0.1"), port), 128);
        this.server.setExecutor(Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r,"lab3-proxy"); t.setDaemon(true); return t;
        }));
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.min(300, requestTimeoutMs))).build();
        this.maxConcurrent = maxConcurrent;
        this.inFlight = new Semaphore(maxConcurrent);
        this.requestTimeoutMs = requestTimeoutMs;
        this.cache = new TtlCache(ttlMs, staleMs, 512);
        server.createContext("/", this::handle);
    }
    void start() {
        probeBackends();
        server.start();
        healthChecks.scheduleWithFixedDelay(this::probeBackends,500,500,TimeUnit.MILLISECONDS);
    }
    private void probeBackends() {
        for (Endpoint endpoint : backends) {
            try {
                HttpRequest req = HttpRequest.newBuilder(endpoint.uri.resolve("/health"))
                        .timeout(Duration.ofMillis(250)).GET().build();
                int status = client.send(req,HttpResponse.BodyHandlers.discarding()).statusCode();
                endpoint.healthy = status >= 200 && status < 300;
            } catch (Exception e) {
                endpoint.healthy = false;
            }
        }
    }
    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/metrics")) {
            StringBuilder nodes = new StringBuilder("[");
            for (int i = 0; i < backends.size(); i++) {
                if (i > 0) nodes.append(',');
                Endpoint e = backends.get(i);
                nodes.append("{\"url\":\"").append(HttpUtil.jsonEscape(e.uri.toString()))
                     .append("\",\"circuit\":\"").append(e.breaker.state())
                     .append("\",\"healthy\":").append(e.healthy)
                     .append(",\"failures\":").append(e.breaker.failureCount())
                     .append(",\"selected\":").append(e.chosen.sum()).append('}');
            }
            nodes.append(']');
            String snapshot = metrics.snapshot(nodes.toString(), cache.size(), maxConcurrent-inFlight.availablePermits());
            // Real in-flight registry, not merely bookkeeping: expose correlation, path and client.
            StringBuilder details = new StringBuilder("[");
            boolean first = true;
            for (var active : activeClients.values()) {
                if (!first) details.append(',');
                details.append("{\"correlationId\":\"").append(HttpUtil.jsonEscape(active.correlationId()))
                        .append("\",\"client\":\"").append(HttpUtil.jsonEscape(active.clientAddress()))
                        .append("\",\"path\":\"").append(HttpUtil.jsonEscape(active.path()))
                        .append("\"}");
                first = false;
            }
            details.append(']');

        snapshot = snapshot.substring(0, snapshot.length() - 1)
                + ",\"activeClients\":" + details
                + ",\"cacheGeneration\":" + generation.get()
                + ",\"pendingGetKeys\":" + pendingGets.size()
                + ",\"upstreamLimit\":" + maxConcurrent
                + "}";

            HttpUtil.send(ex,200,snapshot,"application/json");
            return;
        }
        if (path.equals("/health")) {
            HttpUtil.send(ex,200,"{\"proxy\":true,\"backends\":"+backends.size()+"}","application/json");
            return;
        }
        if (path.equals("/admin/cache/invalidate") && ex.getRequestMethod().equals("POST")) {
            String prefix = HttpUtil.query(ex.getRequestURI().getRawQuery()).getOrDefault("prefix", "/api/");
            if (!prefix.startsWith("/api/") || prefix.length() > 128) {
                HttpUtil.send(ex,400,HttpUtil.error("invalid_prefix",prefix),"application/json"); return;
            }
            int invalidated;

            synchronized (cacheGenerationLock) {
                generation.incrementAndGet();
                invalidated = cache.invalidate(prefix);
            }

            HttpUtil.send(ex, 200,
                    "{\"invalidated\":" + invalidated + "}",
                    "application/json");

            return;
        }
        if (!path.startsWith("/api/")) {
            HttpUtil.send(ex,404,HttpUtil.error("not_found",path),"application/json"); return;
        }
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("PUT")) {
            HttpUtil.send(ex,405,HttpUtil.error("method_not_allowed",ex.getRequestMethod()),"application/json"); return;
        }
        long start = System.nanoTime();
        String correlation = ex.getRequestHeaders().getFirst("X-Correlation-Id");
        if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
        ex.getResponseHeaders().set("X-Correlation-Id",correlation);



        long requestId = requestIds.incrementAndGet();
        activeClients.put(requestId, new ClientRequest(correlation,
                ex.getRemoteAddress().toString(),path,start));
        try {
            if (ex.getRequestMethod().equals("GET")) get(ex, correlation);
            else put(ex, correlation);
        } finally {
            activeClients.remove(requestId);

            metrics.recordMicros((System.nanoTime()-start)/1000);
        }
    }


    private void overloaded(HttpExchange ex) throws IOException {
        metrics.rejected.increment();
        ex.getResponseHeaders().set("Retry-After", "1");
        ex.getResponseHeaders().set("X-Degraded", "load-shed");

        HttpUtil.send(ex, 429,
                HttpUtil.error("overloaded",
                        "upstream concurrency limit reached"),
                "application/json");
    }

    private TtlCache.Entry freshAtGeneration(
            String key, long expectedGeneration) {

        synchronized (cacheGenerationLock) {
            return generation.get() == expectedGeneration
                    ? cache.fresh(key)
                    : null;
        }
    }


    private String key(HttpExchange ex) {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        return ex.getRequestURI().toASCIIString() + "|accept=" + (accept == null ? "" : accept);
    }

    private void get(HttpExchange ex, String correlation) throws IOException {
        String cacheKey = key(ex);
        long seenGeneration = generation.get();

        // Verificam cache-ul inainte de limita de concurenta
        TtlCache.Entry cached = freshAtGeneration(cacheKey, seenGeneration);

        if (cached != null) {
            metrics.cacheHit.increment();
            ex.getResponseHeaders().set("X-Smart-Proxy", "CACHE");
            reply(ex, 200, cached.body(), cached.type(),
                    cached.backend(), "HIT");
            return;
        }

        // Verificam daca alt client a pornit deja aceeasi cerere
        FlightKey flightKey = new FlightKey(cacheKey, seenGeneration);
        CompletableFuture<Upstream> flight = pendingGets.get(flightKey);

        if (flight != null) {
            serveCoalesced(ex, flight, cacheKey);
            return;
        }

        // Limitam doar solicitarile care au nevoie de backend
        if (!inFlight.tryAcquire()) {
            cached = freshAtGeneration(cacheKey, seenGeneration);

            if (cached != null) {
                metrics.cacheHit.increment();
                ex.getResponseHeaders().set("X-Smart-Proxy", "CACHE");
                reply(ex, 200, cached.body(), cached.type(),
                        cached.backend(), "HIT");
                return;
            }

            flight = pendingGets.get(flightKey);
            if (flight != null) {
                serveCoalesced(ex, flight, cacheKey);
                return;
            }

            overloaded(ex);
            return;
        }

        CompletableFuture<Upstream> created = new CompletableFuture<>();
        flight = pendingGets.putIfAbsent(flightKey, created);

        if (flight != null) {
            inFlight.release();
            serveCoalesced(ex, flight, cacheKey);
            return;
        }

        try {
            cached = freshAtGeneration(cacheKey, seenGeneration);

            if (cached != null) {
                metrics.cacheHit.increment();
                created.complete(new Upstream(200, cached.body(),
                        cached.type(), cached.backend()));

                ex.getResponseHeaders().set("X-Smart-Proxy", "CACHE");
                reply(ex, 200, cached.body(), cached.type(),
                        cached.backend(), "HIT");
                return;
            }

            metrics.cacheMiss.increment();

            Upstream result = fetch(ex, "GET", correlation);

            if (result != null && result.status == 200) {
                synchronized (cacheGenerationLock) {
                    if (generation.get() == seenGeneration) {
                        cache.put(cacheKey, result.data,
                                result.contentType, result.node);
                    }
                }
            }

            created.complete(result);
            ex.getResponseHeaders().set("X-Smart-Proxy", "FORWARDED");
            finishGet(ex, cacheKey, result, "MISS");

        } catch (RuntimeException | Error failure) {
            created.completeExceptionally(failure);
            throw failure;

        } finally {
            pendingGets.remove(flightKey, created);
            inFlight.release();
        }
    }

    // Reutilizam rezultatul unei cereri GET deja pornite
    private void serveCoalesced(
            HttpExchange ex,
            CompletableFuture<Upstream> flight,
            String cacheKey) throws IOException {

        metrics.coalescedGet.increment();
        ex.getResponseHeaders().set("X-Smart-Proxy", "COALESCED");

        try {
            Upstream result = flight.get(
                    (long) requestTimeoutMs * backends.size() + 1500,
                    TimeUnit.MILLISECONDS);

            finishGet(ex, cacheKey, result, "MISS");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishGet(ex, cacheKey, null, "MISS");

        } catch (TimeoutException | ExecutionException e) {
            finishGet(ex, cacheKey, null, "MISS");
        }
    }

    // Trimitem raspunsul catre client
    private void finishGet(
            HttpExchange ex,
            String cacheKey,
            Upstream result,
            String cacheHeader) throws IOException {

        if (result != null && result.status < 500) {
            if (result.status >= 200 && result.status < 300) {
                metrics.successful.increment();
            }

            reply(ex, result.status, result.data,
                    result.contentType, result.node, cacheHeader);
            return;
        }

        TtlCache.Entry stale = cache.stale(cacheKey);

        if (stale != null) {
            metrics.cacheStale.increment();
            ex.getResponseHeaders().set(
                    "Warning", "110 - \"Response is stale\"");
            ex.getResponseHeaders().set(
                    "X-Degraded", "stale-if-error");

            reply(ex, 200, stale.body(), stale.type(),
                    stale.backend(), "STALE");
            return;
        }

        ex.getResponseHeaders().set(
                "X-Degraded", "upstream-unavailable");

        int status = result == null
                ? 503
                : (result.status == 504 ? 504 : 503);

        reply(ex, status,
                HttpUtil.error(
                        "upstream_unavailable",
                        "all backends failed or circuit open")
                        .getBytes(StandardCharsets.UTF_8),
                "application/json", "none", cacheHeader);
    }



    private void put(HttpExchange ex, String correlation) throws IOException {
        byte[] payload = ex.getRequestBody().readAllBytes();

        if (payload.length > 4096) {
            reply(ex, 413,
                    HttpUtil.error("too_large", "max 4096 bytes")
                            .getBytes(StandardCharsets.UTF_8),
                    "application/json", "none", "BYPASS");
            return;
        }

        // Limitam operatiile catre DW, nu citirile din cache
        if (!inFlight.tryAcquire()) {
            overloaded(ex);
            return;
        }

        try {
            // Invalidam cache-ul inainte de scriere
            synchronized (cacheGenerationLock) {
                generation.incrementAndGet();
                cache.invalidate("/api/orders");
                cache.invalidate("/api/stats");
            }

            Set<Endpoint> attempted = new HashSet<>();
            Upstream response = null;

            for (int tries = 0; tries < backends.size(); tries++) {
                Endpoint endpoint = select(attempted);

                if (endpoint == null) break;
                attempted.add(endpoint);

                try {
                    Upstream current = request(
                            endpoint, ex, "PUT", payload, correlation);

                    if (current.status < 500) {
                        endpoint.breaker.success();
                        response = current;
                        break;
                    }

                    metrics.upstreamFailures.increment();
                    endpoint.breaker.failure();

                } catch (Exception failure) {
                    metrics.upstreamFailures.increment();
                    endpoint.breaker.failure();
                }
            }

            // Invalidam si dupa scriere
            synchronized (cacheGenerationLock) {
                generation.incrementAndGet();
                cache.invalidate("/api/orders");
                cache.invalidate("/api/stats");
            }

            if (response == null) {
                ex.getResponseHeaders().set(
                        "X-Degraded", "upstream-unavailable");

                reply(ex, 503,
                        HttpUtil.error(
                                "write_failed",
                                "no available backend acknowledged the write")
                                .getBytes(StandardCharsets.UTF_8),
                        "application/json", "none", "INVALIDATED");

            } else {
                if (response.status >= 200 && response.status < 300) {
                    metrics.successful.increment();
                }

                reply(ex, response.status, response.data,
                        response.contentType, response.node, "INVALIDATED");
            }

        } finally {
            // Eliberam locul indiferent de rezultat
            inFlight.release();
        }
    }

        private Upstream fetch(HttpExchange ex, String method, String correlation) {
            Set<Endpoint> attempted = new HashSet<>();
            Upstream last = null;
            // Each backend is tried at most once; retries are restricted to idempotent GET.
            for (int tryNo = 0; tryNo < backends.size(); tryNo++) {
                Endpoint endpoint = select(attempted);
                if (endpoint == null) break;
                attempted.add(endpoint);
                try {
                    Upstream result = request(endpoint,ex,method,new byte[0],correlation);
                    if (result.status >= 500) {
                        metrics.upstreamFailures.increment();
                        endpoint.breaker.failure();
                        last = result;
                    } else {
                        endpoint.breaker.success();
                        return result;
                    }
                } catch (java.net.http.HttpTimeoutException e) {
                    metrics.upstreamFailures.increment();
                    endpoint.breaker.failure();
                    last = new Upstream(504,new byte[0],"application/json",endpoint.uri.toString());
                } catch (Exception e) {
                    metrics.upstreamFailures.increment();
                    endpoint.breaker.failure();
                    last = new Upstream(503,new byte[0],"application/json",endpoint.uri.toString());
                }
                if (tryNo + 1 < backends.size()) {
                    metrics.retries.increment();
                    // Exponential backoff (bounded) plus random jitter prevents synchronized retries.
                    long base = Math.min(250,40L << Math.min(3,tryNo));
                    try { Thread.sleep(base + ThreadLocalRandom.current().nextLong(0,41)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            return last;
        }
        private Endpoint select(Set<Endpoint> attempted) {
            int start = Math.floorMod(rr.getAndIncrement(),backends.size());
            for (int i = 0; i < backends.size(); i++) {
                Endpoint endpoint = backends.get((start+i)%backends.size());
                if (!attempted.contains(endpoint) && endpoint.healthy && endpoint.breaker.acquire()) return endpoint;
            }
            return null;
        }
    private Upstream request(Endpoint endpoint,HttpExchange ex,String method,byte[] body,String correlation)
            throws IOException, InterruptedException {
        endpoint.chosen.increment();
        URI target = endpoint.uri.resolve(ex.getRequestURI().toASCIIString());
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofMillis(requestTimeoutMs))
                .header("X-Correlation-Id",correlation);
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept != null) builder.header("Accept",accept);
        if (method.equals("GET")) builder.GET();
        else builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> result = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Upstream(result.statusCode(),result.body(),
                result.headers().firstValue("Content-Type").orElse("application/json"),
                result.headers().firstValue("X-Backend").orElse(endpoint.uri.toString()));
    }
    private void reply(HttpExchange ex,int code,byte[] body,String type,String node,String hit) throws IOException {
        ex.getResponseHeaders().set("X-Cache",hit);
        ex.getResponseHeaders().set("X-Backend",node);
        HttpUtil.send(ex,code,body,type);
    }
    @Override public void close() { healthChecks.shutdownNow(); server.stop(0); }
}
