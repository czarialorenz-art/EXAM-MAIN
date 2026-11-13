package exceptions;

/** Thrown when an examination attempt is submitted (or graded) more than once. */
public class DuplicateSubmissionException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public DuplicateSubmissionException(String message) {
        super(message);
    }

    public DuplicateSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
