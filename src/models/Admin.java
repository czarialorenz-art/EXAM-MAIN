package models;

import exceptions.ValidationException;

/** Administrator account: manages users, subjects, classes and system-wide reports. */
public class Admin extends User {
    private static final long serialVersionUID = 1L;

    public Admin(String userId, String username, String plainPassword, String fullName, String email)
            throws ValidationException {
        super(userId, username, plainPassword, fullName, email);
    }

    @Override
    public Role getRole() {
        return Role.ADMIN;
    }

    @Override
    public String getDisplaySummary() {
        return "System administrator";
    }
}
