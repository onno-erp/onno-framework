package su.onno.auth.users;

/** What a one-time secret is good for. A secret issued for one purpose never satisfies the other. */
public enum AuthCodePurpose {

    /** A high-entropy token that authorizes setting a new password (a "reset link"). */
    PASSWORD_RESET,

    /** A short numeric code that signs the user in without a password. */
    LOGIN
}
