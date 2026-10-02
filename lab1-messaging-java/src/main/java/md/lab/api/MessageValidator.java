package md.lab.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

/** Executable v1 contract, mirrored by contracts/order-message-v1.schema.json. */
public final class MessageValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MessageValidator() {}

    public static void validate(MessageEnvelope message) {
        if (message == null) throw new IllegalArgumentException("Message is required");
        try {
            if (!UUID.fromString(message.id()).toString().equalsIgnoreCase(message.id())
                    || !UUID.fromString(message.correlationId()).toString().equalsIgnoreCase(message.correlationId()))
                throw new IllegalArgumentException("Canonical UUID required");
            Instant.parse(message.timestamp());
            if (!"1.0".equals(message.schemaVersion()) || !"ORDER_CREATED".equals(message.type())) {
                throw new IllegalArgumentException("Unsupported schemaVersion or type");
            }
            if (message.key() == null || message.key().isBlank()) throw new IllegalArgumentException("Order ID is required");
            JsonNode payload = MAPPER.readTree(message.payload());
            if (payload == null || !payload.isObject() || payload.size() != 2
                    || !payload.path("product").isTextual() || payload.path("product").asText().isBlank()
                    || !payload.path("quantity").isIntegralNumber() || !payload.path("quantity").canConvertToInt()
                    || payload.path("quantity").intValue() <= 0) {
                throw new IllegalArgumentException("Payload requires product and positive integer quantity");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid message contract: " + e.getMessage(), e);
        }
    }

    public static boolean sameRequest(MessageEnvelope left, MessageEnvelope right) {
        try {
            return left.key().equals(right.key()) && left.id().equals(right.id())
                    && left.correlationId().equals(right.correlationId())
                    && left.type().equals(right.type()) && left.schemaVersion().equals(right.schemaVersion())
                    && MAPPER.readTree(left.payload()).equals(MAPPER.readTree(right.payload()));
        } catch (Exception e) {
            return false;
        }
    }
}
