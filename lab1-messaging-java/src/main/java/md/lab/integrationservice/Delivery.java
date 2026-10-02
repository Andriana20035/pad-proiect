package md.lab.integrationservice;

import md.lab.api.MessageEnvelope;

public record Delivery(MessageEnvelope message, int attempt) {
    public Delivery nextAttempt() {
        return new Delivery(message, attempt + 1);
    }
}
