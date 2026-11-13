package util;

import exceptions.ValidationException;

import java.util.regex.Pattern;

/** Reusable validation rules. Every method returns the cleaned value or throws {@link ValidationException}. */
public final class InputValidator {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.]{4,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    public static final int MIN_PASSWORD_LENGTH = 8;

    private InputValidator() {
    }

    public static String requireText(String value, String field, int maxLength) throws ValidationException {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new ValidationException(field + " must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }

    /** Optional text: blank becomes an empty string. */
    public static String optionalText(String value, String field, int maxLength) throws ValidationException {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requireText(value, field, maxLength);
    }

    public static String validateUsername(String username) throws ValidationException {
        String u = requireText(username, "Username", 20);
        if (!USERNAME.matcher(u).matches()) {
            throw new ValidationException("Username must be 4-20 characters using letters, digits, '_' or '.'.");
        }
        return u;
    }

    public static void validatePassword(String password) throws ValidationException {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
        if (password.length() > 64) {
            throw new ValidationException("Password must be at most 64 characters long.");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw new ValidationException("Password must contain at least one letter and one digit.");
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            throw new ValidationException("Password must not contain spaces.");
        }
    }

    /** Email is optional; when given it must look like an address. */
    public static String validateEmail(String email) throws ValidationException {
        if (email == null || email.isBlank()) {
            return "";
        }
        String e = email.trim();
        if (e.length() > 100 || !EMAIL.matcher(e).matches()) {
            throw new ValidationException("'" + e + "' is not a valid email address.");
        }
        return e;
    }

    public static double requireRange(double value, double min, double max, String field) throws ValidationException {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new ValidationException(field + " must be between " + TextUtil.num(min) + " and " + TextUtil.num(max) + ".");
        }
        return value;
    }
}
