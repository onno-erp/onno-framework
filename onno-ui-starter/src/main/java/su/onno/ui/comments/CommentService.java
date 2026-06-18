package su.onno.ui.comments;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.List;
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
 */
public class CommentService {

    static final String TABLE = "onno_comments";

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
                    "    _created_at TIMESTAMP NOT NULL,\n" +
                    "    _edited_at TIMESTAMP,\n" +
                    "    _deleted BOOLEAN NOT NULL DEFAULT FALSE\n" +
                    ")");
            h.execute("CREATE INDEX IF NOT EXISTS onno_comments_target_idx ON " + TABLE +
                    " (_entity_type, _entity_name, _entity_id)");
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
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + TABLE +
                        " (_id, _entity_type, _entity_name, _entity_id, _author_id, _author_name," +
                        " _body, _created_at, _edited_at, _deleted)" +
                        " VALUES (:id, :type, :name, :entityId, :authorId, :authorName, :body, :createdAt, NULL, FALSE)")
                .bind("id", id)
                .bind("type", entityType)
                .bind("name", entityName)
                .bind("entityId", entityId)
                .bind("authorId", authorId == null ? null : UUID.fromString(authorId))
                .bind("authorName", authorName)
                .bind("body", body)
                .bind("createdAt", now)
                .execute());
        return new Comment(id, entityType, entityName, entityId, authorId, authorName, body, now, null);
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

    private static Comment map(UUID id, String type, String name, UUID entityId,
                               java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID authorId = rs.getObject("_author_id", UUID.class);
        java.sql.Timestamp created = rs.getTimestamp("_created_at");
        java.sql.Timestamp edited = rs.getTimestamp("_edited_at");
        return new Comment(id, type, name, entityId,
                authorId == null ? null : authorId.toString(),
                rs.getString("_author_name"),
                rs.getString("_body"),
                created == null ? null : created.toLocalDateTime(),
                edited == null ? null : edited.toLocalDateTime());
    }
}
