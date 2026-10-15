package md.lab2.store;

public final class BackoffPolicy {
    private final long baseMs;
    private final long maxMs;

    public BackoffPolicy(long baseMs, long maxMs) {
        if (baseMs < 0 || maxMs < baseMs) {
            throw new IllegalArgumentException("Invalid backoff values");
        }
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    public long delayMillis(int failedAttempt) {
        if (failedAttempt <= 0) {
            return 0;
        }
        int shift = Math.min(failedAttempt - 1, 20);
        long candidate;
        try {
            candidate = Math.multiplyExact(baseMs, 1L << shift);
        } catch (ArithmeticException ignored) {
            candidate = maxMs;
        }
        return Math.min(candidate, maxMs);
    }
}
