package su.onno.web.comments;

import org.jdbi.v3.core.Jdbi;
import org.springframework.context.ApplicationEventPublisher;

import su.onno.access.AccessSubject;
import su.onno.events.EntityChangedEvent;
import su.onno.web.CurrentUserResolver.CurrentUser;
import su.onno.web.JdbiCommitCallbacks;
import su.onno.web.SqlBind;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private static final String COMMENT_ENTITY_TYPE = "comment";

    private final Jdbi jdbi;
    private final ApplicationEventPublisher events;
    private final CommentProperties properties;
    private final MentionResolver mentions;

    public CommentService(Jdbi jdbi) {
        this(jdbi, event -> {});
    }

    public CommentService(Jdbi jdbi, ApplicationEventPublisher events) {
        this(jdbi, events, new CommentProperties(), null);
    }

    public CommentService(Jdbi jdbi, ApplicationEventPublisher events,
                          CommentProperties properties, MentionResolver mentions) {
        this.jdbi = jdbi;
        this.events = events;
        this.properties = properties;
        this.mentions = mentions;
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
            h.execute("CREATE INDEX IF NOT EXISTS onno_comments_feed_idx ON " + TABLE +
                    " (_entity_type, _entity_name, _entity_id, _created_at, _id)");
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
        return su.onno.query.KeysetWindow.drain(c -> page(entityType, entityName, entityId, c, su.onno.query.KeysetWindow.MAX_LIMIT, "trusted"));
    }

    public su.onno.query.KeysetWindow.Page<Comment> page(String type, String name, UUID id,
            String cursor, int limit, String fingerprint) {
        try {
            return jdbi.withHandle(h -> su.onno.query.KeysetWindow.fetch(h, TABLE, "*",
                    "_entity_type = :type AND _entity_name = :name AND _entity_id = :id AND _deleted = false",
                    q -> q.bind("type", type).bind("name", name).bind("id", id),
                    List.of(su.onno.query.KeysetWindow.Order.notNull("_created_at", false),
                            su.onno.query.KeysetWindow.Order.notNull("_id", false)), cursor, limit,
                    "comments:" + type + ":" + name + ":" + id + ":" + fingerprint,
                    (rs, ctx) -> map(rs.getObject("_id", UUID.class), type, name, id, rs)));
        } catch (su.onno.query.Cursor.ScopeMismatchException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Cursor belongs to another thread or access scope", e);
        }
    }

    /** Append a comment and return it as stored. */
    public Comment add(String entityType, String entityName, UUID entityId,
                       String authorId, String authorName, String body) {
        return add(entityType, entityName, entityId, authorId, authorName, body, null);
    }

    /** Append a top-level comment or a reply and return it as stored. */
    public Comment add(String entityType, String entityName, UUID entityId,
                       String authorId, String authorName, String body, UUID parentId) {
        CurrentUser actor = new CurrentUser(null, authorName, authorId, null, null);
        return add(entityType, entityName, entityId, actor, body, parentId);
    }

    /**
     * Append a comment from trusted application code. Mentions are validated as
     * {@link AccessSubject#system()}, so they are kept as written (no request user to scope them to);
     * request paths must pass the caller's {@link AccessSubject} to the overload below.
     */
    public Comment add(String entityType, String entityName, UUID entityId,
                       CurrentUser actor, String body, UUID parentId) {
        return add(entityType, entityName, entityId, actor, body, parentId, AccessSubject.system());
    }

    /** Append a comment using the author's access subject to validate mentions. */
    public Comment add(String entityType, String entityName, UUID entityId,
                       CurrentUser actor, String body, UUID parentId, AccessSubject subject) {
        if (body == null || body.isBlank()) {
            throw new InvalidCommentException("Comment cannot be empty");
        }
        String text = body.strip();
        if (text.length() > properties.getMaxLength()) {
            throw new InvalidCommentException(
                    "Comment must be at most " + properties.getMaxLength() + " characters");
        }
        boolean resolveMentions = mentions != null && properties.getMentions().isEnabled();
        String savedBody = resolveMentions
                ? Mentions.degrade(text, ref -> !mentions.canRead(subject, ref))
                : text;
        List<MentionRef> readableMentions = resolveMentions
                ? mentions.resolve(Mentions.parse(savedBody, '@'), subject).stream()
                        .filter(MentionResolver.ResolvedMention::readable)
                        .map(ref -> new MentionRef(ref.kind(), ref.name(), ref.id()))
                        .toList()
                : List.of();
        Comment parent = findReplyTarget(parentId, entityType, entityName, entityId);
        String authorId = actor.recordId();
        String authorName = actor.displayName();
        UUID id = UUID.randomUUID();
        // TIMESTAMP keeps microseconds and rounds finer values, so truncate first: the returned
        // createdAt then equals what a read-back sees (Linux clocks tick in nanoseconds).
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID authorUuid = authorId == null ? null : UUID.fromString(authorId);
        Comment saved = new Comment(
                id, entityType, entityName, entityId, authorId, authorName, savedBody, parentId, now, null);
        Runnable publish = () -> publishAdded(saved, parent, actor, readableMentions);
        boolean deferred = jdbi.withHandle(h -> {
            var update = h.createUpdate("INSERT INTO " + TABLE +
                            " (_id, _entity_type, _entity_name, _entity_id, _author_id, _author_name," +
                            " _body, _parent_id, _created_at, _edited_at, _deleted)" +
                            " VALUES (:id, :type, :name, :entityId, :authorId, :authorName, :body, :parentId, :createdAt, NULL, FALSE)")
                    .bind("id", id)
                    .bind("type", entityType)
                    .bind("name", entityName)
                    .bind("entityId", entityId)
                    .bind("authorName", authorName)
                    .bind("body", savedBody)
                    // Bind through java.sql.Timestamp (not the raw Instant) so write and read use the
                    // same conversion and the instant round-trips through the plain TIMESTAMP column.
                    .bind("createdAt", Timestamp.from(now));
            // An unlinked principal (e.g. an in-memory onno.auth.users login) has no record id, so
            // _author_id is null — bind it as a typed uuid null, not varchar, or Postgres rejects
            // the insert ("uuid but expression is of type character varying"). (#171, same as #163)
            SqlBind.nullable(update, "authorId", authorUuid);
            SqlBind.nullable(update, "parentId", parentId);
            update.execute();
            return JdbiCommitCallbacks.defer(h, publish);
        });
        if (!deferred) {
            publish.run();
        }
        return saved;
    }

    private void publishAdded(Comment saved, Comment parent, CurrentUser actor, List<MentionRef> readableMentions) {
        publishChange(EntityChangedEvent.CREATED, saved);
        for (MentionRef ref : readableMentions) {
            events.publishEvent(new EntityMentionedEvent(saved, ref, actor));
        }
        if (parent != null) {
            events.publishEvent(new CommentRepliedEvent(saved, parent, actor));
        }
    }

    private Comment findReplyTarget(UUID parentId, String entityType, String entityName, UUID entityId) {
        if (parentId == null) {
            return null;
        }
        Comment parent = find(parentId)
                .orElseThrow(() -> new InvalidCommentException("Reply target does not exist"));
        if (parent.parentId() != null
                || !parent.entityType().equals(entityType)
                || !parent.entityName().equals(entityName)
                || !parent.entityId().equals(entityId)) {
            throw new InvalidCommentException("Reply target is not in this thread");
        }
        return parent;
    }

    public static final class InvalidCommentException extends IllegalArgumentException {
        InvalidCommentException(String message) {
            super(message);
        }
    }

    private void publishChange(String type, Comment comment) {
        events.publishEvent(new EntityChangedEvent(
                type, COMMENT_ENTITY_TYPE, comment.entityName(), comment.entityId(), null));
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
        return find(id).map(this::softDelete).orElse(false);
    }

    boolean softDelete(Comment comment) {
        Runnable publish = () -> publishChange(EntityChangedEvent.DELETED, comment);
        boolean deleted = jdbi.withHandle(h -> {
            int rows = h.createUpdate("UPDATE " + TABLE +
                            " SET _deleted = TRUE WHERE _id = :id AND _deleted = FALSE")
                    .bind("id", comment.id())
                    .execute();
            if (rows > 0 && !JdbiCommitCallbacks.defer(h, publish)) {
                publish.run();
            }
            return rows > 0;
        });
        return deleted;
    }

    /** Toggle one viewer's reaction on a comment and return whether it is now present. */
    public boolean toggleReaction(UUID commentId, String userKey, String emoji) {
        return toggleReaction(commentId, userKey, emoji, null);
    }

    void toggleReaction(Comment comment, String userKey, String emoji) {
        toggleReaction(comment.id(), userKey, emoji, comment);
    }

    private boolean toggleReaction(UUID commentId, String userKey, String emoji, Comment comment) {
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
            boolean present = deleted == 0;
            if (present) {
                h.createUpdate("INSERT INTO " + REACTIONS_TABLE +
                                " (_comment_id, _user_key, _emoji, _created_at)" +
                                " VALUES (:commentId, :userKey, :emoji, :createdAt)")
                        .bind("commentId", commentId)
                        .bind("userKey", userKey)
                        .bind("emoji", emoji)
                        .bind("createdAt", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)))
                        .execute();
            }
            Optional.ofNullable(comment).or(() -> find(commentId)).ifPresent(target -> {
                Runnable publish = () -> publishChange(EntityChangedEvent.UPDATED, target);
                if (!JdbiCommitCallbacks.defer(h, publish)) {
                    publish.run();
                }
            });
            return present;
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
