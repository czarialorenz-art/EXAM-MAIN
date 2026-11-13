package models;

/** Examination lifecycle. ACTIVE/COMPLETED are derived from the schedule (see Exam#getEffectiveStatus). */
public enum ExamStatus {
    DRAFT("Draft"),
    PUBLISHED("Published"),
    ACTIVE("Active"),
    COMPLETED("Completed"),
    ARCHIVED("Archived");

    private final String label;

    ExamStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
