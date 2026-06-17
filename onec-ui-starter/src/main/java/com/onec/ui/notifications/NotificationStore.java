package com.onec.ui.notifications;

import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code onec_notifications}: the framework-owned inbox store behind the
 * {@code /api/notifications} endpoint. Like {@code onec_comments} and {@code onec_schema_history} it
 * is infrastructure, not a modelled catalog — created with a plain {@code CREATE TABLE IF NOT EXISTS}
 * at startup rather than through the metadata diff engine, and it never appears in the navigation or
 * REST model.
 *
 * <p>Every read/mutate is scoped to a set of recipient keys (see {@link NotificationRecipients}) so a
 * caller can only ever see or change notifications addressed to <em>them</em> — the controller passes
 * the keys it resolved from the authenticated principal, never an id the client asserted.
 */
public class NotificationStore {

    static final String TABLE = "onec_notifications";

    private final Jdbi jdbi;

    public NotificationStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        ensureSchema();
    }

    /** Create the inbox table and its lookup index if they don't exist yet (idempotent). */
    private void ensureSchema() {
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n" +
                    "    _id UUID PRIMARY KEY,\n" +
                    "    _recipient VARCHAR(255) NOT NULL,\n" +
                    "    _title VARCHAR(512) NOT NULL,\n" +
                    "    _body TEXT,\n" +
                    "    _severity VARCHAR(32) NOT NULL,\n" +
                    "    _category VARCHAR(64),\n" +
                    "    _link VARCHAR(1024),\n" +
                    "    _created_at TIMESTAMP NOT NULL,\n" +
                    "    _read BOOLEAN NOT NULL DEFAULT FALSE,\n" +
                    "    _read_at TIMESTAMP\n" +
                    ")");
            // The inbox query is always "my unread, newest first", so index the recipient + read flag.
            h.execute("CREATE INDEX IF NOT EXISTS onec_notifications_recipient_idx ON " + TABLE +
                    " (_recipient, _read, _created_at)");
        });
    }

    /** Persist a notification exactly as given. */
    public void insert(Notification n) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + TABLE +
                        " (_id, _recipient, _title, _body, _severity, _category, _link, _created_at, _read, _read_at)" +
                        " VALUES (:id, :recipient, :title, :body, :severity, :category, :link, :createdAt, FALSE, NULL)")
                .bind("id", n.id())
                .bind("recipient", n.recipient())
                .bind("title", n.title())
                .bind("body", n.body())
                .bind("severity", n.severity().name())
                .bind("category", n.category())
                .bind("link", n.link())
                .bind("createdAt", n.createdAt())
                .execute());
    }

    /** A recipient's inbox, newest first, optionally only the unread ones, capped at {@code limit}. */
    public List<Notification> list(Collection<String> recipients, boolean unreadOnly, int limit) {
        if (recipients.isEmpty()) {
            return List.of();
        }
        String unread = unreadOnly ? " AND _read = FALSE" : "";
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE +
                        " WHERE _recipient IN (<recipients>)" + unread +
                        " ORDER BY _created_at DESC, _id DESC LIMIT :limit")
                .bindList("recipients", List.copyOf(recipients))
                .bind("limit", Math.max(1, limit))
                .map((rs, ctx) -> map(rs))
                .list());
    }

    /** How many unread notifications a recipient set holds. */
    public long countUnread(Collection<String> recipients) {
        if (recipients.isEmpty()) {
            return 0;
        }
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + TABLE +
                        " WHERE _recipient IN (<recipients>) AND _read = FALSE")
                .bindList("recipients", List.copyOf(recipients))
                .mapTo(Long.class)
                .one());
    }

    /** Mark one notification read, but only if it is addressed to the caller. Returns rows affected. */
    public int markRead(UUID id, Collection<String> recipients) {
        if (recipients.isEmpty()) {
            return 0;
        }
        return jdbi.withHandle(h -> h.createUpdate("UPDATE " + TABLE +
                        " SET _read = TRUE, _read_at = :now" +
                        " WHERE _id = :id AND _recipient IN (<recipients>) AND _read = FALSE")
                .bind("id", id)
                .bind("now", LocalDateTime.now())
                .bindList("recipients", List.copyOf(recipients))
                .execute());
    }

    /** Mark every unread notification in the caller's inbox read. Returns rows affected. */
    public int markAllRead(Collection<String> recipients) {
        if (recipients.isEmpty()) {
            return 0;
        }
        return jdbi.withHandle(h -> h.createUpdate("UPDATE " + TABLE +
                        " SET _read = TRUE, _read_at = :now" +
                        " WHERE _recipient IN (<recipients>) AND _read = FALSE")
                .bind("now", LocalDateTime.now())
                .bindList("recipients", List.copyOf(recipients))
                .execute());
    }

    /** Hard-delete one notification, but only if it is addressed to the caller. Returns rows affected. */
    public int delete(UUID id, Collection<String> recipients) {
        if (recipients.isEmpty()) {
            return 0;
        }
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM " + TABLE +
                        " WHERE _id = :id AND _recipient IN (<recipients>)")
                .bind("id", id)
                .bindList("recipients", List.copyOf(recipients))
                .execute());
    }

    private static Notification map(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("_created_at");
        Timestamp readAt = rs.getTimestamp("_read_at");
        return new Notification(
                rs.getObject("_id", UUID.class),
                rs.getString("_recipient"),
                rs.getString("_title"),
                rs.getString("_body"),
                NotificationSeverity.fromString(rs.getString("_severity")),
                rs.getString("_category"),
                rs.getString("_link"),
                created == null ? null : created.toLocalDateTime(),
                rs.getBoolean("_read"),
                readAt == null ? null : readAt.toLocalDateTime());
    }
}
