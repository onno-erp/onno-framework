package su.onno.ui.comments;

import org.jdbi.v3.core.Jdbi;

import su.onno.ui.SqlBind;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code onno_comments}: the framework-owned thread store behind the
 * {@code /api/comments} endpoint. It is infrastructure (like {@code onno_schema_history}), not a
 * modelled catalog — so it is created with a plain {@code CREATE TABLE IF NOT EXISTS} at startup
 * rather than through the metadata diff engine, and never appears in the navigation or REST model.
 *
 * <p>A thread is keyed by {@code (entity_type, entity_name, entity_id)}. Deletes are soft (the row
 * stays for audit with {@code _deleted = true}); reads filter them out.
 *
 * <p>Timestamps are {@link Instant}s. They go to and from the plain {@code TIMESTAMP} column through
 * {@link java.sql.Timestamp} ({@code Timestamp.from(instant)} on write, {@code getTimestamp().toInstant()}
 * on read), so the instant round-trips symmetrically regardless of the server's zone and no column
 * migration is needed (#177).
 */
public class CommentService {

    static final String TABLE = "onno_comments";
    static final String REACTIONS_TABLE = "onno_comment_reactions";

    private final Jdbi jdbi;

    public CommentService(Jdbi jdbi) {
        this.jdbi = jdbi;
        ensureSchema();
    }

    /** Create the thread table and its lookup index if they don't exist yet (idempotent). */
    private void ensureSchema() {
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n" +
                    "    _id UUID PRIMARY KEY,\n" +
                    "    _entity_type VARCHAR(32) NOT NULL,\n" +
                    "    _entity_name VARCHAR(128) NOT NULL,\n" +
                    "    _entity_id UUID NOT NULL,\n" +
                    "    _author_id UUID,\n" +
                    "    _author_name VARCHAR(256),\n" +
                    "    _body TEXT NOT NULL,\n" +
                    "    _parent_id UUID,\n" +
                    "    _created_at TIMESTAMP NOT NULL,\n" +
                    "    _edited_at TIMESTAMP,\n" +
                    "    _deleted BOOLEAN NOT NULL DEFAULT FALSE\n" +
                    ")");
            h.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS _parent_id UUID");
            h.execute("CREATE INDEX IF NOT EXISTS onno_comments_target_idx ON " + TABLE +
                    " (_entity_type, _entity_name, _entity_id)");
            h.execute("CREATE INDEX IF NOT EXISTS onno_comments_parent_idx ON " + TABLE + " (_parent_id)");
            h.execute("CREATE TABLE IF NOT EXISTS " + REACTIONS_TABLE + " (\n" +
                    "    _comment_id UUID NOT NULL,\n" +
                    "    _user_key VARCHAR(512) NOT NULL,\n" +
                    "    _emoji VARCHAR(32) NOT NULL,\n" +
                    "    _created_at TIMESTAMP NOT NULL,\n" +
                    "    PRIMARY KEY (_comment_id, _user_key, _emoji)\n" +
                    ")");
            h.execute("CREATE INDEX IF NOT EXISTS onno_comment_reactions_comment_idx ON " + REACTIONS_TABLE +
                    " (_comment_id)");
        });
    }

    /** The live thread for one entity, oldest comment first. */
    public List<Comment> list(String entityType, String entityName, UUID entityId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE +
                        " WHERE _entity_type = :type AND _entity_name = :name AND _entity_id = :id" +
                        " AND _deleted = FALSE ORDER BY _created_at ASC, _id ASC")
                .bind("type", entityType)
                .bind("name", entityName)
                .bind("id", entityId)
                .map((rs, ctx) -> map(rs.getObject("_id", UUID.class), entityType, entityName, entityId, rs))
                .list());
    }

    /** Append a comment and return it as stored. */
    public Comment add(String entityType, String entityName, UUID entityId,
                       String authorId, String authorName, String body) {
        return add(entityType, entityName, entityId, authorId, authorName, body, null);
    }

    /** Append a top-level comment or a reply and return it as stored. */
    public Comment add(String entityType, String entityName, UUID entityId,
                       String authorId, String authorName, String body, UUID parentId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        UUID authorUuid = authorId == null ? null : UUID.fromString(authorId);
        jdbi.useHandle(h -> {
            var update = h.createUpdate("INSERT INTO " + TABLE +
                            " (_id, _entity_type, _entity_name, _entity_id, _author_id, _author_name," +
                            " _body, _parent_id, _created_at, _edited_at, _deleted)" +
                            " VALUES (:id, :type, :name, :entityId, :authorId, :authorName, :body, :parentId, :createdAt, NULL, FALSE)")
                    .bind("id", id)
                    .bind("type", entityType)
                    .bind("name", entityName)
                    .bind("entityId", entityId)
                    .bind("authorName", authorName)
                    .bind("body", body)
                    // Bind through java.sql.Timestamp (not the raw Instant) so write and read use the
                    // same conversion and the instant round-trips through the plain TIMESTAMP column.
                    .bind("createdAt", Timestamp.from(now));
            // An unlinked principal (e.g. an in-memory onno.auth.users login) has no record id, so
            // _author_id is null — bind it as a typed uuid null, not varchar, or Postgres rejects
            // the insert ("uuid but expression is of type character varying"). (#171, same as #163)
            SqlBind.nullable(update, "authorId", authorUuid);
            SqlBind.nullable(update, "parentId", parentId);
            update.execute();
        });
        return new Comment(id, entityType, entityName, entityId, authorId, authorName, body, parentId, now, null);
    }

    /** A single comment by id (including soft-deleted ones), for authorization checks. */
    public Optional<Comment> find(UUID id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE + " WHERE _id = :id")
                .bind("id", id)
                .map((rs, ctx) -> {
                    UUID entityId = rs.getObject("_entity_id", UUID.class);
                    return map(id, rs.getString("_entity_type"), rs.getString("_entity_name"), entityId, rs);
                })
                .findOne());
    }

    /** Soft-delete a comment; returns false when it doesn't exist or was already deleted. */
    public boolean softDelete(UUID id) {
        return jdbi.withHandle(h -> h.createUpdate("UPDATE " + TABLE +
                        " SET _deleted = TRUE WHERE _id = :id AND _deleted = FALSE")
                .bind("id", id)
                .execute()) > 0;
    }

    /** Toggle one viewer's reaction on a comment and return whether it is now present. */
    public boolean toggleReaction(UUID commentId, String userKey, String emoji) {
        if (userKey == null || userKey.isBlank()) {
            throw new IllegalArgumentException("userKey is required");
        }
        return jdbi.inTransaction(h -> {
            int deleted = h.createUpdate("DELETE FROM " + REACTIONS_TABLE +
                            " WHERE _comment_id = :commentId AND _user_key = :userKey AND _emoji = :emoji")
                    .bind("commentId", commentId)
                    .bind("userKey", userKey)
                    .bind("emoji", emoji)
                    .execute();
            if (deleted > 0) {
                return false;
            }
            h.createUpdate("INSERT INTO " + REACTIONS_TABLE +
                            " (_comment_id, _user_key, _emoji, _created_at)" +
                            " VALUES (:commentId, :userKey, :emoji, :createdAt)")
                    .bind("commentId", commentId)
                    .bind("userKey", userKey)
                    .bind("emoji", emoji)
                    .bind("createdAt", Timestamp.from(Instant.now()))
                    .execute();
            return true;
        });
    }

    /** Grouped reactions for a batch of comments, preserving first-seen emoji order per comment. */
    public Map<UUID, List<CommentReaction>> reactionsFor(Collection<UUID> commentIds, String userKey) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = new ArrayList<>(commentIds);
        return jdbi.withHandle(h -> {
            Map<UUID, List<CommentReaction>> out = new LinkedHashMap<>();
            h.createQuery("SELECT _comment_id, _emoji, COUNT(*) AS _count," +
                        " SUM(CASE WHEN _user_key = :userKey THEN 1 ELSE 0 END) AS _mine" +
                        " FROM " + REACTIONS_TABLE +
                        " WHERE _comment_id IN (<ids>)" +
                        " GROUP BY _comment_id, _emoji" +
                        " ORDER BY MIN(_created_at), _emoji")
                .bind("userKey", userKey == null ? "" : userKey)
                .bindList("ids", ids)
                .map((rs, ctx) -> Map.entry(
                        rs.getObject("_comment_id", UUID.class),
                        new CommentReaction(rs.getString("_emoji"), rs.getInt("_count"), rs.getInt("_mine") > 0)))
                .forEach(entry -> out.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue()));
            return out;
        });
    }

    private static Comment map(UUID id, String type, String name, UUID entityId,
                               java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID authorId = rs.getObject("_author_id", UUID.class);
        Timestamp created = rs.getTimestamp("_created_at");
        Timestamp edited = rs.getTimestamp("_edited_at");
        return new Comment(id, type, name, entityId,
                authorId == null ? null : authorId.toString(),
                rs.getString("_author_name"),
                rs.getString("_body"),
                rs.getObject("_parent_id", UUID.class),
                created == null ? null : created.toInstant(),
                edited == null ? null : edited.toInstant());
    }

    public record CommentReaction(String emoji, int count, boolean mine) {
    }
}
