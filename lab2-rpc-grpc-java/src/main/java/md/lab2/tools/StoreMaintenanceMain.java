package md.lab2.tools;

import md.lab2.config.AppConfig;
import md.lab2.store.SqliteMessageStore;
import md.lab2.store.StatsSnapshot;

import java.nio.file.Path;

public final class StoreMaintenanceMain {
    private StoreMaintenanceMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: backup <file> | stats");
            return;
        }
        AppConfig config = AppConfig.fromSystemProperties();
        SqliteMessageStore store = new SqliteMessageStore(
                config.databasePath(),
                config.maxAttempts(),
                config.leaseMs(),
                config.retryBaseMs(),
                config.retryMaxMs());

        if ("backup".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                throw new IllegalArgumentException("backup requires a target file");
            }
            Path target = Path.of(args[1]);
            store.backupTo(target);
            System.out.println("Backup created: " + target.toAbsolutePath());
            return;
        }

        if ("stats".equalsIgnoreCase(args[0])) {
            StatsSnapshot stats = store.stats("", "");
            System.out.println(stats);
            return;
        }

        throw new IllegalArgumentException("Unknown command: " + args[0]);
    }
}
