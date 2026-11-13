package su.onno.auth.users;

/** Thrown when an account is created with a login that already exists. */
public class DuplicateLoginException extends RuntimeException {

    public DuplicateLoginException(String login) {
        super("A user with login '" + login + "' already exists");
    }
}
