package services;

import data.DataStore;
import exceptions.AccessDeniedException;
import exceptions.AuthenticationException;
import exceptions.QuizSystemException;
import exceptions.ValidationException;
import models.Admin;
import models.User;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Login, first-run setup and password changes. */
public class AuthenticationService {
    public static final int MAX_FAILED_ATTEMPTS = 5;

    private final DataStore store;
    private final Map<String, Integer> failedAttempts = new HashMap<>();

    public AuthenticationService(DataStore store) {
        this.store = store;
    }

    /** True when no account exists yet (first run): the UI must create the first administrator. */
    public boolean needsInitialSetup() {
        return store.userCount() == 0;
    }

    public Admin createInitialAdmin(String username, String password, String fullName, String email)
            throws QuizSystemException {
        if (!needsInitialSetup()) {
            throw new AccessDeniedException("Initial setup has already been completed.");
        }
        Admin admin = new Admin(store.nextId("ADM"), username, password, fullName, email);
        store.addUser(admin);
        store.saveUsers();
        return admin;
    }

    /**
     * Validates credentials. The same message is used for an unknown username and a wrong
     * password so attackers cannot discover which usernames exist. After
     * {@value #MAX_FAILED_ATTEMPTS} failures a username is locked for the rest of the session.
     */
    public User login(String username, String password) throws AuthenticationException {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new AuthenticationException("Username and password are required.");
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        if (failedAttempts.getOrDefault(key, 0) >= MAX_FAILED_ATTEMPTS) {
            throw new AuthenticationException("Too many failed attempts for this username. "
                    + "Restart the application or ask an administrator to reset the password.");
        }
        User user = store.findUserByUsername(username).orElse(null);
        if (user == null || !user.verifyPassword(password)) {
            int fails = failedAttempts.merge(key, 1, Integer::sum);
            int left = MAX_FAILED_ATTEMPTS - fails;
            throw new AuthenticationException("Invalid username or password."
                    + (left > 0 ? " (" + left + " attempt(s) left)" : " This username is now locked."));
        }
        if (!user.isActive()) {
            throw new AuthenticationException("This account is deactivated. Please contact an administrator.");
        }
        failedAttempts.remove(key);
        return user;
    }

    public void changePassword(User user, String currentPassword, String newPassword, String confirmation)
            throws QuizSystemException {
        if (user == null) {
            throw new AccessDeniedException("You must be logged in.");
        }
        if (newPassword == null || !newPassword.equals(confirmation)) {
            throw new ValidationException("The new passwords do not match.");
        }
        user.changePassword(currentPassword, newPassword);
        store.saveUsers();
    }

    /** Clears a lock after an administrator resets a password. */
    void clearFailedAttempts(String username) {
        failedAttempts.remove(username.trim().toLowerCase(Locale.ROOT));
    }
}
