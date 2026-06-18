package su.onno.mail.suppression;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Addresses that must not be mailed: hard bounces, spam complaints, and explicit unsubscribes.
 * Recipients are checked against this list before every dispatch so the system stops hitting
 * addresses that damage sender reputation. Backed by {@code onno_mail_suppression}; addresses are
 * normalised to lower-case.
 */
public class MailSuppressionList {

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS onno_mail_suppression (\n" +
                    "    _address VARCHAR(320) PRIMARY KEY,\n" +
                    "    _reason VARCHAR(64) NOT NULL,\n" +
                    "    _detail TEXT,\n" +
                    "    _created_at TIMESTAMP NOT NULL\n" +
                    ")";

    private final Jdbi jdbi;

    public MailSuppressionList(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void initSchema() {
        jdbi.useHandle(h -> h.execute(DDL));
    }

    public boolean isSuppressed(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT 1 FROM onno_mail_suppression WHERE _address = :a")
                .bind("a", normalise(address))
                .mapTo(Integer.class)
                .findFirst()
                .isPresent());
    }

    public void suppress(String address, String reason, String detail) {
        if (address == null || address.isBlank()) {
            return;
        }
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO onno_mail_suppression (_address, _reason, _detail, _created_at) " +
                                "VALUES (:a, :reason, :detail, :now) " +
                                "ON CONFLICT (_address) DO UPDATE SET _reason = :reason, _detail = :detail")
                .bind("a", normalise(address))
                .bind("reason", reason == null ? "unknown" : reason)
                .bind("detail", detail)
                .bind("now", LocalDateTime.now())
                .execute());
    }

    public void remove(String address) {
        if (address == null || address.isBlank()) {
            return;
        }
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM onno_mail_suppression WHERE _address = :a")
                .bind("a", normalise(address))
                .execute());
    }

    public Optional<String> reasonFor(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT _reason FROM onno_mail_suppression WHERE _address = :a")
                .bind("a", normalise(address))
                .mapTo(String.class)
                .findFirst());
    }

    private static String normalise(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
