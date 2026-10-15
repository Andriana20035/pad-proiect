package md.lab2.failure;

import md.lab2.store.SqliteMessageStore;
import md.lab2.store.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void invalidDatabasePathFailsClosedInsteadOfPretendingToPersist() throws Exception {
        Path parentThatIsAFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentThatIsAFile, "block directory creation");
        Path impossibleDb = parentThatIsAFile.resolve("broker.db");

        StoreException error = assertThrows(
                StoreException.class,
                () -> new SqliteMessageStore(impossibleDb, 3, 500, 100, 500));
        assertEquals(StoreException.Kind.STORAGE, error.kind());
    }
}
