package su.onno.history;

import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.query.KeysetWindow;
import su.onno.security.SecretCipher;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The record change history (field-level audit log) for catalogs and documents that opt in with
 * {@code @Catalog(history = true)} / {@code @Document(history = true)}.
 *
 * <p>Recording is storage-level and therefore identical on every write path: the writer captures a
 * {@link RecordImage} of the stored row (and its tabular sections) before the mutation with
 * {@link #capture}, then calls {@link #record} on the same transaction-bound {@link Handle} after
 * it. The recorder re-reads the row, diffs the two images and appends one row per changed value to
 * the framework-managed {@value #TABLE} table, so the history commits or rolls back with the write.
 * The generic command services do this around their JDBI writes; the starter's Spring Data
 * callbacks do it around typed {@code repository.save(...)}/{@code delete(...)}; the posting
 * engine records post/unpost inside its own posting transaction.
 *
 * <p>The table is infrastructure (like {@code onno_schema_history}): created with a plain
 * {@code CREATE TABLE IF NOT EXISTS}, append-only, never part of the metadata diff. Values are
 * stored as canonical text; {@code secret} attributes are recorded as changed <em>without</em>
 * values. An entity that has not opted in costs one cached map lookup per write.
 *
 * <p>This class does not authorize reads: {@link #list} and {@link #page} return raw entries for trusted code. The
 * UI starter's {@code /api/history} endpoint applies {@code @AccessControl}, record policies and
 * reference visibility on top.
 */
public class RecordHistory {

    public static final String TABLE = "onno_record_history";

    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    /** The deletion mark was set (catalog/document deletes are soft). */
    public static final String DELETED = "DELETED";
    /** The deletion mark was cleared. */
    public static final String RESTORED = "RESTORED";
    public static final String POSTED = "POSTED";
    public static final String UNPOSTED = "UNPOSTED";

    public static final String CATALOG = "catalog";
    public static final String DOCUMENT = "document";

    private static final RecordHistory DISABLED = new RecordHistory();

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private final SecretCipher secretCipher;
    private final HistoryActorResolver actorResolver;
    private final boolean enabled;
    private final ConcurrentHashMap<Class<?>, Optional<Target>> targets = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastStamp = new AtomicReference<>(Instant.EPOCH);

    /** What the recorder needs to know about one tracked entity. */
    private record Target(String type, String name, String table, List<AttributeDescriptor> attributes,
                          List<TabularSectionDescriptor> sections, boolean hierarchical) {
    }

    private RecordHistory() {
        this.jdbi = null;
        this.registry = null;
        this.secretCipher = null;
        this.actorResolver = null;
        this.enabled = false;
    }

    /**
     * @param secretCipher  decrypts secret columns so an unchanged secret is not reported as changed
     *                      (ciphertext differs on every encryption); may be {@code null}
     * @param actorResolver fallback actor source for threads with no {@link HistoryActors} binding;
     *                      may be {@code null}
     */
    public RecordHistory(Jdbi jdbi, MetadataRegistry registry, SecretCipher secretCipher,
                         HistoryActorResolver actorResolver) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.secretCipher = secretCipher;
        this.actorResolver = actorResolver;
        this.enabled = true;
        ensureSchema();
    }

    /** A recorder that tracks nothing — the default collaborator when history is switched off. */
    public static RecordHistory disabled() {
        return DISABLED;
    }

    private void ensureSchema() {
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n" +
                    "    _id UUID PRIMARY KEY,\n" +
                    "    _change_id UUID NOT NULL,\n" +
                    "    _seq INT NOT NULL,\n" +
                    "    _entity_type VARCHAR(32) NOT NULL,\n" +
                    "    _entity_name VARCHAR(128) NOT NULL,\n" +
                    "    _record_id UUID NOT NULL,\n" +
                    "    _event VARCHAR(32) NOT NULL,\n" +
                    "    _at TIMESTAMP NOT NULL,\n" +
                    "    _actor VARCHAR(256) NOT NULL,\n" +
                    "    _actor_id UUID,\n" +
                    "    _section VARCHAR(128),\n" +
                    "    _line INT,\n" +
                    "    _row_change VARCHAR(16),\n" +
                    "    _field VARCHAR(128),\n" +
                    "    _old_value TEXT,\n" +
                    "    _new_value TEXT,\n" +
                    "    _secret BOOLEAN NOT NULL DEFAULT FALSE\n" +
                    ")");
            h.execute("CREATE INDEX IF NOT EXISTS onno_record_history_record_idx ON " + TABLE +
                    " (_record_id, _at)");
            h.execute("CREATE INDEX IF NOT EXISTS onno_record_history_change_idx ON " + TABLE +
                    " (_change_id)");
        });
    }

    /** Whether history is recorded for {@code entity} (opted in, and the feature is enabled). */
    public boolean tracks(Class<?> entity) {
        return target(entity) != null;
    }

    private Target target(Class<?> entity) {
        if (!enabled || entity == null) {
            return null;
        }
        return targets.computeIfAbsent(entity, this::describe).orElse(null);
    }

    private Optional<Target> describe(Class<?> entity) {
        Catalog catalog = entity.getAnnotation(Catalog.class);
        if (catalog != null) {
            if (!catalog.history()) return Optional.empty();
            for (CatalogDescriptor d : registry.allCatalogs()) {
                if (d.javaClass().equals(entity)) {
                    return Optional.of(new Target(CATALOG, d.logicalName(), d.tableName(),
                            d.attributes(), d.tabularSections(), d.hierarchical()));
                }
            }
            return Optional.empty();
        }
        Document document = entity.getAnnotation(Document.class);
        if (document != null && document.history()) {
            for (DocumentDescriptor d : registry.allDocuments()) {
                if (d.javaClass().equals(entity)) {
                    return Optional.of(new Target(DOCUMENT, d.logicalName(), d.tableName(),
                            d.attributes(), d.tabularSections(), false));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The stored state of record {@code id}, read on {@code handle}; {@link RecordImage#ABSENT} when
     * the row does not exist, {@code null} when {@code entity} is not tracked (so an untracked write
     * costs no query).
     */
    public RecordImage capture(Handle handle, Class<?> entity, UUID id) {
        Target target = target(entity);
        if (target == null || id == null) {
            return null;
        }
        return capture(handle, target, id);
    }

    private RecordImage capture(Handle handle, Target target, UUID id) {
        Map<String, Object> raw = handle.createQuery("SELECT * FROM " + target.table() + " WHERE _id = :id")
                .bind("id", id)
                .mapToMap()
                .findOne()
                .orElse(null);
        if (raw == null) {
            return RecordImage.ABSENT;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (CATALOG.equals(target.type())) {
            fields.put("code", text(raw.get("_code")));
            fields.put("description", text(raw.get("_description")));
            if (target.hierarchical()) {
                fields.put("parent", text(raw.get("_parent")));
                fields.put("folder", text(raw.get("_is_folder")));
            }
        } else {
            fields.put("number", text(raw.get("_number")));
            fields.put("date", text(raw.get("_date")));
        }
        readAttributes(target.attributes(), raw, fields);

        Map<String, List<Map<String, String>>> sections = new LinkedHashMap<>();
        for (TabularSectionDescriptor section : target.sections()) {
            List<Map<String, Object>> rows = handle.createQuery("SELECT * FROM " + section.tableName() +
                            " WHERE _parent_id = :id ORDER BY _line_number")
                    .bind("id", id)
                    .mapToMap()
                    .list();
            List<Map<String, String>> images = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, String> image = new LinkedHashMap<>();
                readAttributes(section.attributes(), row, image);
                images.add(image);
            }
            sections.put(section.name(), images);
        }
        return new RecordImage(true, Boolean.TRUE.equals(raw.get("_deletion_mark")), fields, sections);
    }

    private void readAttributes(List<AttributeDescriptor> attributes, Map<String, Object> raw,
                                Map<String, String> into) {
        for (AttributeDescriptor attr : attributes) {
            Object value = raw.get(attr.columnName());
            if (value == null) {
                value = raw.get(attr.columnName().toLowerCase(java.util.Locale.ROOT));
            }
            String text = text(value);
            if (attr.secret() && text != null && secretCipher != null) {
                try {
                    text = secretCipher.decrypt(text);
                } catch (RuntimeException undecryptable) {
                    // Compare the stored form instead; the value is never written to history anyway.
                }
            }
            into.put(attr.fieldName(), text);
        }
    }

    /**
     * Record what changed on record {@code id} since {@code before} was captured. Re-reads the row on
     * {@code handle} (the write's own transaction), diffs it against {@code before} and appends the
     * event — {@link #CREATED}, {@link #UPDATED}, {@link #DELETED} or {@link #RESTORED} — with its
     * field changes. A write that changed nothing records nothing. No-op when {@code entity} is not
     * tracked or {@code before} is {@code null}.
     *
     * @param actor who made the change; {@code null} resolves the thread's actor
     */
    public void record(Handle handle, Class<?> entity, UUID id, RecordImage before, HistoryActor actor) {
        Target target = target(entity);
        if (target == null || before == null || id == null) {
            return;
        }
        RecordImage after = capture(handle, target, id);
        if (!after.exists()) {
            return;
        }
        List<HistoryChange> changes = diff(target, before, after);
        String event;
        if (!before.exists()) {
            event = CREATED;
        } else if (!before.deleted() && after.deleted()) {
            event = DELETED;
        } else if (before.deleted() && !after.deleted()) {
            event = RESTORED;
        } else if (changes.isEmpty()) {
            return;
        } else {
            event = UPDATED;
        }
        insert(handle, target, id, event, actor, changes);
    }

    /**
     * Record a bare lifecycle event (e.g. {@link #POSTED}/{@link #UNPOSTED}) on {@code handle}, so it
     * commits or rolls back with the caller's transaction. No-op when {@code entity} is not tracked.
     */
    public void recordEvent(Handle handle, Class<?> entity, UUID id, String event, HistoryActor actor) {
        Target target = target(entity);
        if (target == null || id == null) {
            return;
        }
        insert(handle, target, id, event, actor, List.of());
    }

    private List<HistoryChange> diff(Target target, RecordImage before, RecordImage after) {
        List<HistoryChange> changes = new ArrayList<>();
        Set<String> secrets = secretFields(target.attributes());
        Set<String> keys = new LinkedHashSet<>(after.fields().keySet());
        keys.addAll(before.fields().keySet());
        for (String field : keys) {
            String oldValue = before.fields().get(field);
            String newValue = after.fields().get(field);
            if (!Objects.equals(oldValue, newValue)) {
                changes.add(change(null, null, null, field, oldValue, newValue, secrets.contains(field)));
            }
        }
        for (TabularSectionDescriptor section : target.sections()) {
            Set<String> sectionSecrets = secretFields(section.attributes());
            List<Map<String, String>> oldRows = before.sections().getOrDefault(section.name(), List.of());
            List<Map<String, String>> newRows = after.sections().getOrDefault(section.name(), List.of());
            int lines = Math.max(oldRows.size(), newRows.size());
            for (int i = 0; i < lines; i++) {
                Map<String, String> oldRow = i < oldRows.size() ? oldRows.get(i) : null;
                Map<String, String> newRow = i < newRows.size() ? newRows.get(i) : null;
                int line = i + 1;
                if (oldRow != null && newRow != null) {
                    for (AttributeDescriptor attr : section.attributes()) {
                        String oldValue = oldRow.get(attr.fieldName());
                        String newValue = newRow.get(attr.fieldName());
                        if (!Objects.equals(oldValue, newValue)) {
                            changes.add(change(section.name(), line, HistoryChange.ROW_CHANGED, attr.fieldName(),
                                    oldValue, newValue, sectionSecrets.contains(attr.fieldName())));
                        }
                    }
                } else {
                    boolean added = newRow != null;
                    Map<String, String> row = added ? newRow : oldRow;
                    String rowChange = added ? HistoryChange.ROW_ADDED : HistoryChange.ROW_REMOVED;
                    int sizeBefore = changes.size();
                    for (AttributeDescriptor attr : section.attributes()) {
                        String value = row.get(attr.fieldName());
                        if (value != null) {
                            changes.add(change(section.name(), line, rowChange, attr.fieldName(),
                                    added ? null : value, added ? value : null,
                                    sectionSecrets.contains(attr.fieldName())));
                        }
                    }
                    if (changes.size() == sizeBefore) {
                        changes.add(new HistoryChange(section.name(), line, rowChange, null, null, null, false));
                    }
                }
            }
        }
        return changes;
    }

    /** Secret values never reach the table: the change is recorded with both sides blank. */
    private static HistoryChange change(String section, Integer line, String rowChange, String field,
                                        String oldValue, String newValue, boolean secret) {
        return secret
                ? new HistoryChange(section, line, rowChange, field, null, null, true)
                : new HistoryChange(section, line, rowChange, field, oldValue, newValue, false);
    }

    private static Set<String> secretFields(List<AttributeDescriptor> attributes) {
        Set<String> secrets = new LinkedHashSet<>();
        for (AttributeDescriptor attr : attributes) {
            if (attr.secret()) secrets.add(attr.fieldName());
        }
        return secrets;
    }

    private void insert(Handle handle, Target target, UUID recordId, String event, HistoryActor actor,
                        List<HistoryChange> changes) {
        HistoryActor who = actor != null ? actor : currentActor();
        UUID changeId = UUID.randomUUID();
        Timestamp at = Timestamp.from(nextStamp());
        List<HistoryChange> rows = changes.isEmpty()
                ? List.of(new HistoryChange(null, null, null, null, null, null, false))
                : changes;
        PreparedBatch batch = handle.prepareBatch("INSERT INTO " + TABLE +
                " (_id, _change_id, _seq, _entity_type, _entity_name, _record_id, _event, _at, _actor," +
                " _actor_id, _section, _line, _row_change, _field, _old_value, _new_value, _secret)" +
                " VALUES (:id, :changeId, :seq, :type, :name, :recordId, :event, :at, :actor," +
                // Explicit casts: PostgreSQL types an untyped NULL parameter as varchar otherwise.
                " CAST(:actorId AS UUID), :section, CAST(:line AS INT), :rowChange, :field, :oldValue," +
                " :newValue, :secret)");
        int seq = 0;
        for (HistoryChange change : rows) {
            batch.bind("id", UUID.randomUUID())
                    .bind("changeId", changeId)
                    .bind("seq", seq++)
                    .bind("type", target.type())
                    .bind("name", target.name())
                    .bind("recordId", recordId)
                    .bind("event", event)
                    .bind("at", at)
                    .bind("actor", who.name())
                    .bindByType("actorId", who.recordId(), UUID.class)
                    .bindByType("section", change.section(), String.class)
                    .bindByType("line", change.line(), Integer.class)
                    .bindByType("rowChange", change.rowChange(), String.class)
                    .bindByType("field", change.field(), String.class)
                    .bindByType("oldValue", change.oldValue(), String.class)
                    .bindByType("newValue", change.newValue(), String.class)
                    .bind("secret", change.secret())
                    .add();
        }
        batch.execute();
    }

    private HistoryActor currentActor() {
        HistoryActor bound = HistoryActors.current();
        if (bound != null) {
            return bound;
        }
        if (actorResolver != null) {
            try {
                HistoryActor resolved = actorResolver.resolve();
                if (resolved != null) {
                    return resolved;
                }
            } catch (RuntimeException unresolved) {
                // Attribution must never fail a write; fall back to the system actor.
            }
        }
        return HistoryActor.SYSTEM;
    }

    /** Strictly increasing microsecond stamps within this JVM, so same-instant events keep their order. */
    private Instant nextStamp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return lastStamp.updateAndGet(last -> now.isAfter(last) ? now : last.plus(1, ChronoUnit.MICROS));
    }

    /**
     * Every recorded event for record {@code recordId}, newest first, unauthorized and unredacted
     * (beyond secrets, which are never stored). History follows the record id, so it survives an
     * entity rename. For request paths, use {@link #page} instead.
     */
    public List<HistoryEntry> list(UUID recordId) {
        return KeysetWindow.drain(cursor -> page(recordId, cursor, KeysetWindow.MAX_LIMIT, "trusted"));
    }

    /**
     * One keyset window of record {@code recordId}'s events, newest first, in the shared
     * {@code {rows, nextCursor, hasMore}} shape. Events are ordered by {@code (at, change id)}, so
     * two events stamped at the same instant (another node, a clock tie) are neither skipped nor
     * repeated across windows.
     *
     * @param cursor  the previous window's {@code nextCursor}, or {@code null} for the newest
     * @param limit   the window size, clamped to {@link KeysetWindow#MAX_LIMIT}
     * @param context the caller's access-scope fingerprint; a cursor minted for another record or
     *                scope is rejected with {@link su.onno.query.Cursor.ScopeMismatchException}
     */
    public KeysetWindow.Page<HistoryEntry> page(UUID recordId, String cursor, int limit, String context) {
        if (!enabled || recordId == null) {
            return new KeysetWindow.Page<>(List.of(), null, false);
        }
        return jdbi.withHandle(h -> {
            KeysetWindow.Page<UUID> ids = KeysetWindow.fetch(h,
                    "(SELECT _change_id, MAX(_at) AS _at FROM " + TABLE
                            + " WHERE _record_id = :id GROUP BY _change_id) changes",
                    "_change_id, _at", "1 = 1", q -> q.bind("id", recordId),
                    List.of(KeysetWindow.Order.notNull("_at", true), KeysetWindow.Order.notNull("_change_id", true)),
                    cursor, limit, "history:" + recordId + ":" + context,
                    (rs, ctx) -> rs.getObject("_change_id", UUID.class));
            return new KeysetWindow.Page<>(load(h, ids.rows()), ids.nextCursor(), ids.hasMore());
        });
    }

    /** The full entries for {@code changeIds}, in that order. */
    private static List<HistoryEntry> load(Handle h, List<UUID> changeIds) {
        if (changeIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, HistoryEntry> entries = new LinkedHashMap<>();
        for (UUID changeId : changeIds) {
            entries.put(changeId, null);
        }
        h.createQuery("SELECT * FROM " + TABLE + " WHERE _change_id IN (<ids>) ORDER BY _seq")
                .bindList("ids", changeIds)
                .map((rs, ctx) -> {
                    UUID changeId = rs.getObject("_change_id", UUID.class);
                    HistoryEntry entry = entries.get(changeId);
                    if (entry == null) {
                        entry = new HistoryEntry(changeId, rs.getString("_entity_type"),
                                rs.getString("_entity_name"), rs.getObject("_record_id", UUID.class),
                                rs.getString("_event"), rs.getTimestamp("_at").toInstant(),
                                rs.getString("_actor"), rs.getObject("_actor_id", UUID.class),
                                new ArrayList<>());
                        entries.put(changeId, entry);
                    }
                    String field = rs.getString("_field");
                    String section = rs.getString("_section");
                    if (field != null || section != null) {
                        int line = rs.getInt("_line");
                        entry.changes().add(new HistoryChange(section, rs.wasNull() ? null : line,
                                rs.getString("_row_change"), field, rs.getString("_old_value"),
                                rs.getString("_new_value"), rs.getBoolean("_secret")));
                    }
                    return changeId;
                })
                .list();
        return entries.values().stream().filter(Objects::nonNull).toList();
    }

    /** Canonical text for a stored value, so equal values compare equal across drivers. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException unreadable) {
                return null;
            }
        }
        if (value instanceof byte[] bytes) {
            return "(" + bytes.length + " bytes)";
        }
        return value.toString();
    }
}
