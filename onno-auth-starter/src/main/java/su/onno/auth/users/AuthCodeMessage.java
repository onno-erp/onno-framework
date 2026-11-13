package su.onno.auth.users;

import java.time.Instant;

/**
 * A one-time secret to deliver to a user.
 *
 * @param login     the account's login; the sender resolves the address (e-mail, phone, chat) from it,
 *                  typically through the {@code Layout.identity(...)} catalog record
 * @param purpose   password reset or one-time login
 * @param code      the secret itself — a URL-safe token for {@link AuthCodePurpose#PASSWORD_RESET}
 *                  (embed it, together with the login, in the link you send), digits for
 *                  {@link AuthCodePurpose#LOGIN}. Never log or persist it.
 * @param expiresAt when the secret stops working
 */
public record AuthCodeMessage(String login, AuthCodePurpose purpose, String code, Instant expiresAt) {

    /** Keeps the secret out of logs and exception messages. */
    @Override
    public String toString() {
        return "AuthCodeMessage[login=" + login + ", purpose=" + purpose + ", expiresAt=" + expiresAt + "]";
    }
}
