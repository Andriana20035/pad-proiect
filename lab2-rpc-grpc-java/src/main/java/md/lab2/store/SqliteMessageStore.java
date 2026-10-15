package md.lab2.store;

import com.google.protobuf.ByteString;
import md.lab2.rpc.v1.AckStage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteMessageStore {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_IN_FLIGHT = "IN_FLIGHT";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_DLQ = "DLQ";

    private final Path databasePath;
    private final int maxAttempts;
    private final long leaseMs;
    private final BackoffPolicy backoffPolicy;

    public SqliteMessageStore(
            Path databasePath,
            int maxAttempts,
            long leaseMs,
            long retryBaseMs,
            long retryMaxMs) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.maxAttempts = maxAttempts;
        this.leaseMs = leaseMs;
        this.backoffPolicy = new BackoffPolicy(retryBaseMs, retryMaxMs);
        initialize();
    }

    public Path databasePath() {
        return databasePath;
    }

    public boolean createTopic(String topic) {
        requireNonBlank(topic, "topic");
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT OR IGNORE INTO topics(name, created_at) VALUES(?, ?)")) {
            statement.setString(1, topic);
            statement.setString(2, Instant.now().toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw storage("Cannot create topic " + topic, e);
        }
    }

    public boolean topicExists(String topic) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM topics WHERE name=?")) {
            statement.setString(1, topic);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw storage("Cannot read topic " + topic, e);
        }
    }

    public void activateSubscription(String consumerId, String topic) {
        requireTopic(topic);
        requireNonBlank(consumerId, "consumerId");
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO subscriptions(consumer_id, topic, active, created_at, updated_at)
                VALUES(?, ?, 1, ?, ?)
                ON CONFLICT(consumer_id, topic) DO UPDATE SET
                    active=1,
                    updated_at=excluded.updated_at
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, consumerId);
            statement.setString(2, topic);
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw storage("Cannot activate subscription", e);
        }
    }

    public void deactivateSubscription(String consumerId, String topic) {
        requireNonBlank(consumerId, "consumerId");
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE subscriptions SET active=0, updated_at=? " +
                             "WHERE consumer_id=? AND topic=?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, consumerId);
            statement.setString(3, topic);
            int changed = statement.executeUpdate();
            if (changed == 0) {
                throw new StoreException(
                        StoreException.Kind.NOT_FOUND,
                        "Subscription does not exist: " + consumerId + " -> " + topic);
            }
        } catch (StoreException e) {
            throw e;
        } catch (SQLException e) {
            throw storage("Cannot deactivate subscription", e);
        }
    }

    public boolean isActiveSubscription(String consumerId, String topic) {
        try (Connection connection = open()) {
            return isActiveSubscription(connection, consumerId, topic);
        } catch (SQLException e) {
            throw storage("Cannot read subscription", e);
        }
    }

    public PublishOutcome publish(String topic, Envelope message) {
        requireTopic(topic);
        validateEnvelope(message);
        try (Connection connection = open()) {
            beginImmediate(connection);
            try {
                Optional<Envelope> existing = findMessage(connection, message.getId());
                if (existing.isPresent()) {
                    if (!sameEnvelope(existing.get(), message) || !sameMessageTopic(
                            connection, message.getId(), topic)) {
                        throw new StoreException(
                                StoreException.Kind.CONFLICT,
                                "messageId already exists with different content or topic");
                    }
                    int routed = countDeliveriesForMessage(connection, message.getId());
                    commit(connection);
                    return new PublishOutcome(true, routed);
                }

                insertMessage(connection, topic, message);
                int routed = createDeliveriesForActiveSubscribers(
                        connection, topic, message.getId());
                commit(connection);
                return new PublishOutcome(false, routed);
            } catch (RuntimeException | SQLException e) {
                rollbackQuietly(connection);
                if (e instanceof StoreException storeException) {
                    throw storeException;
                }
                throw storage("Cannot persist published message", e);
            }
        } catch (SQLException e) {
            throw storage("Cannot open store for publish", e);
        }
    }

    public Optional<DeliveryRecord> claimNextDelivery(
            String consumerId,
            String topic) {
        try (Connection connection = open()) {
            beginImmediate(connection);
            // SQLite writer contention must not consume the new delivery's lease.
            long now = System.currentTimeMillis();
            try {
                requireActiveSubscription(connection, consumerId, topic);
                recoverExpiredDeliveries(connection, consumerId, topic, now);

                if (hasInFlight(connection, consumerId, topic)) {
                    commit(connection);
                    return Optional.empty();
                }

                Long deliveryId = selectDueDeliveryId(
                        connection, consumerId, topic, now);
                if (deliveryId == null) {
                    commit(connection);
                    return Optional.empty();
                }

                long leaseDeadline = now + leaseMs;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE deliveries SET status=?, attempt=attempt+1, " +
                                "lease_until_ms=?, updated_at=? WHERE id=? AND status=?")) {
                    update.setString(1, STATUS_IN_FLIGHT);
                    update.setLong(2, leaseDeadline);
                    update.setString(3, Instant.now().toString());
                    update.setLong(4, deliveryId);
                    update.setString(5, STATUS_PENDING);
                    if (update.executeUpdate() != 1) {
                        rollbackQuietly(connection);
                        return Optional.empty();
                    }
                }

                DeliveryRecord record = readDelivery(connection, deliveryId);
                commit(connection);
                return Optional.of(record);
            } catch (RuntimeException | SQLException e) {
                rollbackQuietly(connection);
                if (e instanceof StoreException storeException) {
                    throw storeException;
                }
                throw storage("Cannot claim delivery", e);
            }
        } catch (SQLException e) {
            throw storage("Cannot open store for delivery", e);
        }
    }

    public long nextWakeDelayMs(
            String consumerId,
            String topic,
            long idleFallbackMs) {
        long fallback = Math.max(1L, idleFallbackMs);
        long now = System.currentTimeMillis();
        try (Connection connection = open()) {
            requireActiveSubscription(connection, consumerId, topic);

            Long leaseDeadline = minimumTime(
                    connection,
                    consumerId,
                    topic,
                    STATUS_IN_FLIGHT,
                    "lease_until_ms");
            if (leaseDeadline != null) {
                return boundedDelay(leaseDeadline - now, fallback);
            }

            Long retryAt = minimumTime(
                    connection,
                    consumerId,
                    topic,
                    STATUS_PENDING,
                    "next_attempt_ms");
            if (retryAt != null) {
                return boundedDelay(retryAt - now, fallback);
            }
            return fallback;
        } catch (SQLException e) {
            throw storage("Cannot calculate next subscription wake-up", e);
        }
    }

    public AckOutcome ack(long deliveryId, String consumerId, AckStage stage) {
        if (stage == null || stage == AckStage.ACK_STAGE_UNSPECIFIED
                || stage == AckStage.UNRECOGNIZED) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "A concrete ACK stage is required");
        }
        try (Connection connection = open()) {
            beginImmediate(connection);
            long now = System.currentTimeMillis();
            try {
                DeliveryMeta meta = readDeliveryMeta(connection, deliveryId);
                validateDeliveryOwner(meta, consumerId);
                requireActiveSubscription(connection, consumerId, meta.topic());

                AckStage currentStage = parseAckStage(meta.ackStage());
                if (STATUS_COMPLETED.equals(meta.status())) {
                    commit(connection);
                    return new AckOutcome(true, currentStage);
                }
                if (!STATUS_IN_FLIGHT.equals(meta.status())) {
                    throw new StoreException(
                            StoreException.Kind.PRECONDITION,
                            "Delivery is not in flight: status=" + meta.status());
                }

                AckStage storedStage = stage.getNumber() > currentStage.getNumber()
                        ? stage
                        : currentStage;
                boolean completed = storedStage == AckStage.PERSISTED;
                String sql = completed
                        ? "UPDATE deliveries SET ack_stage=?, status=?, lease_until_ms=0, " +
                        "updated_at=? WHERE id=?"
                        : "UPDATE deliveries SET ack_stage=?, lease_until_ms=?, updated_at=? " +
                        "WHERE id=?";
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    update.setString(1, storedStage.name());
                    if (completed) {
                        update.setString(2, STATUS_COMPLETED);
                        update.setString(3, Instant.now().toString());
                        update.setLong(4, deliveryId);
                    } else {
                        update.setLong(2, now + leaseMs);
                        update.setString(3, Instant.now().toString());
                        update.setLong(4, deliveryId);
                    }
                    update.executeUpdate();
                }
                commit(connection);
                return new AckOutcome(completed, storedStage);
            } catch (RuntimeException | SQLException e) {
                rollbackQuietly(connection);
                if (e instanceof StoreException storeException) {
                    throw storeException;
                }
                throw storage("Cannot ACK delivery", e);
            }
        } catch (SQLException e) {
            throw storage("Cannot open store for ACK", e);
        }
    }

    public NackOutcome nack(
            long deliveryId,
            String consumerId,
            String reason,
            boolean retryable) {
        try (Connection connection = open()) {
            beginImmediate(connection);
            long now = System.currentTimeMillis();
            try {
                DeliveryMeta meta = readDeliveryMeta(connection, deliveryId);
                validateDeliveryOwner(meta, consumerId);
                requireActiveSubscription(connection, consumerId, meta.topic());
                if (!STATUS_IN_FLIGHT.equals(meta.status())) {
                    throw new StoreException(
                            StoreException.Kind.PRECONDITION,
                            "Only an in-flight delivery can be NACKed");
                }

                if (!retryable || meta.attempt() >= maxAttempts) {
                    moveToDlq(connection, meta, reason);
                    commit(connection);
                    return new NackOutcome(true, 0, meta.attempt());
                }

                long retryAfter = backoffPolicy.delayMillis(meta.attempt());
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE deliveries SET status=?, lease_until_ms=0, " +
                                "next_attempt_ms=?, last_error=?, updated_at=? WHERE id=?")) {
                    update.setString(1, STATUS_PENDING);
                    update.setLong(2, now + retryAfter);
                    update.setString(3, reason);
                    update.setString(4, Instant.now().toString());
                    update.setLong(5, deliveryId);
                    update.executeUpdate();
                }
                commit(connection);
                return new NackOutcome(false, retryAfter, meta.attempt());
            } catch (RuntimeException | SQLException e) {
                rollbackQuietly(connection);
                if (e instanceof StoreException storeException) {
                    throw storeException;
                }
                throw storage("Cannot NACK delivery", e);
            }
        } catch (SQLException e) {
            throw storage("Cannot open store for NACK", e);
        }
    }

    public StatsSnapshot stats(String topic, String consumerId) {
        try (Connection connection = open()) {
            long messages = countMessages(connection, topic);
            long pending = countDeliveries(connection, STATUS_PENDING, topic, consumerId);
            long inFlight = countDeliveries(
                    connection, STATUS_IN_FLIGHT, topic, consumerId);
            long completed = countDeliveries(
                    connection, STATUS_COMPLETED, topic, consumerId);
            long dlq = countDlq(connection, topic, consumerId);
            long subscriptions = countActiveSubscriptions(connection, topic, consumerId);
            return new StatsSnapshot(
                    messages, pending, inFlight, completed, dlq, subscriptions);
        } catch (SQLException e) {
            throw storage("Cannot read stats", e);
        }
    }

    public List<DlqRecord> listDlq(String topic, String consumerId) {
        StringBuilder sql = new StringBuilder(
                "SELECT delivery_id, message_id, consumer_id, topic, attempts, reason, moved_at " +
                        "FROM dlq WHERE 1=1");
        List<String> params = new ArrayList<>();
        addFilter(sql, params, "topic", topic);
        addFilter(sql, params, "consumer_id", consumerId);
        sql.append(" ORDER BY id");

        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindStrings(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                List<DlqRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(new DlqRecord(
                            result.getLong("delivery_id"),
                            result.getString("message_id"),
                            result.getString("consumer_id"),
                            result.getString("topic"),
                            result.getInt("attempts"),
                            result.getString("reason"),
                            result.getString("moved_at")));
                }
                return records;
            }
        } catch (SQLException e) {
            throw storage("Cannot read DLQ", e);
        }
    }

    public int countMessagesWithId(String messageId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM messages WHERE id=?")) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw storage("Cannot count messages", e);
        }
    }

    public int countDeliveriesForMessage(String messageId) {
        try (Connection connection = open()) {
            return countDeliveriesForMessage(connection, messageId);
        } catch (SQLException e) {
            throw storage("Cannot count deliveries", e);
        }
    }

    public int deliveryAttemptForMessageAndConsumer(
            String messageId,
            String consumerId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT attempt FROM deliveries WHERE message_id=? AND consumer_id=?")) {
            statement.setString(1, messageId);
            statement.setString(2, consumerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw storage("Cannot read delivery attempt", e);
        }
    }

    public void backupTo(Path backupPath) {
        Path absoluteBackup = backupPath.toAbsolutePath().normalize();
        try {
            if (databasePath.equals(absoluteBackup)
                    || (Files.exists(absoluteBackup)
                    && Files.isSameFile(databasePath, absoluteBackup))) {
                throw new StoreException(StoreException.Kind.PRECONDITION,
                        "Backup target must differ from the live database");
            }
            Path parent = absoluteBackup.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(absoluteBackup);
        } catch (IOException e) {
            throw new StoreException(
                    StoreException.Kind.STORAGE,
                    "Cannot prepare backup path",
                    e);
        }

        String escapedPath = absoluteBackup.toString().replace("'", "''");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(FULL)");
            statement.execute("VACUUM INTO '" + escapedPath + "'");
        } catch (SQLException e) {
            throw storage("Cannot create SQLite backup", e);
        }
    }

    private void initialize() {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new StoreException(
                    StoreException.Kind.STORAGE,
                    "Cannot create database directory",
                    e);
        }

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS topics(
                        name TEXT PRIMARY KEY,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS subscriptions(
                        consumer_id TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        active INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(consumer_id, topic),
                        FOREIGN KEY(topic) REFERENCES topics(name)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS messages(
                        id TEXT PRIMARY KEY,
                        topic TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        correlation_id TEXT NOT NULL,
                        schema_version TEXT NOT NULL,
                        type TEXT NOT NULL,
                        msg_key TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        accepted_at TEXT NOT NULL,
                        FOREIGN KEY(topic) REFERENCES topics(name)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS deliveries(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message_id TEXT NOT NULL,
                        consumer_id TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempt INTEGER NOT NULL DEFAULT 0,
                        ack_stage TEXT NOT NULL DEFAULT 'ACK_STAGE_UNSPECIFIED',
                        lease_until_ms INTEGER NOT NULL DEFAULT 0,
                        next_attempt_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE(message_id, consumer_id),
                        FOREIGN KEY(message_id) REFERENCES messages(id),
                        FOREIGN KEY(consumer_id, topic)
                            REFERENCES subscriptions(consumer_id, topic)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dlq(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        delivery_id INTEGER NOT NULL UNIQUE,
                        message_id TEXT NOT NULL,
                        consumer_id TEXT NOT NULL,
                        topic TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        moved_at TEXT NOT NULL
                    )
                    """);
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_delivery_ready " +
                            "ON deliveries(consumer_id, topic, status, next_attempt_ms, id)");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_delivery_lease " +
                            "ON deliveries(status, lease_until_ms)");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_message_topic " +
                            "ON messages(topic, accepted_at)");
        } catch (SQLException e) {
            throw storage("Cannot initialize SQLite schema", e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
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

    private void requireTopic(String topic) {
        requireNonBlank(topic, "topic");
        if (!topicExists(topic)) {
            throw new StoreException(
                    StoreException.Kind.NOT_FOUND,
                    "Unknown topic: " + topic);
        }
    }

    private void validateEnvelope(Envelope message) {
        if (message == null) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "Message is required");
        }
        requireNonBlank(message.getId(), "message.id");
        requireNonBlank(message.getTimestamp(), "message.timestamp");
        requireNonBlank(message.getCorrelationId(), "message.correlationId");
        requireNonBlank(message.getSchemaVersion(), "message.schemaVersion");
        requireNonBlank(message.getType(), "message.type");
        requireNonBlank(message.getKey(), "message.key");
        if (!"1.0".equals(message.getSchemaVersion())) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "Only schemaVersion 1.0 is accepted");
        }
        try {
            Instant.parse(message.getTimestamp());
        } catch (RuntimeException e) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "message.timestamp must be ISO-8601 UTC");
        }
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    field + " is required");
        }
    }

    private Optional<Envelope> findMessage(Connection connection, String messageId)
            throws SQLException {
        String sql = "SELECT id, timestamp, correlation_id, schema_version, type, " +
                "msg_key, payload FROM messages WHERE id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(envelopeFrom(result));
            }
        }
    }

    private boolean sameMessageTopic(
            Connection connection,
            String messageId,
            String topic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT topic FROM messages WHERE id=?")) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && topic.equals(result.getString(1));
            }
        }
    }

    private boolean sameEnvelope(Envelope left, Envelope right) {
        return left.getId().equals(right.getId())
                && left.getCorrelationId().equals(right.getCorrelationId())
                && left.getSchemaVersion().equals(right.getSchemaVersion())
                && left.getType().equals(right.getType())
                && left.getKey().equals(right.getKey())
                && left.getPayload().equals(right.getPayload());
    }

    private void insertMessage(
            Connection connection,
            String topic,
            Envelope message) throws SQLException {
        String sql = """
                INSERT INTO messages(
                    id, topic, timestamp, correlation_id, schema_version,
                    type, msg_key, payload, accepted_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, message.getId());
            statement.setString(2, topic);
            statement.setString(3, message.getTimestamp());
            statement.setString(4, message.getCorrelationId());
            statement.setString(5, message.getSchemaVersion());
            statement.setString(6, message.getType());
            statement.setString(7, message.getKey());
            statement.setBytes(8, message.getPayload().toByteArray());
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private int createDeliveriesForActiveSubscribers(
            Connection connection,
            String topic,
            String messageId) throws SQLException {
        List<String> consumers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT consumer_id FROM subscriptions " +
                        "WHERE topic=? AND active=1 ORDER BY consumer_id")) {
            statement.setString(1, topic);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    consumers.add(result.getString(1));
                }
            }
        }

        String now = Instant.now().toString();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO deliveries(
                    message_id, consumer_id, topic, status, attempt,
                    ack_stage, lease_until_ms, next_attempt_ms,
                    created_at, updated_at)
                VALUES(?, ?, ?, ?, 0, ?, 0, 0, ?, ?)
                """)) {
            for (String consumer : consumers) {
                insert.setString(1, messageId);
                insert.setString(2, consumer);
                insert.setString(3, topic);
                insert.setString(4, STATUS_PENDING);
                insert.setString(5, AckStage.ACK_STAGE_UNSPECIFIED.name());
                insert.setString(6, now);
                insert.setString(7, now);
                insert.addBatch();
            }
            if (!consumers.isEmpty()) {
                insert.executeBatch();
            }
        }
        return consumers.size();
    }

    private int countDeliveriesForMessage(
            Connection connection,
            String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM deliveries WHERE message_id=?")) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void recoverExpiredDeliveries(
            Connection connection,
            String consumerId,
            String topic,
            long now) throws SQLException {
        String sql = """
                SELECT id, message_id, consumer_id, topic, status, attempt,
                       ack_stage, lease_until_ms, next_attempt_ms
                FROM deliveries
                WHERE consumer_id=? AND topic=? AND status=? AND lease_until_ms<=?
                ORDER BY id
                """;
        List<DeliveryMeta> expired = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, consumerId);
            statement.setString(2, topic);
            statement.setString(3, STATUS_IN_FLIGHT);
            statement.setLong(4, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    expired.add(deliveryMetaFrom(result));
                }
            }
        }

        for (DeliveryMeta meta : expired) {
            if (meta.attempt() >= maxAttempts) {
                moveToDlq(connection, meta, "ACK lease expired");
            } else {
                long retryAt = now + backoffPolicy.delayMillis(meta.attempt());
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE deliveries SET status=?, lease_until_ms=0, " +
                                "next_attempt_ms=?, last_error=?, updated_at=? WHERE id=?")) {
                    update.setString(1, STATUS_PENDING);
                    update.setLong(2, retryAt);
                    update.setString(3, "ACK lease expired");
                    update.setString(4, Instant.now().toString());
                    update.setLong(5, meta.id());
                    update.executeUpdate();
                }
            }
        }
    }

    private boolean hasInFlight(
            Connection connection,
            String consumerId,
            String topic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM deliveries WHERE consumer_id=? AND topic=? " +
                        "AND status=?")) {
            statement.setString(1, consumerId);
            statement.setString(2, topic);
            statement.setString(3, STATUS_IN_FLIGHT);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) > 0;
            }
        }
    }

    private Long selectDueDeliveryId(
            Connection connection,
            String consumerId,
            String topic,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id
                FROM deliveries
                WHERE consumer_id=? AND topic=? AND status=? AND next_attempt_ms<=?
                ORDER BY id
                LIMIT 1
                """)) {
            statement.setString(1, consumerId);
            statement.setString(2, topic);
            statement.setString(3, STATUS_PENDING);
            statement.setLong(4, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private Long minimumTime(
            Connection connection,
            String consumerId,
            String topic,
            String status,
            String column) throws SQLException {
        String sql = "SELECT MIN(" + column + ") FROM deliveries "
                + "WHERE consumer_id=? AND topic=? AND status=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, consumerId);
            statement.setString(2, topic);
            statement.setString(3, status);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long value = result.getLong(1);
                return result.wasNull() ? null : value;
            }
        }
    }

    private long boundedDelay(long delayMs, long fallbackMs) {
        if (delayMs <= 0L) {
            return 1L;
        }
        return Math.min(delayMs, fallbackMs);
    }

    private DeliveryRecord readDelivery(Connection connection, long deliveryId)
            throws SQLException {
        String sql = """
                SELECT d.id, d.topic, d.consumer_id, d.attempt, d.lease_until_ms,
                       m.id, m.timestamp, m.correlation_id, m.schema_version,
                       m.type, m.msg_key, m.payload
                FROM deliveries d
                JOIN messages m ON m.id=d.message_id
                WHERE d.id=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deliveryId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StoreException(
                            StoreException.Kind.NOT_FOUND,
                            "Delivery not found: " + deliveryId);
                }
                Envelope envelope = Envelope.newBuilder()
                        .setId(result.getString(6))
                        .setTimestamp(result.getString(7))
                        .setCorrelationId(result.getString(8))
                        .setSchemaVersion(result.getString(9))
                        .setType(result.getString(10))
                        .setKey(result.getString(11))
                        .setPayload(ByteString.copyFrom(result.getBytes(12)))
                        .build();
                return new DeliveryRecord(
                        result.getLong(1),
                        result.getString(2),
                        result.getString(3),
                        envelope,
                        result.getInt(4),
                        result.getLong(5));
            }
        }
    }

    private DeliveryMeta readDeliveryMeta(Connection connection, long deliveryId)
            throws SQLException {
        String sql = """
                SELECT id, message_id, consumer_id, topic, status, attempt,
                       ack_stage, lease_until_ms, next_attempt_ms
                FROM deliveries WHERE id=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deliveryId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StoreException(
                            StoreException.Kind.NOT_FOUND,
                            "Delivery not found: " + deliveryId);
                }
                return deliveryMetaFrom(result);
            }
        }
    }

    private DeliveryMeta deliveryMetaFrom(ResultSet result) throws SQLException {
        return new DeliveryMeta(
                result.getLong("id"),
                result.getString("message_id"),
                result.getString("consumer_id"),
                result.getString("topic"),
                result.getString("status"),
                result.getInt("attempt"),
                result.getString("ack_stage"),
                result.getLong("lease_until_ms"),
                result.getLong("next_attempt_ms"));
    }

    private Envelope envelopeFrom(ResultSet result) throws SQLException {
        return Envelope.newBuilder()
                .setId(result.getString("id"))
                .setTimestamp(result.getString("timestamp"))
                .setCorrelationId(result.getString("correlation_id"))
                .setSchemaVersion(result.getString("schema_version"))
                .setType(result.getString("type"))
                .setKey(result.getString("msg_key"))
                .setPayload(ByteString.copyFrom(result.getBytes("payload")))
                .build();
    }

    private void validateDeliveryOwner(DeliveryMeta meta, String consumerId) {
        if (!meta.consumerId().equals(consumerId)) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "Delivery belongs to another consumer");
        }
    }

    private AckStage parseAckStage(String value) {
        try {
            return AckStage.valueOf(value);
        } catch (RuntimeException ignored) {
            return AckStage.ACK_STAGE_UNSPECIFIED;
        }
    }

    private void moveToDlq(
            Connection connection,
            DeliveryMeta meta,
            String reason) throws SQLException {
        String movedAt = Instant.now().toString();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO dlq(
                    delivery_id, message_id, consumer_id, topic,
                    attempts, reason, moved_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setLong(1, meta.id());
            insert.setString(2, meta.messageId());
            insert.setString(3, meta.consumerId());
            insert.setString(4, meta.topic());
            insert.setInt(5, meta.attempt());
            insert.setString(6, reason == null ? "unspecified" : reason);
            insert.setString(7, movedAt);
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE deliveries SET status=?, lease_until_ms=0, last_error=?, " +
                        "updated_at=? WHERE id=?")) {
            update.setString(1, STATUS_DLQ);
            update.setString(2, reason);
            update.setString(3, movedAt);
            update.setLong(4, meta.id());
            update.executeUpdate();
        }
    }

    private boolean isActiveSubscription(
            Connection connection,
            String consumerId,
            String topic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active FROM subscriptions WHERE consumer_id=? AND topic=?")) {
            statement.setString(1, consumerId);
            statement.setString(2, topic);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void requireActiveSubscription(
            Connection connection,
            String consumerId,
            String topic) throws SQLException {
        if (!isActiveSubscription(connection, consumerId, topic)) {
            throw new StoreException(
                    StoreException.Kind.PRECONDITION,
                    "Consumer is not actively subscribed to topic");
        }
    }

    private long countMessages(Connection connection, String topic) throws SQLException {
        if (topic == null || topic.isBlank()) {
            return scalar(connection, "SELECT COUNT(*) FROM messages", List.of());
        }
        return scalar(
                connection,
                "SELECT COUNT(*) FROM messages WHERE topic=?",
                List.of(topic));
    }

    private long countDeliveries(
            Connection connection,
            String status,
            String topic,
            String consumerId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM deliveries WHERE status=?");
        List<String> params = new ArrayList<>();
        params.add(status);
        addFilter(sql, params, "topic", topic);
        addFilter(sql, params, "consumer_id", consumerId);
        return scalar(connection, sql.toString(), params);
    }

    private long countDlq(
            Connection connection,
            String topic,
            String consumerId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dlq WHERE 1=1");
        List<String> params = new ArrayList<>();
        addFilter(sql, params, "topic", topic);
        addFilter(sql, params, "consumer_id", consumerId);
        return scalar(connection, sql.toString(), params);
    }

    private long countActiveSubscriptions(
            Connection connection,
            String topic,
            String consumerId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM subscriptions WHERE active=1");
        List<String> params = new ArrayList<>();
        addFilter(sql, params, "topic", topic);
        addFilter(sql, params, "consumer_id", consumerId);
        return scalar(connection, sql.toString(), params);
    }

    private long scalar(Connection connection, String sql, List<String> params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindStrings(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private void addFilter(
            StringBuilder sql,
            List<String> params,
            String column,
            String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append("=?");
            params.add(value);
        }
    }

    private void bindStrings(PreparedStatement statement, List<String> params)
            throws SQLException {
        for (int index = 0; index < params.size(); index++) {
            statement.setString(index + 1, params.get(index));
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

    private StoreException storage(String message, Throwable cause) {
        return new StoreException(StoreException.Kind.STORAGE, message, cause);
    }

    private record DeliveryMeta(
            long id,
            String messageId,
            String consumerId,
            String topic,
            String status,
            int attempt,
            String ackStage,
            long leaseUntilMs,
            long nextAttemptMs) {
    }
}
