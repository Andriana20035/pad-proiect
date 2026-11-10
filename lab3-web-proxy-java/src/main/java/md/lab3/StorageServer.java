package md.lab3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** Single synchronization service used by separate DW processes. Persists acknowledged writes. */
final class StorageServer implements AutoCloseable {
    private final HttpServer server;
    private final WarehouseStore store;

    StorageServer(int port, Path file) throws IOException {
        store = new WarehouseStore(file);
        server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("LAB3_BIND_HOST", "127.0.0.1"), port), 64);
        server.setExecutor(Executors.newFixedThreadPool(24, r -> { Thread t = new Thread(r,"warehouse-storage");t.setDaemon(true);return t; }));
        server.createContext("/", this::handle);
    }
    void start() { server.start(); }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String,String> q = HttpUtil.query(ex.getRequestURI().getRawQuery());
        String method = ex.getRequestMethod();
        if (path.equals("/health")) {
            HttpUtil.send(ex, 200,"{\"storage\":true}","application/json"); return;
        }
        String node = q.getOrDefault("node","storage");
        boolean xml = ex.getRequestHeaders().getFirst("Accept") != null &&
                ex.getRequestHeaders().getFirst("Accept").toLowerCase(Locale.ROOT).contains("application/xml");
        String type = xml ? "application/xml" : "application/json";
        if (path.equals("/api/stats") && method.equals("GET")) {
            HttpUtil.send(ex,200,xml ? store.statsXml(node) : store.stats(node),type);return;
        }
        if (path.equals("/api/orders") && method.equals("GET")) {
            HttpUtil.send(ex,200,store.all(xml),type);return;
        }
        if (path.startsWith("/api/orders/")) {
            String id = path.substring("/api/orders/".length());
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                HttpUtil.send(ex,400,HttpUtil.error("invalid_order_id",id),"application/json");return;
            }
            if (method.equals("GET")) {
                String result = store.read(id,xml);
                if (result == null) HttpUtil.send(ex,404,HttpUtil.error("not_found",id),"application/json");
                else HttpUtil.send(ex,200,result,type);
                return;
            }
            if (method.equals("PUT")) {
                String status = q.getOrDefault("status", "");
                if (!status.matches("[A-Z_]{1,32}")) {
                    HttpUtil.send(ex,400,HttpUtil.error("bad_status","Set ?status=READY"),"application/json");return;
                }
                HttpUtil.send(ex,200,store.put(id,status),"application/json");return;
            }
        }
        HttpUtil.send(ex,404,HttpUtil.error("not_found",path),"application/json");
    }
    @Override public void close() { server.stop(0); }
}
