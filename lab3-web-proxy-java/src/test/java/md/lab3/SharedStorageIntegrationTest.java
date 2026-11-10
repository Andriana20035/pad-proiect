package md.lab3;

import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedStorageIntegrationTest {
    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
    @Test void independentBackendsUseSamePersistentDataAndCorrelation() throws Exception {
        int storagePort=freePort(),a=freePort(),b=freePort(),p=freePort();
        Path file=Files.createTempDirectory("lab3-storage-test").resolve("data.properties");
        try (StorageServer storage=new StorageServer(storagePort,file);
             BackendServer first=new BackendServer(a,"dw-a",URI.create("http://127.0.0.1:"+storagePort));
             BackendServer second=new BackendServer(b,"dw-b",URI.create("http://127.0.0.1:"+storagePort));
             ProxyServer proxy=new ProxyServer(p,List.of("http://127.0.0.1:"+a,"http://127.0.0.1:"+b),2000,5000,8,700,2,300)) {
            storage.start(); first.start(); second.start(); proxy.start();
            HttpClient http=HttpClient.newHttpClient();
            String root="http://127.0.0.1:";
            HttpRequest put=HttpRequest.newBuilder(URI.create(root+p+"/api/orders/ORD-2001?status=READY"))
                    .timeout(Duration.ofSeconds(4)).header("X-Correlation-Id","lab3-sync-test")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> changed=http.send(put,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,changed.statusCode(),changed.body());
            assertEquals("lab3-sync-test",changed.headers().firstValue("X-Correlation-Id").orElse(""));
            HttpRequest readA=HttpRequest.newBuilder(URI.create(root+a+"/api/orders/ORD-2001")).build();
            HttpRequest readB=HttpRequest.newBuilder(URI.create(root+b+"/api/orders/ORD-2001")).build();
            assertEquals(http.send(readA,HttpResponse.BodyHandlers.ofString()).body(),
                         http.send(readB,HttpResponse.BodyHandlers.ofString()).body());
            assertTrue(http.send(readA,HttpResponse.BodyHandlers.ofString()).body().contains("READY"));
        }
        WarehouseStore reloaded=new WarehouseStore(file);
        assertTrue(reloaded.read("ORD-2001",false).contains("READY"));
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.getParent());
    }
}
