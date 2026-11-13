package su.onno.auth.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link AuthCodeSender}: delivers nothing. It logs that a secret was issued, and prints
 * the secret itself only when {@code onno.auth.database.log-codes=true} — a development convenience
 * that must stay off wherever logs are shared.
 */
public class LoggingAuthCodeSender implements AuthCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuthCodeSender.class);

    private final boolean logCodes;

    public LoggingAuthCodeSender(boolean logCodes) {
        this.logCodes = logCodes;
    }

    @Override
    public void send(AuthCodeMessage message) {
        if (logCodes) {
            log.warn("[onno.auth.database.log-codes] {} secret for '{}': {} (expires {})",
                    message.purpose(), message.login(), message.code(), message.expiresAt());
        } else {
            log.warn("A {} secret was issued for '{}' but no AuthCodeSender bean is registered, so it "
                    + "was not delivered. Register an AuthCodeSender, or set "
                    + "onno.auth.database.log-codes=true in development.", message.purpose(), message.login());
        }
    }
}
