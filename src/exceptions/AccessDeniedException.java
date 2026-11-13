package exceptions;

/** Thrown when a user attempts an action their role or ownership does not allow. */
public class AccessDeniedException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public AccessDeniedException(String message) {
        super(message);
    }

    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
