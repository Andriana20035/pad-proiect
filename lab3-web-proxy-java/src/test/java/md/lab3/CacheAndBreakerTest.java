package md.lab3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

class CacheAndBreakerTest {
    @Test void ttlExpirationAndBoundedStale() throws Exception {
        TtlCache cache = new TtlCache(80, 180, 2);
        cache.put("/api/orders/ORD-2001|accept=application/json", "ok".getBytes(StandardCharsets.UTF_8), "application/json", "dw-a");
        String key = "/api/orders/ORD-2001|accept=application/json";
        assertNotNull(cache.fresh(key));
        Thread.sleep(115);
        assertNull(cache.fresh(key));
        assertNotNull(cache.stale(key));
        assertEquals(1, cache.invalidate("/api/orders"));
        assertNull(cache.stale(key));
    }

    @Test void breakerOpensAndRecoversAfterProbe() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(2, 90);
        assertTrue(cb.acquire()); cb.failure();
        assertTrue(cb.acquire()); cb.failure();
        assertEquals("OPEN", cb.state());
        assertFalse(cb.acquire());
        Thread.sleep(130);
        assertTrue(cb.acquire());
        assertEquals("HALF_OPEN", cb.state());
        assertFalse(cb.acquire(), "only one half-open probe permitted");
        cb.success();
        assertEquals("CLOSED", cb.state());
        assertTrue(cb.acquire());
    }

    @Test void cacheEvictionIsBounded() {
        TtlCache cache = new TtlCache(1000, 1000, 2);
        cache.put("/api/a",new byte[]{1},"application/json","a");
        cache.put("/api/b",new byte[]{2},"application/json","a");
        cache.put("/api/c",new byte[]{3},"application/json","b");
        assertEquals(2,cache.size());
    }
}
