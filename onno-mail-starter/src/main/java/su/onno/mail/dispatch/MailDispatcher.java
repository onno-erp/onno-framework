package su.onno.mail.dispatch;

import su.onno.mail.MailMessage;

/**
 * Provider SPI. Each provider starter (SMTP default, SendGrid, SES, Resend, ...)
 * contributes one bean. The active dispatcher is selected by configuration.
 */
public interface MailDispatcher {

    /** Stable provider id, e.g. "smtp", "sendgrid", "ses". */
    String name();

    /** Synchronously deliver a message to the provider. Throws {@code MailDeliveryException} on failure. */
    void dispatch(MailMessage message);
}
