package ui;

/** Thrown when standard input is closed (e.g. Ctrl+D / end of a piped script) so the app can exit cleanly. */
public class InputClosedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InputClosedException() {
        super("Input stream closed");
    }
}
