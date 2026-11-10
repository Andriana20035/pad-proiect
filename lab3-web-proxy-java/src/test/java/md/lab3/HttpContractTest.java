package md.lab3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

class HttpContractTest {
    @Test void orderPathAndQueryContract() {
        Map<String,String> q=HttpUtil.query("status=READY&filter=some%20value");
        assertEquals("READY", q.get("status"));
        assertEquals("some value", q.get("filter"));
        WarehouseStore store = new WarehouseStore();
        assertTrue(store.read("ORD-2001",false).contains("\"orderId\""));
        assertTrue(store.read("ORD-2001",true).contains("<status>"));
        assertNull(store.read("MISSING",false));
        assertTrue(HttpUtil.error("bad", "x\"y").contains("x\\\"y"));
    }
}
