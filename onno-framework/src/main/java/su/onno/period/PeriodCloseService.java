package su.onno.period;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Period close ("change-prohibition date"): documents dated on or before a scope's close date
 * cannot be created, changed, delete-marked, posted, reposted or unposted.
 *
 * <p>A close date is set per {@link PeriodScope}: globally, for one bounded context, or for one
 * accumulation register. Several scopes may apply to a document; the latest close date wins. The
 * posting engine, the repository save path and the generic UI/REST/import/MCP command path all
 * consult this service, so the rule holds below controllers. Chronological register restoration
 * checks every later document it would repost, so a backdated change cannot cascade into a period
 * closed for another context or register.
 *
 * <p>A document type annotated {@link PeriodCloseExempt} is never checked.
 *
 * <p>Closing and reopening are audited in {@code onno_period_close_audit}; a reopen requires a
 * reason. An authorized actor may change a closed period without reopening it through an
 * {@linkplain #override(PeriodOverride, Supplier) override}, which is audited per bypassed
 * operation.
 *
 * <p>Close dates are read from the database on every guarded operation, so a close made on one
 * node applies on all of them. The check runs inside the posting transaction but does not lock
 * the close row: an operation already past its check when a close commits still completes.
 */
public class PeriodCloseService {

    private static final String LOCKS = "onno_period_close";
    private static final String AUDIT = "onno_period_close_audit";
    private static final ThreadLocal<PeriodOverride> OVERRIDE = new ThreadLocal<>();

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private volatile PeriodCloseOverridePolicy overridePolicy = Optional::empty;

    public PeriodCloseService(Jdbi jdbi, MetadataRegistry registry) {
        this.jdbi = jdbi;
        this.registry = registry;
    }

    /** Sets the policy that grants the current caller an override; {@code null} grants none. */
    public void setOverridePolicy(PeriodCloseOverridePolicy overridePolicy) {
        this.overridePolicy = overridePolicy == null ? Optional::empty : overridePolicy;
    }

    /** Creates the framework-owned close and audit tables when they do not exist yet. */
    public void ensureSchema() {
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE IF NOT EXISTS " + LOCKS + " ("
                    + "_scope_kind VARCHAR(16) NOT NULL, _scope_key VARCHAR(255) NOT NULL, "
                    + "_closed_through DATE NOT NULL, _updated_at TIMESTAMP NOT NULL, "
                    + "_updated_by VARCHAR(255), PRIMARY KEY (_scope_kind, _scope_key))");
            handle.execute("CREATE TABLE IF NOT EXISTS " + AUDIT + " ("
                    + "_id UUID PRIMARY KEY, _at TIMESTAMP NOT NULL, _action VARCHAR(16) NOT NULL, "
                    + "_scope_kind VARCHAR(16) NOT NULL, _scope_key VARCHAR(255) NOT NULL, "
                    + "_previous DATE, _closed_through DATE, _actor VARCHAR(255), "
                    + "_reason VARCHAR(2000), _operation VARCHAR(16), _document_type VARCHAR(255), "
                    + "_document_id UUID, _document_date TIMESTAMP)");
            handle.execute("CREATE INDEX IF NOT EXISTS idx_" + AUDIT + "_at ON " + AUDIT + " (_at)");
        });
    }

    // ---------------------------------------------------------------------------------------
    // Administration
    // ---------------------------------------------------------------------------------------

    /** Every close currently in force, global first. */
    public List<PeriodLock> locks() {
        return jdbi.withHandle(this::loadLocks);
    }

    /** The close date of exactly {@code scope}, or empty when that scope is open. */
    public Optional<LocalDate> closedThrough(PeriodScope scope) {
        return locks().stream()
                .filter(lock -> lock.scope().equals(scope))
                .map(PeriodLock::closedThrough)
                .findFirst();
    }

    /**
     * Closes {@code scope} through {@code closedThrough} (inclusive). Moving an existing close
     * date back is a {@link #reopen}, not a close, and is rejected here.
     *
     * @param actor  who closes the period, for the audit trail
     * @param reason an optional note
     */
    public PeriodLock close(PeriodScope scope, LocalDate closedThrough, String actor, String reason) {
        if (closedThrough == null) {
            throw new IllegalArgumentException("A close date is required");
        }
        requireKnown(scope);
        return jdbi.inTransaction(handle -> {
            LocalDate previous = currentClose(handle, scope);
            if (previous != null && closedThrough.isBefore(previous)) {
                throw new IllegalArgumentException("Period for " + scope.describe()
                        + " is already closed through " + previous
                        + "; moving the close date back to " + closedThrough + " is a reopen and needs a reason");
            }
            LocalDateTime now = LocalDateTime.now();
            store(handle, scope, previous, closedThrough, now, actor);
            audit(handle, now, PeriodCloseAction.CLOSE, scope, previous, closedThrough, actor, reason,
                    null, null, null, null);
            return new PeriodLock(scope, closedThrough, now, actor);
        });
    }

    /**
     * Reopens {@code scope}: moves its close date back to {@code closedThrough}, or removes the
     * close entirely when {@code closedThrough} is {@code null}.
     *
     * @param actor  who reopens the period, for the audit trail
     * @param reason why — required
     * @return the remaining close, or empty when the scope is now fully open
     */
    public Optional<PeriodLock> reopen(PeriodScope scope, LocalDate closedThrough, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reopening a period requires a reason");
        }
        return jdbi.inTransaction(handle -> {
            LocalDate previous = currentClose(handle, scope);
            if (previous == null) {
                throw new IllegalArgumentException("Period for " + scope.describe() + " is not closed");
            }
            if (closedThrough != null && !closedThrough.isBefore(previous)) {
                throw new IllegalArgumentException("Period for " + scope.describe()
                        + " is closed through " + previous + "; a reopen must move the close date back");
            }
            LocalDateTime now = LocalDateTime.now();
            if (closedThrough == null) {
                handle.createUpdate("DELETE FROM " + LOCKS
                                + " WHERE _scope_kind = :kind AND _scope_key = :key")
                        .bind("kind", scope.kind().name())
                        .bind("key", scope.key())
                        .execute();
            } else {
                store(handle, scope, previous, closedThrough, now, actor);
            }
            audit(handle, now, PeriodCloseAction.REOPEN, scope, previous, closedThrough, actor,
                    reason.trim(), null, null, null, null);
            return closedThrough == null
                    ? Optional.<PeriodLock>empty()
                    : Optional.of(new PeriodLock(scope, closedThrough, now, actor));
        });
    }

    /** The most recent audit entries (closes, reopens, overrides), newest first. */
    public List<PeriodCloseAuditEntry> audit(int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT * FROM " + AUDIT + " ORDER BY _at DESC, _id LIMIT " + capped)
                .map((rs, ctx) -> new PeriodCloseAuditEntry(
                        rs.getObject("_id", UUID.class),
                        dateTime(rs, "_at"),
                        PeriodCloseAction.valueOf(rs.getString("_action")),
                        new PeriodScope(PeriodScope.Kind.valueOf(rs.getString("_scope_kind")),
                                rs.getString("_scope_key")),
                        rs.getObject("_previous", LocalDate.class),
                        rs.getObject("_closed_through", LocalDate.class),
                        rs.getString("_actor"),
                        rs.getString("_reason"),
                        rs.getString("_operation") == null
                                ? null : PeriodOperation.valueOf(rs.getString("_operation")),
                        rs.getString("_document_type"),
                        rs.getObject("_document_id", UUID.class),
                        dateTime(rs, "_document_date")))
                .list());
    }

    /** The bounded contexts declared by documents and accumulation registers, sorted. */
    public Set<String> knownContexts() {
        Set<String> contexts = new TreeSet<>();
        for (DocumentDescriptor document : registry.allDocuments()) {
            if (hasText(document.context())) contexts.add(document.context());
        }
        for (AccumulationRegisterDescriptor register : registry.allRegisters()) {
            if (hasText(register.context())) contexts.add(register.context());
        }
        return contexts;
    }

    /** The logical names of the accumulation registers, sorted. */
    public Set<String> knownRegisters() {
        Set<String> names = new TreeSet<>();
        for (AccumulationRegisterDescriptor register : registry.allRegisters()) {
            names.add(register.logicalName());
        }
        return names;
    }

    // ---------------------------------------------------------------------------------------
    // Override
    // ---------------------------------------------------------------------------------------

    /**
     * Runs {@code action} with permission to change closed periods on the current thread. Each
     * operation that would otherwise be rejected is audited with the override's actor and reason.
     */
    public <T> T override(PeriodOverride override, Supplier<T> action) {
        PeriodOverride previous = OVERRIDE.get();
        OVERRIDE.set(override);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }

    /** {@link #override(PeriodOverride, Supplier)} for an action without a result. */
    public void override(PeriodOverride override, Runnable action) {
        override(override, () -> {
            action.run();
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------
    // Enforcement
    // ---------------------------------------------------------------------------------------

    /**
     * Rejects creating, changing or delete-marking a document inside a closed period. Both the
     * stored date and {@code date} (the date being written) are checked, so a document can be
     * moved neither out of nor into a closed period. Global and document-context closes apply;
     * register closes guard posting.
     *
     * @param id   the document id; a stored row, if any, contributes its date
     * @param date the date being written, or {@code null} when the date is not changing
     * @throws PeriodClosedException when a close covers the document and the caller holds no override
     */
    public void requireDocumentOpen(DocumentDescriptor descriptor, UUID id, LocalDateTime date,
                                    PeriodOperation operation) {
        jdbi.useHandle(handle -> check(handle, descriptor, id, date, null, operation, true));
    }

    /**
     * Rejects posting, reposting or unposting a document inside a closed period, on the posting
     * transaction's {@code handle}. Global closes, closes of the document's context, and closes of
     * every register the document moves — {@code staged} plus the registers holding its active
     * movements, and their contexts — apply.
     *
     * @param date   the date of the document instance being posted, or {@code null} to use only
     *               the stored date
     * @param staged the registers the pending posting writes to
     * @param audit  whether a granted override is written to the audit trail ({@code false} for a
     *               preview, which changes nothing)
     * @throws PeriodClosedException when a close covers the document and the caller holds no override
     */
    public void requirePostingOpen(Handle handle, DocumentDescriptor descriptor, UUID id, LocalDateTime date,
                                   Collection<AccumulationRegisterDescriptor> staged,
                                   PeriodOperation operation, boolean audit) {
        check(handle, descriptor, id, date, staged == null ? List.of() : staged, operation, audit);
    }

    /**
     * @param staged {@code null} for a plain document write (registers are not considered), else
     *               the registers a posting stages
     */
    private void check(Handle handle, DocumentDescriptor descriptor, UUID id, LocalDateTime date,
                       Collection<AccumulationRegisterDescriptor> staged, PeriodOperation operation,
                       boolean audit) {
        if (descriptor.javaClass() != null
                && descriptor.javaClass().isAnnotationPresent(PeriodCloseExempt.class)) {
            return;
        }
        List<PeriodLock> locks = loadLocks(handle);
        if (locks.isEmpty()) return;

        List<LocalDateTime> dates = new ArrayList<>(2);
        if (date != null) dates.add(date);
        if (id != null && operation != PeriodOperation.CREATE) {
            handle.createQuery("SELECT _date FROM " + descriptor.tableName() + " WHERE _id = :id")
                    .bind("id", id)
                    .map((rs, ctx) -> dateTime(rs, "_date"))
                    .findOne()
                    .ifPresent(stored -> {
                        if (stored != null) dates.add(stored);
                    });
        }
        if (dates.isEmpty()) return;

        boolean scoped = locks.stream().anyMatch(lock -> lock.scope().kind() != PeriodScope.Kind.GLOBAL);
        Collection<AccumulationRegisterDescriptor> registers =
                scoped && staged != null ? touchedRegisters(handle, id, staged) : List.of();

        PeriodLock violated = null;
        LocalDateTime violatingDate = null;
        for (PeriodLock lock : locks) {
            if (!applies(lock.scope(), descriptor, registers)) continue;
            if (violated != null && !lock.closedThrough().isAfter(violated.closedThrough())) continue;
            for (LocalDateTime candidate : dates) {
                if (lock.covers(candidate)) {
                    violated = lock;
                    violatingDate = candidate;
                    break;
                }
            }
        }
        if (violated == null) return;

        PeriodOverride override = currentOverride();
        if (override == null) {
            throw new PeriodClosedException(violated, operation, descriptor.logicalName(), id, violatingDate);
        }
        if (audit) {
            audit(handle, LocalDateTime.now(), PeriodCloseAction.OVERRIDE, violated.scope(), null,
                    violated.closedThrough(), override.actor(), override.reason(), operation,
                    descriptor.logicalName(), id, violatingDate);
        }
    }

    private PeriodOverride currentOverride() {
        PeriodOverride explicit = OVERRIDE.get();
        if (explicit != null) return explicit;
        return overridePolicy.current().orElse(null);
    }

    private static boolean applies(PeriodScope scope, DocumentDescriptor document,
                                   Collection<AccumulationRegisterDescriptor> registers) {
        return switch (scope.kind()) {
            case GLOBAL -> true;
            case CONTEXT -> scope.key().equalsIgnoreCase(document.context())
                    || registers.stream().anyMatch(r -> scope.key().equalsIgnoreCase(r.context()));
            case REGISTER -> registers.stream().anyMatch(r -> scope.key().equalsIgnoreCase(r.logicalName()));
        };
    }

    /** The staged registers plus every register that holds active movements of the document. */
    private Collection<AccumulationRegisterDescriptor> touchedRegisters(
            Handle handle, UUID documentId, Collection<AccumulationRegisterDescriptor> staged) {
        Set<AccumulationRegisterDescriptor> touched = new LinkedHashSet<>(staged);
        if (documentId == null) return touched;
        for (AccumulationRegisterDescriptor register : registry.allRegisters()) {
            if (touched.contains(register)) continue;
            boolean moved = handle.createQuery("SELECT COUNT(*) FROM " + register.tableName()
                            + " WHERE _document_ref = :id AND _active = TRUE")
                    .bind("id", documentId)
                    .mapTo(Integer.class)
                    .one() > 0;
            if (moved) touched.add(register);
        }
        return touched;
    }

    // ---------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------

    private List<PeriodLock> loadLocks(Handle handle) {
        List<PeriodLock> locks = handle.createQuery("SELECT * FROM " + LOCKS)
                .map((rs, ctx) -> new PeriodLock(
                        new PeriodScope(PeriodScope.Kind.valueOf(rs.getString("_scope_kind")),
                                rs.getString("_scope_key")),
                        rs.getObject("_closed_through", LocalDate.class),
                        dateTime(rs, "_updated_at"),
                        rs.getString("_updated_by")))
                .list();
        List<PeriodLock> sorted = new ArrayList<>(locks);
        sorted.sort(Comparator.comparing((PeriodLock lock) -> lock.scope().kind())
                .thenComparing(lock -> lock.scope().key()));
        return sorted;
    }

    private static LocalDate currentClose(Handle handle, PeriodScope scope) {
        return handle.createQuery("SELECT _closed_through FROM " + LOCKS
                        + " WHERE _scope_kind = :kind AND _scope_key = :key")
                .bind("kind", scope.kind().name())
                .bind("key", scope.key())
                .map((rs, ctx) -> rs.getObject("_closed_through", LocalDate.class))
                .findOne()
                .orElse(null);
    }

    private static void store(Handle handle, PeriodScope scope, LocalDate previous, LocalDate closedThrough,
                              LocalDateTime now, String actor) {
        String sql = previous == null
                ? "INSERT INTO " + LOCKS + " (_scope_kind, _scope_key, _closed_through, _updated_at, _updated_by)"
                        + " VALUES (:kind, :key, :closed, :now, :actor)"
                : "UPDATE " + LOCKS + " SET _closed_through = :closed, _updated_at = :now, _updated_by = :actor"
                        + " WHERE _scope_kind = :kind AND _scope_key = :key";
        handle.createUpdate(sql)
                .bind("kind", scope.kind().name())
                .bind("key", scope.key())
                .bind("closed", closedThrough)
                .bind("now", now)
                .bind("actor", actor)
                .execute();
    }

    private static void audit(Handle handle, LocalDateTime at, PeriodCloseAction action, PeriodScope scope,
                              LocalDate previous, LocalDate closedThrough, String actor, String reason,
                              PeriodOperation operation, String documentType, UUID documentId,
                              LocalDateTime documentDate) {
        handle.createUpdate("INSERT INTO " + AUDIT + " (_id, _at, _action, _scope_kind, _scope_key, _previous,"
                        + " _closed_through, _actor, _reason, _operation, _document_type, _document_id,"
                        + " _document_date) VALUES (:id, :at, :action, :kind, :key, :previous, :closed, :actor,"
                        + " :reason, :operation, :documentType, :documentId, :documentDate)")
                .bind("id", UUID.randomUUID())
                .bind("at", at)
                .bind("action", action.name())
                .bind("kind", scope.kind().name())
                .bind("key", scope.key())
                .bindByType("previous", previous, LocalDate.class)
                .bindByType("closed", closedThrough, LocalDate.class)
                .bindByType("actor", actor, String.class)
                .bindByType("reason", truncate(reason), String.class)
                .bindByType("operation", operation == null ? null : operation.name(), String.class)
                .bindByType("documentType", documentType, String.class)
                .bindByType("documentId", documentId, UUID.class)
                .bindByType("documentDate", documentDate, LocalDateTime.class)
                .execute();
    }

    private void requireKnown(PeriodScope scope) {
        switch (scope.kind()) {
            case GLOBAL -> { }
            case CONTEXT -> {
                if (knownContexts().stream().noneMatch(scope.key()::equalsIgnoreCase)) {
                    throw new IllegalArgumentException("Unknown context '" + scope.key()
                            + "'; known contexts: " + knownContexts());
                }
            }
            case REGISTER -> {
                if (knownRegisters().stream().noneMatch(scope.key()::equalsIgnoreCase)) {
                    throw new IllegalArgumentException("Unknown accumulation register '" + scope.key()
                            + "'; known registers: " + knownRegisters());
                }
            }
        }
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String truncate(String reason) {
        if (reason == null) return null;
        return reason.length() > 2000 ? reason.substring(0, 2000) : reason;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
