package models;

/** The nine supported question types. */
public enum QuestionType {
    MULTIPLE_CHOICE("Multiple Choice"),
    MULTIPLE_SELECT("Multiple Select"),
    TRUE_FALSE("True or False"),
    IDENTIFICATION("Identification"),
    FILL_IN_THE_BLANKS("Fill in the Blanks"),
    ENUMERATION("Enumeration"),
    ESSAY("Essay"),
    MATCHING("Matching"),
    NUMERICAL("Numerical Answer");

    private final String label;

    QuestionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
