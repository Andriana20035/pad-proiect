package md.lab2.consumer;

public class SimulatedConsumerCrashException extends RuntimeException {
    public SimulatedConsumerCrashException(String message) {
        super(message);
    }
}
