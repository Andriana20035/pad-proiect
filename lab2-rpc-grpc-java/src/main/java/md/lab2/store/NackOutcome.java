package md.lab2.store;

public record NackOutcome(boolean movedToDlq, long retryAfterMs, int attempt) {
}
