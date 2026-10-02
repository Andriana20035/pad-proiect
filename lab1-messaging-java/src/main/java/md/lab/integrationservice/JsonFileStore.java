package md.lab.integrationservice;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Small lab snapshots: flush a temporary file, then atomically replace the previous snapshot. */
public final class JsonFileStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonFileStore() {}

    public static <T> T read(Path path, Class<T> type, T empty) {
        if (path == null || !Files.exists(path)) return empty;
        try {
            return MAPPER.readValue(Files.readAllBytes(path), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid storage; refusing to discard data: " + path, e);
        }
    }

    public static void write(Path path, Object value) {
        if (path == null) return; // Transient mode, used by isolated unit tests.
        Path absolute = path.toAbsolutePath();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try {
            Files.createDirectories(absolute.getParent());
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot commit storage: " + path, e);
        }
    }
}
