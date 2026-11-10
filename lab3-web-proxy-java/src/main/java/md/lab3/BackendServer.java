package md.lab3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** Independent HTTP endpoint with programmable slowness/outage for reproducible tests. */
final class BackendServer implements AutoCloseable {
    private final HttpServer server;
    private final WarehouseStore store;
    private final String node;
    private final URI storageUrl;
    private final HttpClient storageHttp = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    private volatile String fault = "ok";
    private volatile int delayMs = 0;

    BackendServer(int port, String node, WarehouseStore store) throws IOException {
        this(port,node,store,null);
    }
    BackendServer(int port, String node, URI storageUrl) throws IOException {
        this(port,node,null,storageUrl);
    }
    private BackendServer(int port, String node, WarehouseStore store, URI storageUrl) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("LAB3_BIND_HOST", "127.0.0.1"), port), 64);
        this.server.setExecutor(Executors.newFixedThreadPool(48, r -> {
            Thread t = new Thread(r, "warehouse-" + node); t.setDaemon(true); return t;
        }));
        this.store = store;
        this.storageUrl = storageUrl;
        this.node = node;
        server.createContext("/", this::handle);
    }
    void start() { server.start(); }
    private void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("X-Backend", node);
        String path = ex.getRequestURI().getPath();
        Map<String,String> q = HttpUtil.query(ex.getRequestURI().getRawQuery());
        if (path.equals("/health")) {
            if (fault.equals("fail")) {
                HttpUtil.send(ex,503,HttpUtil.error("backend_unhealthy",node),"application/json");return;
            }
            if (storageUrl != null) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(storageUrl.resolve("/health"))
                            .timeout(Duration.ofMillis(300)).GET().build();
                    if (storageHttp.send(req,HttpResponse.BodyHandlers.discarding()).statusCode() != 200) {
                        HttpUtil.send(ex,503,HttpUtil.error("storage_unhealthy",node),"application/json");return;
                    }
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    HttpUtil.send(ex,503,HttpUtil.error("storage_interrupted",node),"application/json");return;
                } catch (IOException unavailable) {
                    HttpUtil.send(ex,503,HttpUtil.error("storage_unavailable",node),"application/json");return;
                }
            }
            HttpUtil.send(ex, 200, "{\"node\":\""+node+"\",\"up\":true}", "application/json"); return;
        }
        if (path.equals("/admin/fault") && ex.getRequestMethod().equals("POST")) {
            String mode = q.getOrDefault("mode", "ok").toLowerCase(Locale.ROOT);
            if (!mode.equals("ok") && !mode.equals("slow") && !mode.equals("fail")) {
                HttpUtil.send(ex, 400, HttpUtil.error("bad_mode", mode), "application/json"); return;
            }
            int delay;
            try { delay = Integer.parseInt(q.getOrDefault("delayMs", "0")); }
            catch (NumberFormatException e) { delay = -1; }
            if (delay < 0 || delay > 30_000) {
                HttpUtil.send(ex, 400, HttpUtil.error("bad_delay", "0..30000"), "application/json"); return;
            }
            fault = mode;
            delayMs = delay;
            HttpUtil.send(ex, 200, "{\"node\":\"" + node + "\",\"mode\":\""+mode+"\",\"delayMs\":"+delay+"}", "application/json");
            return;
        }
        if (!path.startsWith("/api/")) {
            HttpUtil.send(ex, 404, HttpUtil.error("not_found", path), "application/json"); return;
        }
        String correlation = ex.getRequestHeaders().getFirst("X-Correlation-Id");
        if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
        ex.getResponseHeaders().set("X-Correlation-Id", correlation);
        System.out.println("[" + node + "] correlationId=" + correlation + " method="
                + ex.getRequestMethod() + " path=" + path);
        if (fault.equals("fail")) {
            HttpUtil.send(ex, 503, HttpUtil.error("backend_unavailable", node), "application/json"); return;
        }
        if (fault.equals("slow")) {
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (storageUrl != null) {
            forwardToStorage(ex, correlation);
            return;
        }
        boolean xml = ex.getRequestHeaders().getFirst("Accept") != null &&
                ex.getRequestHeaders().getFirst("Accept").toLowerCase(Locale.ROOT).contains("application/xml");
        String type = xml ? "application/xml; charset=utf-8" : "application/json; charset=utf-8";
        if (path.equals("/api/stats") && ex.getRequestMethod().equals("GET")) {
            HttpUtil.send(ex, 200, xml ? store.statsXml(node) : store.stats(node), type); return;
        }
        if (path.equals("/api/orders") && ex.getRequestMethod().equals("GET")) {
            HttpUtil.send(ex, 200, store.all(xml), type); return;
        }
        if (path.startsWith("/api/orders/") && path.length() > 12) {
            String id = path.substring("/api/orders/".length());
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                HttpUtil.send(ex, 400, HttpUtil.error("invalid_order_id", id), "application/json"); return;
            }
            if (ex.getRequestMethod().equals("GET")) {
                String result = store.read(id, xml);
                if (result == null) HttpUtil.send(ex, 404, HttpUtil.error("not_found", id), "application/json");
                else HttpUtil.send(ex, 200, result, type);
                return;
            }
            if (ex.getRequestMethod().equals("PUT")) {
                String status = q.getOrDefault("status", "");
                if (!status.matches("[A-Z_]{1,32}")) {
                    HttpUtil.send(ex, 400, HttpUtil.error("bad_status", "Set ?status=READY"), "application/json"); return;
                }
                HttpUtil.send(ex, 200, store.put(id, status), "application/json; charset=utf-8"); return;
            }
        }
        HttpUtil.send(ex, 405, HttpUtil.error("unsupported_operation", ex.getRequestMethod()+" " +path), "application/json");
    }
    private void forwardToStorage(HttpExchange ex, String correlation) throws IOException {
        String pathAndQuery = ex.getRequestURI().toASCIIString();
        String separator = pathAndQuery.contains("?") ? "&" : "?";
        URI target = storageUrl.resolve(pathAndQuery + separator + "node=" + node);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(1600))
                .header("X-Correlation-Id",correlation);
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept != null) builder.header("Accept",accept);
        if (ex.getRequestMethod().equals("GET")) builder.GET();
        else if (ex.getRequestMethod().equals("PUT")) builder.PUT(HttpRequest.BodyPublishers.noBody());
        else { HttpUtil.send(ex,405,HttpUtil.error("bad_method",ex.getRequestMethod()),"application/json");return; }
        try {
            HttpResponse<byte[]> result = storageHttp.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
            String contentType = result.headers().firstValue("Content-Type").orElse("application/json");
            HttpUtil.send(ex,result.statusCode(),result.body(),contentType);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            HttpUtil.send(ex,503,HttpUtil.error("storage_interrupted",node),"application/json");
        } catch (IOException failure) {
            HttpUtil.send(ex,503,HttpUtil.error("storage_unavailable",node),"application/json");
        }
    }
    @Override public void close() { server.stop(0); }
}
