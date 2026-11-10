package md.lab3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.util.List;
import java.net.http.HttpClient;

class ProxyFailureTest {
    private static int port() throws Exception {
        try (ServerSocket s=new ServerSocket(0)) {return s.getLocalPort();}
    }
    @Test void singleBackend503FallsBackToHealthyPeer() throws Exception {
        int a=port(),b=port(),p=port();
        try (BackendServer first=new BackendServer(a,"dw-a",new WarehouseStore());
             BackendServer second=new BackendServer(b,"dw-b",new WarehouseStore());
             ProxyServer proxy=new ProxyServer(p,List.of("http://127.0.0.1:"+a,"http://127.0.0.1:"+b),500,2000,8,600,2,250)) {
            first.start();second.start();proxy.start();
            HttpClient http=HttpClient.newHttpClient();
            assertEquals(200,HttpProxyIntegrationTest.call(http,"http://127.0.0.1:"+a+"/admin/fault?mode=fail","POST",null).statusCode());
            for (int i=0;i<8;i++)
                assertEquals(200,HttpProxyIntegrationTest.call(http,"http://127.0.0.1:"+p+"/api/orders/ORD-2002?i="+i,"GET",null).statusCode());
            var metrics=HttpProxyIntegrationTest.call(http,"http://127.0.0.1:"+p+"/metrics","GET",null).body();
            assertTrue(metrics.contains("\"retries\":"),metrics);
            // Health checks may exclude the node before the breaker trips.
            assertTrue(metrics.contains("\"OPEN\"") || metrics.contains("\"healthy\":false"),metrics);
        }
    }
}
