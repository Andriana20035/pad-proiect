package md.lab2.store;

public record DlqRecord(
        long deliveryId,
        String messageId,
        String consumerId,
        String topic,
        int attempts,
        String reason,
        String movedAt) {
}
