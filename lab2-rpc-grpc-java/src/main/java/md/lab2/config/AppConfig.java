package md.lab2.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public record AppConfig(
        Path databasePath,
        int port,
        String nodeId,
        int maxAttempts,
        long leaseMs,
        long retryBaseMs,
        long retryMaxMs,
        long crossNodeRefreshMs,
        int unaryDeadlineMs,
        List<Endpoint> endpoints) {

    public static AppConfig fromSystemProperties() {
        return new AppConfig(
                Path.of(value("lab2.db", "LAB2_DB", "data/lab2-broker.db")),
                integer("lab2.port", "LAB2_PORT", 50051),
                value("lab2.node", "LAB2_NODE", "broker-a"),
                integer("lab2.maxAttempts", "LAB2_MAX_ATTEMPTS", 3),
                longValue("lab2.leaseMs", "LAB2_LEASE_MS", 1500),
                longValue("lab2.retryBaseMs", "LAB2_RETRY_BASE_MS", 400),
                longValue("lab2.retryMaxMs", "LAB2_RETRY_MAX_MS", 4000),
                longValue(
                        "lab2.crossNodeRefreshMs",
                        "LAB2_CROSS_NODE_REFRESH_MS",
                        1000),
                integer("lab2.deadlineMs", "LAB2_DEADLINE_MS", 2000),
                parseEndpoints(value(
                        "lab2.endpoints",
                        "LAB2_ENDPOINTS",
                        "localhost:50051,localhost:50052")));
    }

    public AppConfig withPortAndNode(int newPort, String newNodeId) {
        return new AppConfig(
                databasePath,
                newPort,
                newNodeId,
                maxAttempts,
                leaseMs,
                retryBaseMs,
                retryMaxMs,
                crossNodeRefreshMs,
                unaryDeadlineMs,
                endpoints);
    }

    public AppConfig withDatabase(Path newDatabasePath) {
        return new AppConfig(
                newDatabasePath,
                port,
                nodeId,
                maxAttempts,
                leaseMs,
                retryBaseMs,
                retryMaxMs,
                crossNodeRefreshMs,
                unaryDeadlineMs,
                endpoints);
    }

    public AppConfig withEndpoints(List<Endpoint> newEndpoints) {
        return new AppConfig(
                databasePath,
                port,
                nodeId,
                maxAttempts,
                leaseMs,
                retryBaseMs,
                retryMaxMs,
                crossNodeRefreshMs,
                unaryDeadlineMs,
                List.copyOf(newEndpoints));
    }

    private static String value(String property, String env, String fallback) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        String envValue = System.getenv(env);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return fallback;
    }

    private static int integer(String property, String env, int fallback) {
        return Integer.parseInt(value(property, env, Integer.toString(fallback)));
    }

    private static long longValue(String property, String env, long fallback) {
        return Long.parseLong(value(property, env, Long.toString(fallback)));
    }

    private static List<Endpoint> parseEndpoints(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Endpoint::parse)
                .toList();
    }
}
