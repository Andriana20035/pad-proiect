package md.lab2.store;

import md.lab2.rpc.v1.Envelope;

public record DeliveryRecord(
        long deliveryId,
        String topic,
        String consumerId,
        Envelope message,
        int attempt,
        long leaseDeadlineEpochMs) {
}
