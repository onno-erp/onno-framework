package su.onno.web.notifications;

import org.jdbi.v3.core.Jdbi;

import su.onno.service.SqlBind;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code onno_notifications}: the framework-owned, per-user notification timeline behind
 * the {@code /api/notifications} endpoint. Like {@link su.onno.web.comments.CommentService}'s
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
            h.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS _target_kind VARCHAR(32)");
            h.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS _target_name VARCHAR(256)");
            h.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS _target_id UUID");
            h.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS _target_indexed BOOLEAN DEFAULT false NOT NULL");
            normalizeTargets(h, null);
            h.execute("CREATE INDEX IF NOT EXISTS onno_notifications_target_backfill_idx ON " + TABLE + " (_recipient, _target_indexed)");
            // Feed reads scan one recipient newest-first; the composite index serves both the ORDER BY
            // and the unread filter without a sort.
            h.execute("CREATE INDEX IF NOT EXISTS onno_notifications_feed_idx ON " + TABLE +
                    " (_recipient, _created_at DESC, _id DESC)");
        });
    }

    /** Also repairs rows written by an older node during a rolling deployment. */
    private void normalizeTargets(org.jdbi.v3.core.Handle h, String recipient) {
        while (true) {
            var query = h.createQuery("SELECT _id, _link FROM " + TABLE + " WHERE _target_indexed = false"
                    + (recipient == null ? "" : " AND _recipient = :recipient") + " LIMIT 500");
            if (recipient != null) query.bind("recipient", recipient);
            var old = query.mapToMap().list();
            if (old.isEmpty()) break;
            for (var row : old) {
                var target = su.onno.service.RecordAccess.routeTarget((String) row.get("_link"));
                var q = h.createUpdate("UPDATE " + TABLE + " SET _target_kind = :kind, _target_name = :name,"
                        + " _target_id = :target, _target_indexed = true WHERE _id = :id").bind("id", row.get("_id"));
                SqlBind.nullable(q, "kind", target == null ? null : target.kind());
                SqlBind.nullable(q, "name", target == null ? null : target.name());
                SqlBind.nullable(q, "target", target == null ? null : target.id());
                q.execute();
            }
        }
    }

    /** Persist a new notification and return it as stored. */
    public Notification insert(String recipientId, String type, String title, String body, String link,
                               String actorId, String actorName, String actorAvatar) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var target = su.onno.service.RecordAccess.routeTarget(link);
        jdbi.useHandle(h -> {
            var update = h.createUpdate("INSERT INTO " + TABLE +
                            " (_id, _recipient, _type, _title, _body, _link, _actor_id, _actor_name," +
                            " _actor_avatar, _created_at, _read_at, _target_kind, _target_name, _target_id, _target_indexed)" +
                            " VALUES (:id, :recipient, :type, :title, :body, :link, :actorId, :actorName," +
                            " :actorAvatar, :createdAt, NULL, :targetKind, :targetName, :targetId, true)")
                    .bind("id", id)
                    .bind("recipient", recipientId)
                    .bind("type", type)
                    .bind("title", title)
                    .bind("createdAt", Timestamp.from(now));
            SqlBind.nullable(update, "targetKind", target == null ? null : target.kind());
            SqlBind.nullable(update, "targetName", target == null ? null : target.name());
            SqlBind.nullable(update, "targetId", target == null ? null : target.id());
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
        return list(recipientId, unreadOnly, cursor, limit, su.onno.service.ScopeClause.ALL);
    }

    public Page list(String recipientId, boolean unreadOnly, String cursor, int limit,
                     su.onno.service.ScopeClause visibility) {
        try {
            jdbi.useHandle(h -> normalizeTargets(h, recipientId));
            var page = jdbi.withHandle(h -> su.onno.query.KeysetWindow.fetch(h, TABLE, "*",
                    "_recipient = :recipient" + (unreadOnly ? " AND _read_at IS NULL" : "") + visibility.and(),
                    q -> { q.bind("recipient", recipientId); visibility.bind(q); },
                    List.of(su.onno.query.KeysetWindow.Order.notNull("_created_at", true),
                            su.onno.query.KeysetWindow.Order.notNull("_id", true)), cursor, limit,
                    "notifications:" + recipientId + ":" + unreadOnly + ":" + visibility,
                    (rs, ctx) -> map(rs)));
            return new Page(page.rows(), page.nextCursor(), page.hasMore());
        } catch (su.onno.query.Cursor.ScopeMismatchException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Cursor belongs to another notification feed", e);
        }
    }

    /** How many of a recipient's notifications are still unread — the badge count. */
    public int unreadCount(String recipientId) { return unreadCount(recipientId, su.onno.service.ScopeClause.ALL); }

    public int unreadCount(String recipientId, su.onno.service.ScopeClause visibility) {
        if (!visibility.isAll()) jdbi.useHandle(h -> normalizeTargets(h, recipientId));
        return jdbi.withHandle(h -> visibility.bind(h.createQuery("SELECT COUNT(*) FROM " + TABLE +
                        " WHERE _recipient = :recipient AND _read_at IS NULL" + visibility.and()))
                .bind("recipient", recipientId)
                .mapTo(Integer.class)
                .one());
    }

    /**
     * The links of a recipient's unread notifications ({@code null} for one without a link) — so a
     * record-scoped viewer's badge can count only the notifications its feed shows.
     */
    public List<String> unreadLinks(String recipientId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT _link FROM " + TABLE +
                        " WHERE _recipient = :recipient AND _read_at IS NULL")
                .bind("recipient", recipientId)
                .mapTo(String.class)
                .list());
    }

    /**
     * The distinct notification types this recipient actually has, most-recent-first. Drives the panel's
     * filter tabs: a tab exists for a type only once the user has a notification of it, so the tab set is
     * fully modular — a custom producer's type gets a tab with no config, and a disabled built-in source
     * (its type no longer produced) drops out as its old notifications age out.
     */
    public List<String> distinctTypes(String recipientId) { return distinctTypes(recipientId, su.onno.service.ScopeClause.ALL); }

    public List<String> distinctTypes(String recipientId, su.onno.service.ScopeClause visibility) {
        if (!visibility.isAll()) jdbi.useHandle(h -> normalizeTargets(h, recipientId));
        return jdbi.withHandle(h -> visibility.bind(h.createQuery("SELECT _type FROM " + TABLE +
                        " WHERE _recipient = :recipient" + visibility.and() + " GROUP BY _type ORDER BY MAX(_created_at) DESC LIMIT 100"))
                .bind("recipient", recipientId)
                .mapTo(String.class)
                .list());
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

}
