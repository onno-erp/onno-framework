package su.onno.ui.notifications;

import org.jdbi.v3.core.Jdbi;

import su.onno.ui.SqlBind;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code onno_notifications}: the framework-owned, per-user notification timeline behind
 * the {@code /api/notifications} endpoint. Like {@link su.onno.ui.comments.CommentService}'s
 * {@code onno_comments}, it is infrastructure — created with a plain {@code CREATE TABLE IF NOT EXISTS}
 * at startup rather than through the metadata diff engine, and never modelled, navigated, or served by
 * the generic REST catalog API.
 *
 * <p>Rows are keyed for reading by {@code _recipient} and ordered newest-first. The feed is
 * keyset-paginated on {@code (_created_at DESC, _id DESC)} with an opaque cursor, so a client scrolls
 * the history with no offset arithmetic. "Read" is a nullable {@code _read_at} timestamp; unread rows
 * are those with {@code _read_at IS NULL}.
 *
 * <p>Timestamps are {@link Instant}s carried through plain {@code TIMESTAMP} columns via
 * {@link java.sql.Timestamp} on write and {@code getTimestamp().toInstant()} on read, the same
 * zone-symmetric round-trip the comments store uses (#177).
 */
public class NotificationStore {

    static final String TABLE = "onno_notifications";

    private final Jdbi jdbi;

    public NotificationStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        ensureSchema();
    }

    /** Create the timeline table and its per-recipient lookup index if absent (idempotent). */
    private void ensureSchema() {
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n" +
                    "    _id UUID PRIMARY KEY,\n" +
                    "    _recipient VARCHAR(256) NOT NULL,\n" +
                    "    _type VARCHAR(64) NOT NULL,\n" +
                    "    _title VARCHAR(512) NOT NULL,\n" +
                    "    _body TEXT,\n" +
                    "    _link VARCHAR(512),\n" +
                    "    _actor_id VARCHAR(256),\n" +
                    "    _actor_name VARCHAR(256),\n" +
                    "    _actor_avatar VARCHAR(1024),\n" +
                    "    _created_at TIMESTAMP NOT NULL,\n" +
                    "    _read_at TIMESTAMP\n" +
                    ")");
            // Feed reads scan one recipient newest-first; the composite index serves both the ORDER BY
            // and the unread filter without a sort.
            h.execute("CREATE INDEX IF NOT EXISTS onno_notifications_feed_idx ON " + TABLE +
                    " (_recipient, _created_at DESC, _id DESC)");
        });
    }

    /** Persist a new notification and return it as stored. */
    public Notification insert(String recipientId, String type, String title, String body, String link,
                               String actorId, String actorName, String actorAvatar) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbi.useHandle(h -> {
            var update = h.createUpdate("INSERT INTO " + TABLE +
                            " (_id, _recipient, _type, _title, _body, _link, _actor_id, _actor_name," +
                            " _actor_avatar, _created_at, _read_at)" +
                            " VALUES (:id, :recipient, :type, :title, :body, :link, :actorId, :actorName," +
                            " :actorAvatar, :createdAt, NULL)")
                    .bind("id", id)
                    .bind("recipient", recipientId)
                    .bind("type", type)
                    .bind("title", title)
                    .bind("createdAt", Timestamp.from(now));
            SqlBind.nullable(update, "body", body);
            SqlBind.nullable(update, "link", link);
            SqlBind.nullable(update, "actorId", actorId);
            SqlBind.nullable(update, "actorName", actorName);
            SqlBind.nullable(update, "actorAvatar", actorAvatar);
            update.execute();
        });
        return new Notification(id, recipientId, type, title, body, link, actorId, actorName, actorAvatar, now, null);
    }

    /**
     * One newest-first window of a recipient's timeline. {@code unreadOnly} restricts to unread rows;
     * {@code cursor} (from a previous {@link Page#nextCursor()}) resumes after the last row returned;
     * {@code limit} caps the window. Never returns another user's rows.
     */
    public Page list(String recipientId, boolean unreadOnly, String cursor, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        Cursor after = Cursor.decode(cursor);
        StringBuilder sql = new StringBuilder("SELECT * FROM " + TABLE + " WHERE _recipient = :recipient");
        if (unreadOnly) {
            sql.append(" AND _read_at IS NULL");
        }
        if (after != null) {
            // Keyset: strictly older than the cursor row in (_created_at DESC, _id DESC) order.
            sql.append(" AND (_created_at < :cursorTs OR (_created_at = :cursorTs AND _id < :cursorId))");
        }
        sql.append(" ORDER BY _created_at DESC, _id DESC LIMIT :limit");
        List<Notification> rows = jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString())
                    .bind("recipient", recipientId)
                    .bind("limit", capped + 1); // fetch one extra to detect a further window
            if (after != null) {
                q.bind("cursorTs", Timestamp.from(after.createdAt())).bind("cursorId", after.id());
            }
            return q.map((rs, ctx) -> map(rs)).list();
        });
        boolean hasMore = rows.size() > capped;
        if (hasMore) {
            rows = rows.subList(0, capped);
        }
        String next = hasMore && !rows.isEmpty()
                ? Cursor.encode(rows.get(rows.size() - 1)) : null;
        return new Page(rows, next, hasMore);
    }

    /** How many of a recipient's notifications are still unread — the badge count. */
    public int unreadCount(String recipientId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + TABLE +
                        " WHERE _recipient = :recipient AND _read_at IS NULL")
                .bind("recipient", recipientId)
                .mapTo(Integer.class)
                .one());
    }

    /**
     * Mark one notification read, but only if it belongs to {@code recipientId} (a user can't read
     * another's). Returns false when it doesn't exist, isn't theirs, or was already read.
     */
    public boolean markRead(UUID id, String recipientId) {
        return jdbi.withHandle(h -> h.createUpdate("UPDATE " + TABLE +
                        " SET _read_at = :now WHERE _id = :id AND _recipient = :recipient AND _read_at IS NULL")
                .bind("now", Timestamp.from(Instant.now()))
                .bind("id", id)
                .bind("recipient", recipientId)
                .execute()) > 0;
    }

    /** Mark every unread notification of a recipient read; returns how many were flipped. */
    public int markAllRead(String recipientId) {
        return jdbi.withHandle(h -> h.createUpdate("UPDATE " + TABLE +
                        " SET _read_at = :now WHERE _recipient = :recipient AND _read_at IS NULL")
                .bind("now", Timestamp.from(Instant.now()))
                .bind("recipient", recipientId)
                .execute());
    }

    /** Delete read notifications older than the cutoff — the retention sweep. Returns rows removed. */
    public int pruneReadBefore(Instant cutoff) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM " + TABLE +
                        " WHERE _read_at IS NOT NULL AND _created_at < :cutoff")
                .bind("cutoff", Timestamp.from(cutoff))
                .execute());
    }

    private static Notification map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("_created_at");
        Timestamp read = rs.getTimestamp("_read_at");
        return new Notification(
                rs.getObject("_id", UUID.class),
                rs.getString("_recipient"),
                rs.getString("_type"),
                rs.getString("_title"),
                rs.getString("_body"),
                rs.getString("_link"),
                rs.getString("_actor_id"),
                rs.getString("_actor_name"),
                rs.getString("_actor_avatar"),
                created == null ? null : created.toInstant(),
                read == null ? null : read.toInstant());
    }

    /** One newest-first window of a timeline, with the cursor to resume after it. */
    public record Page(List<Notification> items, String nextCursor, boolean hasMore) {}

    /**
     * The keyset position after a row: its {@code (_created_at, _id)} pair, opaquely base64-encoded. The
     * timestamp is carried at <em>full</em> precision (epoch-second + nanos), not truncated to millis —
     * the {@code TIMESTAMP} column keeps sub-millisecond digits, so a millisecond-only cursor would make
     * same-millisecond siblings fall through both arms of the keyset comparison and silently vanish
     * between windows.
     */
    private record Cursor(Instant createdAt, UUID id) {

        static String encode(Notification n) {
            Instant t = n.createdAt();
            String raw = t.getEpochSecond() + ":" + t.getNano() + ":" + n.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor));
                String[] parts = raw.split(":", 3);
                Instant createdAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                return new Cursor(createdAt, UUID.fromString(parts[2]));
            } catch (RuntimeException e) {
                return null; // a garbled cursor just restarts the feed from the top
            }
        }
    }
}
