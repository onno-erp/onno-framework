package su.onno.auth.users;

/** A {@link UserAccounts} command was refused. The message is safe to show an administrator. */
public class UserAccountException extends RuntimeException {

    /** Why the command was refused. */
    public enum Reason {
        /** No such account. */
        NOT_FOUND,
        /** The request itself is malformed (bad username, bad role, wrong current password). */
        INVALID,
        /** The request conflicts with existing state (duplicate username, last administrator). */
        CONFLICT
    }

    private final Reason reason;

    public UserAccountException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
