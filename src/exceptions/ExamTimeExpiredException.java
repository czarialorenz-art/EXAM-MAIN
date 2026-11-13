package exceptions;

/** Thrown when a student tries to answer or submit after the examination deadline. */
public class ExamTimeExpiredException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public ExamTimeExpiredException(String message) {
        super(message);
    }

    public ExamTimeExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
