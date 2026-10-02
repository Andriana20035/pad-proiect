package md.lab.api;

import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public record MessageEnvelope(
        String id,
        String timestamp,
        String correlationId,
        String schemaVersion,
        String type,
        String key,
        String payload
) {
    public static MessageEnvelope orderCreated(String orderId, String payload) {
        return new MessageEnvelope(
                UUID.nameUUIDFromBytes(("ORDER_CREATED:" + orderId).getBytes(StandardCharsets.UTF_8)).toString(),
                Instant.now().toString(),
                UUID.nameUUIDFromBytes(("ORDER_FLOW:" + orderId).getBytes(StandardCharsets.UTF_8)).toString(),
                "1.0",
                "ORDER_CREATED",
                orderId,
                payload
        );
    }
}
