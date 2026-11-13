package exceptions;

/** Thrown when application data cannot be read from or written to disk. */
public class DataPersistenceException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public DataPersistenceException(String message) {
        super(message);
    }

    public DataPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
