package md.lab2.consumer;

public class ProcessingException extends RuntimeException {
    private final boolean retryable;

    public ProcessingException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
