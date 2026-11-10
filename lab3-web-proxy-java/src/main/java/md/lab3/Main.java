package md.lab3;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Launch each service as its own JVM: storage, backend, backend, proxy. */
public final class Main {
    private static int env(String name, int fallback) {
        try { return Integer.parseInt(System.getenv().getOrDefault(name,String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Use: storage [port] | backend <port> <name> | proxy");
        switch (args[0]) {
            case "storage" -> {
                int port = args.length >= 2 ? Integer.parseInt(args[1]) : 9020;
                Path file = Path.of(System.getenv().getOrDefault("LAB3_DATA_FILE","data/warehouse.properties"));
                StorageServer storage = new StorageServer(port,file);
                storage.start();
                Runtime.getRuntime().addShutdownHook(new Thread(storage::close));
                System.out.println("Storage service on http://127.0.0.1:"+port+"; file="+file.toAbsolutePath());
            }
            case "backend" -> {
                int port = args.length >= 2 ? Integer.parseInt(args[1]) : 9011;
                String name = args.length >= 3 ? args[2] : "warehouse-" + port;
                String storage = System.getenv().getOrDefault("LAB3_STORAGE_URL","http://127.0.0.1:9020");
                BackendServer backend = new BackendServer(port,name,URI.create(storage));
                backend.start();
                Runtime.getRuntime().addShutdownHook(new Thread(backend::close));
                System.out.println("Backend " + name + " on http://127.0.0.1:"+port+"; storage="+storage);
            }
            case "proxy" -> {
                String configured = System.getenv().getOrDefault("LAB3_BACKENDS","http://127.0.0.1:9011,http://127.0.0.1:9012");
                List<String> addresses = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                int port = env("LAB3_PROXY_PORT",18080);
                ProxyServer proxy = new ProxyServer(port,addresses,
                        env("LAB3_CACHE_TTL_MS",2_000),env("LAB3_STALE_IF_ERROR_MS",15_000),
                        env("LAB3_MAX_CONCURRENT",12),env("LAB3_BACKEND_TIMEOUT_MS",700),
                        env("LAB3_CB_THRESHOLD",3),env("LAB3_CB_OPEN_MS",1_500));
                proxy.start();
                Runtime.getRuntime().addShutdownHook(new Thread(proxy::close));
                System.out.println("Proxy on http://127.0.0.1:"+port);
            }
            default -> throw new IllegalArgumentException("Use: storage [port] | backend <port> <name> | proxy");
        }
        new CountDownLatch(1).await();
    }
}
