package models;

import exceptions.ValidationException;
import util.InputValidator;
import util.PasswordHasher;

import java.io.Serializable;
import java.util.Objects;

/**
 * Abstract parent of every account (inheritance root for Admin, Teacher and Student).
 * <p>
 * Encapsulation: all fields are private, setters validate before changing state, and the
 * password hash and salt have no getters at all - the only way to check a password is
 * {@link #verifyPassword(String)}.
 */
public abstract class User implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String userId;
    private String username;
    private String passwordHash;
    private String salt;
    private String fullName;
    private String email;
    private boolean active = true;

    protected User(String userId, String username, String plainPassword, String fullName, String email)
            throws ValidationException {
        this.userId = InputValidator.requireText(userId, "User ID", 20);
        setUsername(username);
        setFullName(fullName);
        setEmail(email);
        applyNewPassword(plainPassword);
    }

    /** Overridden by each subclass - used for role-based menus and access checks. */
    public abstract Role getRole();

    /** One-line, role-specific description (polymorphic). */
    public abstract String getDisplaySummary();

    /** Label of the role-specific editable detail, or null if the role has none. */
    public String getRoleDetailLabel() {
        return null;
    }

    public String getRoleDetail() {
        return "";
    }

    /** Updates the role-specific detail (department for teachers, year level for students). */
    public void setRoleDetail(String value) throws ValidationException {
        // Admins have no role-specific detail.
    }

    /** Text searched by "search users"; subclasses add their own identifiers. */
    public String getSearchText() {
        return String.join(" ", userId, username, fullName, email);
    }

    public final String getUserId() {
        return userId;
    }

    public final String getUsername() {
        return username;
    }

    public final String getFullName() {
        return fullName;
    }

    public final String getEmail() {
        return email;
    }

    public final boolean isActive() {
        return active;
    }

    public final void setUsername(String username) throws ValidationException {
        this.username = InputValidator.validateUsername(username);
    }

    public final void setFullName(String fullName) throws ValidationException {
        this.fullName = InputValidator.requireText(fullName, "Full name", 80);
    }

    public final void setEmail(String email) throws ValidationException {
        this.email = InputValidator.validateEmail(email);
    }

    public final void setActive(boolean active) {
        this.active = active;
    }

    public final boolean verifyPassword(String plainPassword) {
        return PasswordHasher.matches(plainPassword, salt, passwordHash);
    }

    /** Self-service password change: requires the current password. */
    public final void changePassword(String currentPlain, String newPlain) throws ValidationException {
        if (!verifyPassword(currentPlain)) {
            throw new ValidationException("Current password is incorrect.");
        }
        if (currentPlain.equals(newPlain)) {
            throw new ValidationException("New password must be different from the current password.");
        }
        applyNewPassword(newPlain);
    }

    /** Administrative reset (the service layer checks that the caller is an admin). */
    public final void resetPassword(String newPlain) throws ValidationException {
        applyNewPassword(newPlain);
    }

    private void applyNewPassword(String plain) throws ValidationException {
        InputValidator.validatePassword(plain);
        String newSalt = PasswordHasher.newSalt();
        this.passwordHash = PasswordHasher.hash(plain, newSalt);
        this.salt = newSalt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof User other && userId.equals(other.userId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    @Override
    public String toString() {
        return fullName + " (" + username + ", " + getRole().getLabel() + ")";
    }
}
