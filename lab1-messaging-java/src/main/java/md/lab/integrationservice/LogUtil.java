package md.lab.integrationservice;

import md.lab.api.MessageEnvelope;

import java.time.Instant;

public final class LogUtil {
    private LogUtil() {}

    public static synchronized void log(String component, String action, MessageEnvelope message, String details) {
        String messageId = message == null ? "-" : message.id();
        String correlationId = message == null ? "-" : message.correlationId();
        System.out.printf("ts=%s component=%s action=%s messageId=%s correlationId=%s %s%n",
                Instant.now(), component, action, messageId, correlationId, details == null ? "" : details);
        if (message != null) {
            System.out.printf("  order=%s type=%s createdAt=%s schema=%s payload=%s%n",
                    message.key(), message.type(), message.timestamp(), message.schemaVersion(),
                    message.payload() == null ? "null" : message.payload().replace("\r", "\\r").replace("\n", "\\n"));
        }
    }
}
