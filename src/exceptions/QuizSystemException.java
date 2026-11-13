package exceptions;

/**
 * Base class of every checked exception thrown by the application.
 * The UI catches this single type and prints {@link #getMessage()}, so every
 * subclass must carry a message that makes sense to an end user.
 */
public class QuizSystemException extends Exception {
    private static final long serialVersionUID = 1L;

    public QuizSystemException(String message) {
        super(message);
    }

    public QuizSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
