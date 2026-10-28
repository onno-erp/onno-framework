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

    /** Stored history, newest first; equal periods are ordered by id for stable offset pages. */
    public List<Map<String, Object>> records(AccessSubject subject, InformationRegisterDescriptor desc,
                                            LocalDateTime from, LocalDateTime to,
                                            Map<String, String> filters, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..1000 and offset nonnegative");
        }
        boolean periodic = desc.periodicity() != Periodicity.NONE;
        if ((!periodic && (from != null || to != null)) || (from != null && to != null && from.isAfter(to))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid period range for this register");
        }
        var dimensions = desc.dimensions().stream().filter(d -> !d.secret()).toList();
        var equality = dimensions.stream().filter(d -> filters.containsKey(d.fieldName()))
                .map(d -> d.columnName() + "," + filters.get(d.fieldName())).toList();
        var allowed = dimensions.stream().map(AttributeDescriptor::columnName)
                .collect(java.util.stream.Collectors.toSet());
        ListFilter.Result scoped = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName())
                .andInto(ListFilter.parse(equality, null, null, allowed));
        StringBuilder sql = new StringBuilder("SELECT * FROM " + desc.tableName() + " WHERE 1=1");
        if (from != null) sql.append(" AND _period >= :from");
        if (to != null) sql.append(" AND _period <= :to");
        if (!scoped.isEmpty()) sql.append(" AND (").append(scoped.sql()).append(")");
        sql.append(" ORDER BY ").append(periodic ? "_period DESC, " : "")
                .append("_id DESC LIMIT :limit OFFSET :offset");
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString()).bind("limit", limit).bind("offset", offset);
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            scoped.bindings().forEach(query::bind);
            return query.mapToMap().list();
        });
        EntityQuerySupport.decorateRows(refResolver, allFields(desc), rows, subject);
        return rows;
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
        ScopeClause scope = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName());
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE " + viaColumn + " = :parent" + scope.and() + " ORDER BY _id"))
                        .bind("parent", parentId)
                        .mapToMap()
                        .list());
        refResolver.resolveAttributes(rows, allFields(desc), subject);
        return rows;
    }

    private static List<AttributeDescriptor> allFields(InformationRegisterDescriptor desc) {
        List<AttributeDescriptor> all = new ArrayList<>(desc.dimensions());
        all.addAll(desc.resources());
        all.addAll(desc.attributes());
        return all;
    }
}
