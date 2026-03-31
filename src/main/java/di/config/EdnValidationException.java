package di.config;

public final class EdnValidationException extends RuntimeException {
    public EdnValidationException(String message) {
        super(message);
    }

    public EdnValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
