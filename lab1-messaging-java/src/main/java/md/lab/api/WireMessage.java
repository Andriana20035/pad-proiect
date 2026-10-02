package md.lab.api;

public record WireMessage(
        String command,
        String channel,
        String consumerId,
        MessageEnvelope message,
        String messageId,
        String reason,
        Boolean retryable
) {
    public static WireMessage publish(String channel, MessageEnvelope message) {
        return publish(channel, message, null);
    }

    // null inseamna toti abonatii canalului.
    public static WireMessage publish(String channel, MessageEnvelope message, String consumerId) {
        return new WireMessage("PUBLISH", channel, consumerId, message, message.id(), null, null);
    }

    public static WireMessage subscribe(String consumerId, String channel) {
        return new WireMessage("SUBSCRIBE", channel, consumerId, null, null, null, null);
    }

    public static WireMessage subscribed(String consumerId, String channel) {
        return new WireMessage("SUBSCRIBED", channel, consumerId, null, null, null, null);
    }

    public static WireMessage deliver(String consumerId, String channel, MessageEnvelope message) {
        return new WireMessage("DELIVER", channel, consumerId, message, message.id(), null, null);
    }

    public static WireMessage ack(String consumerId, String messageId) {
        return new WireMessage("ACK", null, consumerId, null, messageId, null, null);
    }

    public static WireMessage nack(String consumerId, String messageId, String reason) {
        return nack(consumerId, messageId, reason, false);
    }

    public static WireMessage nack(String consumerId, String messageId, String reason, boolean retryable) {
        return new WireMessage("NACK", null, consumerId, null, messageId, reason, retryable);
    }

    public static WireMessage published(String messageId) {
        return new WireMessage("PUBLISHED", null, null, null, messageId, null, null);
    }
}
