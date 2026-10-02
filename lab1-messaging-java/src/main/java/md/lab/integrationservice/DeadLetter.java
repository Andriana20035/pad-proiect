package md.lab.integrationservice;

import md.lab.api.MessageEnvelope;

public record DeadLetter(String consumerId, MessageEnvelope message, String reason, int attempts) {
}
