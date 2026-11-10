package md.lab3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded TTL cache. Entries remain briefly for stale-if-error after expiration. */
final class TtlCache {
    record Entry(byte[] body, String type, String backend, long expiresAt, long staleUntil) {}
    private final Map<String,Entry> values = new ConcurrentHashMap<>();
    private final long ttlMs, staleMs;
    private final int maxEntries;
    TtlCache(long ttlMs, long staleMs, int maxEntries) {
        this.ttlMs = ttlMs; this.staleMs = staleMs; this.maxEntries = maxEntries;
    }
    Entry fresh(String key) {
        Entry entry = values.get(key);
        return entry != null && System.currentTimeMillis() < entry.expiresAt ? entry : null;
    }
    Entry stale(String key) {
        Entry entry = values.get(key);
        return entry != null && System.currentTimeMillis() < entry.staleUntil ? entry : null;
    }
    synchronized void put(String key, byte[] body, String type, String backend) {
        if (values.size() >= maxEntries && !values.containsKey(key)) {
            String oldest = null;
            long expiry = Long.MAX_VALUE;
            for (var item : values.entrySet()) {
                if (item.getValue().staleUntil < expiry) {
                    oldest = item.getKey(); expiry = item.getValue().staleUntil;
                }
            }
            if (oldest != null) values.remove(oldest);
        }
        long now = System.currentTimeMillis();
        values.put(key, new Entry(body.clone(), type, backend, now+ttlMs, now+ttlMs+staleMs));
    }
    synchronized int invalidate(String prefix) {
        int before = values.size();
        values.keySet().removeIf(key -> key.startsWith(prefix));
        return before - values.size();
    }
    int size() { return values.size(); }
}
