package models;

/** How an attempt was submitted. */
public enum SubmissionType {
    MANUAL("Manual"),
    AUTO("Automatic");

    private final String label;

    SubmissionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
