package md.lab3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe model. When a file is configured, updates are persisted before acknowledgement. */
final class WarehouseStore {
    private final ConcurrentHashMap<String,String> orders = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(1);
    private final Path file;

    WarehouseStore() {
        this.file = null;
        seed();
    }

    WarehouseStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (Files.exists(this.file)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(this.file)) { properties.load(input); }
            for (String key : properties.stringPropertyNames()) {
                if (key.equals("version")) version.set(Long.parseLong(properties.getProperty(key)));
                else if (key.startsWith("order.")) orders.put(key.substring(6), properties.getProperty(key));
            }
        } else {
            seed();
            persist(new TreeMap<>(orders), version.get());
        }
    }

    private void seed() {
        orders.put("ORD-2001", "CREATED");
        orders.put("ORD-2002", "PROCESSED");
        orders.put("ORD-2003", "PENDING");
    }

    String read(String id, boolean xml) {
        String status = orders.get(id);
        if (status == null) return null;
        if (xml) return "<order><id>" + id + "</id><status>" + status + "</status></order>";
        return "{\"orderId\":\"" + HttpUtil.jsonEscape(id) + "\",\"status\":\"" + status + "\"}";
    }

    String all(boolean xml) {
        Map<String,String> sorted = new TreeMap<>(orders);
        StringBuilder sb = new StringBuilder(xml ? "<orders>" : "[");
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first && !xml) sb.append(',');
            String id = entry.getKey(), status = entry.getValue();
            sb.append(xml ? "<order><id>" + id + "</id><status>" + status + "</status></order>"
                    : "{\"orderId\":\"" + HttpUtil.jsonEscape(id) + "\",\"status\":\"" + status + "\"}");
            first = false;
        }
        return sb.append(xml ? "</orders>" : "]").toString();
    }

    synchronized String put(String id, String status) throws IOException {
        long next = version.get() + 1;
        if (file != null) {
            Map<String,String> updated = new TreeMap<>(orders);
            updated.put(id, status);
            persist(updated, next);
        }
        orders.put(id, status);
        version.set(next);
        return read(id, false);
    }

    private void persist(Map<String,String> data, long newVersion) throws IOException {
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("version", Long.toString(newVersion));
        for (var entry : data.entrySet()) properties.setProperty("order." + entry.getKey(), entry.getValue());
        Path temp = Files.createTempFile(file.getParent(), "warehouse-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { properties.store(out,"Lab 3 shared warehouse state"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    String statsXml(String node) {
        return "<stats><node>" + HttpUtil.jsonEscape(node) + "</node><orders>" + orders.size()
                + "</orders><modelVersion>" + version.get() + "</modelVersion></stats>";
    }
    String stats(String node) {
        return "{\"node\":\"" + HttpUtil.jsonEscape(node) + "\",\"orders\":" + orders.size()
                + ",\"modelVersion\":" + version.get() + "}";
    }
}
