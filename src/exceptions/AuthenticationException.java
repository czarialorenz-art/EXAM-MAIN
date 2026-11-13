package exceptions;

/** Thrown when login fails (wrong credentials, deactivated or locked account). */
public class AuthenticationException extends QuizSystemException {
    private static final long serialVersionUID = 1L;

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
