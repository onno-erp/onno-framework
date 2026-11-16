package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.Periodicity;

import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side queries for information registers, used to drive related-list panels backed by a
 * register junction (see {@link RelatedList}, {@link Junctions}). Entity-level RBAC stays with the
 * callers; the subject's record scope (by dimension) is applied here. Read-only by design for now: a
 * register-backed relationship
 * renders both-direction panels but isn't edited inline (info registers have no generic write
 * REST surface yet).
 */
public class InformationRegisterQueryService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;
    private final RecordScopeCompiler scopes;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new UiAccessService(registry));
    }

    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                                           UiAccessService access) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.refResolver = new RefResolver(registry, jdbi, scopes, access);
    }

    public InformationRegisterDescriptor require(String name) {
        return registry.allInformationRegisters().stream()
                .filter(d -> d.logicalName().equalsIgnoreCase(name))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Information register not found: " + name));
    }

    /**
     * One keyset window of stored records, newest first: by {@code _period} then {@code _id} for a
     * periodic register, by {@code _id} for a non-periodic one. The cursor is sealed to the caller's
     * record-access fingerprint like every other feed. {@code filters} must name non-secret
     * dimensions by field name; anything else is rejected rather than silently widening the read.
     */
    public KeysetPage records(AccessSubject subject, InformationRegisterDescriptor desc,
                              LocalDateTime from, LocalDateTime to, Map<String, String> filters,
                              String cursor, int limit) {
        boolean periodic = desc.periodicity() != Periodicity.NONE;
        if ((!periodic && (from != null || to != null)) || (from != null && to != null && from.isAfter(to))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid period range for this register");
        }
        var dimensions = desc.dimensions().stream().filter(d -> !d.secret()).toList();
        var known = dimensions.stream().map(AttributeDescriptor::fieldName)
                .collect(java.util.stream.Collectors.toSet());
        var unknown = filters.keySet().stream().filter(k -> !known.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown filter " + String.join(", ", unknown) + "; filter by dimension field names: "
                            + known.stream().sorted().collect(java.util.stream.Collectors.joining(", ")));
        }
        var equality = dimensions.stream().filter(d -> filters.containsKey(d.fieldName()))
                .map(d -> d.columnName() + "," + filters.get(d.fieldName())).toList();
        var allowed = dimensions.stream().map(AttributeDescriptor::columnName)
                .collect(java.util.stream.Collectors.toSet());
        ListFilter.Result scoped = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName())
                .andInto(ListFilter.parse(equality, null, null, allowed));
        StringBuilder where = new StringBuilder("1=1");
        if (from != null) where.append(" AND _period >= :from");
        if (to != null) where.append(" AND _period <= :to");
        if (!scoped.isEmpty()) where.append(" AND (").append(scoped.sql()).append(")");

        KeysetWindow.Raw window = KeysetWindow.fetch(jdbi, desc.tableName(), where.toString(), q -> {
                    if (from != null) q.bind("from", from);
                    if (to != null) q.bind("to", to);
                    scoped.bindings().forEach(q::bind);
                }, periodic ? "_period" : "_id", true, false,
                new KeysetWindow.Position(cursor, null, null), limit,
                scopes.policies().fingerprint(subject));
        EntityQuerySupport.decorateRows(refResolver, allFields(desc), window.rows(), subject);
        return window.decorated();
    }

    /**
     * Register rows whose {@code viaColumn} ref dimension points at {@code parentId} — the read
     * side of a register-backed related-list panel. Ordered by {@code _id} for a stable roster.
     * Refs are resolved (so the {@code display} dimension shows its description), exactly like the
     * catalog join read. {@code viaColumn} must be a real column on {@code desc} (the caller
     * resolves it from the register's scanned dimensions, never from user input) so this stays
     * injection-safe. Information registers carry no soft-delete flag, so every stored row counts.
     */
    public List<Map<String, Object>> relatedRows(AccessSubject subject, InformationRegisterDescriptor desc,
                                                  String viaColumn, UUID parentId) {
        return relatedPage(subject, desc, viaColumn, parentId, null, 100).rows();
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> relatedPage(AccessSubject subject,
            InformationRegisterDescriptor desc, String viaColumn, UUID parentId, String cursor, int limit) {
        ScopeClause scope = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName());
        var page = KeysetWindow.collection(jdbi, desc.tableName(), "*", viaColumn + " = :parent" + scope.and(),
                q -> { scope.bind(q); q.bind("parent", parentId); },
                List.of(new su.onno.query.KeysetWindow.Order("_id", false)), cursor, limit,
                "related-info:" + desc.tableName() + ":" + viaColumn + ":" + parentId + ":" + scopes.policies().fingerprint(subject) + ":" + EntityQuerySupport.cursorSubject(subject));
        refResolver.resolveAttributes(page.rows(), allFields(desc), subject);
        return page;
    }

    private static List<AttributeDescriptor> allFields(InformationRegisterDescriptor desc) {
        List<AttributeDescriptor> all = new ArrayList<>(desc.dimensions());
        all.addAll(desc.resources());
        all.addAll(desc.attributes());
        return all;
    }
}
