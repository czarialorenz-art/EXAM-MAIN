package exceptions;

/** Thrown when user-supplied data is missing or invalid. */
public class ValidationException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
