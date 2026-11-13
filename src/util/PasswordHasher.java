package util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing (built into the JDK, no libraries needed).
 * Plain-text passwords are never stored; only the Base64 hash and a random per-user salt.
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65_536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** Creates a new random salt, Base64 encoded. */
    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** Hashes {@code password} with {@code salt}; returns the Base64 encoded hash. */
    public static String hash(String password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt),
                ITERATIONS, KEY_LENGTH_BITS);
        try {
            byte[] hash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Password hashing algorithm is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Constant-time comparison of a candidate password against a stored hash. */
    public static boolean matches(String password, String salt, String expectedHash) {
        if (password == null || salt == null || expectedHash == null) {
            return false;
        }
        byte[] actual = hash(password, salt).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
