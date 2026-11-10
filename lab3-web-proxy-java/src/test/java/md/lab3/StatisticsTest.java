package md.lab3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsTest {
    @Test void nearestRankPercentilesAndXmlWarehouse() throws Exception {
        LatencyMetrics metrics = new LatencyMetrics();
        for (long latency = 1; latency <= 100; latency++) metrics.recordMicros(latency * 1000);
        String snapshot = metrics.snapshot("[]", 0, 0);
        assertTrue(snapshot.contains("\"p50Ms\":50.000"), snapshot);
        assertTrue(snapshot.contains("\"p95Ms\":95.000"), snapshot);
        assertTrue(snapshot.contains("\"p99Ms\":99.000"), snapshot);
        WarehouseStore store = new WarehouseStore();
        assertTrue(store.read("ORD-2001", true).startsWith("<order>"));
        assertTrue(store.statsXml("dw-a").startsWith("<stats>"));
        assertTrue(store.put("ORD-2001", "READY").contains("READY"));
    }
}
