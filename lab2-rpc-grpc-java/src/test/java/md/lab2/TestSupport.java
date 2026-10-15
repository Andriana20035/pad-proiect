package md.lab2;

import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.rpc.v1.StatsReply;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class TestSupport {
    private TestSupport() {
    }

    public static AppConfig config(Path db, String node) {
        return new AppConfig(
                db,
                0,
                node,
                3,
                250,
                80,
                250,
                100,
                1000,
                List.of(new Endpoint("localhost", 1)));
    }

    public static void waitFor(Condition condition, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    public static boolean completed(StatsReply stats, long count) {
        return stats.getCompleted() >= count;
    }

    @FunctionalInterface
    public interface Condition {
        boolean evaluate() throws Exception;
    }
}
