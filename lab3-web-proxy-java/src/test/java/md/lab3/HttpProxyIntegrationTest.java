package md.lab3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

class HttpProxyIntegrationTest {
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    @Test void endToEndCacheXmlWriteAndInvalidation() throws Exception {
        int a=freePort(),b=freePort(),p=freePort();
        WarehouseStore store = new WarehouseStore();
        try (BackendServer first=new BackendServer(a,"dw-a",store);
             BackendServer second=new BackendServer(b,"dw-b",store);
             ProxyServer proxy=new ProxyServer(p,List.of("http://127.0.0.1:"+a,"http://127.0.0.1:"+b),2000,5000,8,600,2,300)) {
            first.start();second.start();proxy.start();
            HttpClient http=HttpClient.newHttpClient();
            String base="http://127.0.0.1:"+p;
            HttpResponse<String> firstRead=call(http,base+"/api/orders/ORD-2001?case=integration", "GET",null);
            assertEquals(200,firstRead.statusCode());
            assertEquals("MISS",firstRead.headers().firstValue("X-Cache").orElse(""));
            HttpResponse<String> secondRead=call(http,base+"/api/orders/ORD-2001?case=integration","GET",null);
            assertEquals("HIT",secondRead.headers().firstValue("X-Cache").orElse(""));
            assertTrue(call(http,base+"/api/orders/ORD-2001?case=xml","GET","application/xml").body().startsWith("<order>"));
            assertEquals(200,call(http,base+"/api/orders/ORD-2001?status=READY","PUT",null).statusCode());
            HttpResponse<String> afterPut=call(http,base+"/api/orders/ORD-2001?case=integration","GET",null);
            assertEquals("MISS",afterPut.headers().firstValue("X-Cache").orElse(""));
            assertTrue(afterPut.body().contains("READY"));
        }
    }
    static HttpResponse<String> call(HttpClient http,String uri,String method,String accept) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3));
        if (accept!=null) b.header("Accept",accept);
        b.method(method,method.equals("PUT") ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.noBody());
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
}
