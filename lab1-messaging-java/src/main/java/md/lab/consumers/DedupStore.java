package md.lab.consumers;

import md.lab.api.MessageEnvelope;
import md.lab.api.MessageValidator;
import md.lab.integrationservice.JsonFileStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The local effect and its deduplication record are the SAME atomic durable entry. */
public class DedupStore {
    public record Snapshot(Map<String, MessageEnvelope> applied) {}
    private Map<String, MessageEnvelope> applied;
    private final Path storage;
    public DedupStore() { this(null); }
    public DedupStore(Path storage) {
        this.storage = storage;
        applied = new LinkedHashMap<>(JsonFileStore.read(storage, Snapshot.class,
                new Snapshot(Map.of())).applied());
    }
    public synchronized boolean apply(MessageEnvelope message) {
        MessageValidator.validate(message);
        if (storage == null) return commit(message);
        try {
            java.nio.file.Files.createDirectories(storage.toAbsolutePath().getParent());
            Path lock = storage.resolveSibling(storage.getFileName() + ".lock");
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lock,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 java.nio.channels.FileLock ignored = channel.lock()) {
                applied = new LinkedHashMap<>(JsonFileStore.read(storage, Snapshot.class, new Snapshot(Map.of())).applied());
                return commit(message);
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new java.io.UncheckedIOException(new java.io.IOException("Effect store busy", e));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
    private boolean commit(MessageEnvelope message) {
        MessageEnvelope previous = applied.get(message.key());
        if (previous != null) {
            if (!MessageValidator.sameRequest(previous, message))
                throw new IllegalArgumentException("Conflicting request ID");
            return false;
        }
        if (applied.values().stream().anyMatch(saved -> saved.id().equals(message.id())))
            throw new IllegalArgumentException("Conflicting message ID");
        Map<String, MessageEnvelope> next = new LinkedHashMap<>(applied);
        next.put(message.key(), message);
        JsonFileStore.write(storage, new Snapshot(next));
        applied = next;
        return true;
    }
    public synchronized int effectCount(String orderId) { return applied.containsKey(orderId) ? 1 : 0; }
    public synchronized int size() { return applied.size(); }
}
