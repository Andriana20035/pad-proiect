package md.lab2.store;

public class StoreException extends RuntimeException {
    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        PRECONDITION,
        STORAGE
    }

    private final Kind kind;

    public StoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StoreException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
