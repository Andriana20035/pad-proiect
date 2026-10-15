package md.lab2.logging;

import md.lab2.rpc.v1.Envelope;

import java.time.Instant;

public final class CorrelatedLog {
    private CorrelatedLog() {
    }

    public static synchronized void event(
            String component,
            String action,
            Envelope message,
            String details) {
        String messageId = message == null ? "-" : message.getId();
        String correlationId = message == null ? "-" : message.getCorrelationId();
        System.out.printf(
                "ts=%s component=%s action=%s messageId=%s correlationId=%s %s%n",
                Instant.now(),
                component,
                action,
                messageId,
                correlationId,
                details == null ? "" : details);
    }

    public static synchronized void event(
            String component,
            String action,
            String messageId,
            String correlationId,
            String details) {
        System.out.printf(
                "ts=%s component=%s action=%s messageId=%s correlationId=%s %s%n",
                Instant.now(),
                component,
                action,
                safe(messageId),
                safe(correlationId),
                details == null ? "" : details);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
