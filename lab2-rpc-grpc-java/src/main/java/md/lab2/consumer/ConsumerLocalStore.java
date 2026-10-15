package md.lab2.consumer;

import md.lab2.rpc.v1.Envelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

public final class ConsumerLocalStore {
    private final Path databasePath;

    public ConsumerLocalStore(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        initialize();
    }

    public boolean applyOnce(Envelope message, String effectKey) {
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                if (alreadyProcessed(connection, message.getId())) {
                    commit(connection);
                    return false;
                }

                try (PreparedStatement effect = connection.prepareStatement("""
                        INSERT INTO effects(effect_key, effect_count, updated_at)
                        VALUES(?, 1, ?)
                        ON CONFLICT(effect_key) DO UPDATE SET
                            effect_count=effect_count+1,
                            updated_at=excluded.updated_at
                        """)) {
                    effect.setString(1, effectKey);
                    effect.setString(2, Instant.now().toString());
                    effect.executeUpdate();
                }

                try (PreparedStatement processed = connection.prepareStatement("""
                        INSERT INTO processed_messages(
                            message_id, correlation_id, msg_key, processed_at)
                        VALUES(?, ?, ?, ?)
                        """)) {
                    processed.setString(1, message.getId());
                    processed.setString(2, message.getCorrelationId());
                    processed.setString(3, message.getKey());
                    processed.setString(4, Instant.now().toString());
                    processed.executeUpdate();
                }
                commit(connection);
                return true;
            } catch (RuntimeException | SQLException e) {
                rollbackQuietly(connection);
                throw new IllegalStateException("Cannot apply local effect atomically", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open consumer local store", e);
        }
    }

    public int effectCount(String effectKey) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT effect_count FROM effects WHERE effect_key=?")) {
            statement.setString(1, effectKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read local effect", e);
        }
    }

    public int processedCount() {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM processed_messages")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count processed messages", e);
        }
    }

    private void initialize() {
        try {
            if (databasePath.getParent() != null) {
                Files.createDirectories(databasePath.getParent());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create local store directory", e);
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS processed_messages(
                        message_id TEXT PRIMARY KEY,
                        correlation_id TEXT NOT NULL,
                        msg_key TEXT NOT NULL,
                        processed_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS effects(
                        effect_key TEXT PRIMARY KEY,
                        effect_count INTEGER NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize local store", e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return connection;
    }

    private boolean alreadyProcessed(Connection connection, String messageId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM processed_messages WHERE message_id=?")) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void beginImmediate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
    }

    private void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    private void rollbackQuietly(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) {
        }
    }
}
