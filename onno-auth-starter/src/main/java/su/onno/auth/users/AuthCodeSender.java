package su.onno.auth.users;

/**
 * Delivery SPI for password-reset tokens and one-time login codes. The framework generates, stores
 * (hashed), rate-limits and verifies the secret; getting it to the person — e-mail, SMS, a messenger —
 * is the host application's job. Register one bean:
 *
 * <pre>{@code
 * @Component
 * class MailCodeSender implements AuthCodeSender {
 *     public void send(AuthCodeMessage message) {
 *         // look the address up by message.login(), then mail message.code()
 *     }
 * }
 * }</pre>
 *
 * <p>Called off the request thread, and only for an existing, enabled account, so delivery latency
 * never reveals whether a login exists. An exception is logged and otherwise ignored; the user simply
 * requests another code. Without a bean the {@link LoggingAuthCodeSender} is used, which delivers
 * nothing.
 */
@FunctionalInterface
public interface AuthCodeSender {

    void send(AuthCodeMessage message);
}
