package models;

/** Grading state of a result. Results are FINAL only when no manual grading remains. */
public enum ResultStatus {
    PENDING_MANUAL("Awaiting manual grading"),
    FINAL("Final");

    private final String label;

    ResultStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
