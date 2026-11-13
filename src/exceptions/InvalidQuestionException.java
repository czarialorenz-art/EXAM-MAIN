package exceptions;

/** Thrown when question data is incomplete or inconsistent (e.g. no correct answer). */
public class InvalidQuestionException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public InvalidQuestionException(String message) {
        super(message);
    }

    public InvalidQuestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
