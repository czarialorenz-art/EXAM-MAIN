package exceptions;

/** Thrown for invalid exam configuration or an exam action that is not allowed in the current state. */
public class InvalidExamException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public InvalidExamException(String message) {
        super(message);
    }

    public InvalidExamException(String message, Throwable cause) {
        super(message, cause);
    }
}
