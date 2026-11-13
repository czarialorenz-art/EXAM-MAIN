package exceptions;

/** Thrown when a username, user ID or student number already exists. */
public class DuplicateUserException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public DuplicateUserException(String message) {
        super(message);
    }

    public DuplicateUserException(String message, Throwable cause) {
        super(message, cause);
    }
}
