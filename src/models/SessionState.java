package models;

/** State of one examination attempt. */
public enum SessionState {
    IN_PROGRESS("In progress"),
    PAUSED("Paused"),
    SUBMITTED("Submitted"),
    EXPIRED("Expired (not submitted)");

    private final String label;

    SessionState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
