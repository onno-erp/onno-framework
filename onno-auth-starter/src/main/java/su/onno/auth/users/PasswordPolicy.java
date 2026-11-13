package su.onno.auth.users;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Rules a new password must satisfy in {@code onno.auth.mode=database}. Deliberately small and
 * NIST-800-63B-shaped: a length floor, the bcrypt 72-byte ceiling, and no reuse of the username or
 * the current password. Composition rules ("one digit, one symbol") are not enforced — they push
 * users to predictable patterns without adding real entropy.
 *
 * @param minLength minimum length in characters
 */
public record PasswordPolicy(int minLength) {

    /** bcrypt silently ignores (Spring Security 6.3+ rejects) bytes beyond the 72nd. */
    static final int MAX_BYTES = 72;

    public PasswordPolicy {
        if (minLength < 1) {
            throw new IllegalArgumentException("onno.auth.database.password-min-length must be at least 1");
        }
    }

    /**
     * Validates {@code password} for {@code username}.
     *
     * @throws InvalidPasswordException naming the first rule it breaks
     */
    public void check(String username, String password) {
        if (password == null || password.isBlank()) {
            throw new InvalidPasswordException("Enter a password.");
        }
        if (password.codePointCount(0, password.length()) < minLength) {
            throw new InvalidPasswordException("Use at least " + minLength + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new InvalidPasswordException("Use at most " + MAX_BYTES + " bytes.");
        }
        if (username != null && password.trim().toLowerCase(Locale.ROOT)
                .equals(username.trim().toLowerCase(Locale.ROOT))) {
            throw new InvalidPasswordException("The password must differ from the username.");
        }
    }

    /** A new password, or a reset, was refused. The message is safe to show the user. */
    public static class InvalidPasswordException extends RuntimeException {
        public InvalidPasswordException(String message) {
            super(message);
        }
    }
}
