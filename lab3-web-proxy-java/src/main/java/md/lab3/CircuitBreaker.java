package md.lab3;

/** CLOSED -> OPEN on repeated failures -> one HALF_OPEN probe -> CLOSED/OPEN. */
final class CircuitBreaker {
    private enum State { CLOSED, OPEN, HALF_OPEN }
    private State state = State.CLOSED;
    private int failures = 0;
    private boolean probeInProgress = false;
    private long openUntil = 0;
    private final int threshold;
    private final long openMs;

    CircuitBreaker(int threshold, long openMs) {
        this.threshold = threshold;
        this.openMs = openMs;
    }
    synchronized boolean acquire() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() < openUntil) return false;
            state = State.HALF_OPEN;
        }
        if (state == State.HALF_OPEN) {
            if (probeInProgress) return false;
            probeInProgress = true;
        }
        return true;
    }
    synchronized void success() {
        failures = 0;
        probeInProgress = false;
        state = State.CLOSED;
    }
    synchronized void failure() {
        probeInProgress = false;
        failures++;
        if (state == State.HALF_OPEN || failures >= threshold) {
            state = State.OPEN;
            openUntil = System.currentTimeMillis() + openMs;
        }
    }
    synchronized String state() { return state.toString(); }
    synchronized int failureCount() { return failures; }
}
