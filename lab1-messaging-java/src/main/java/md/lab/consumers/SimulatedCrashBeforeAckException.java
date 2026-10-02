package md.lab.consumers;

public class SimulatedCrashBeforeAckException extends RuntimeException {
    public SimulatedCrashBeforeAckException(String message) {
        super(message);
    }
}
