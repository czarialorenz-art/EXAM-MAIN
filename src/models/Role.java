package models;

/** The three account roles. Each role gets its own menu and permissions. */
public enum Role {
    ADMIN("Administrator"),
    TEACHER("Teacher"),
    STUDENT("Student");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
