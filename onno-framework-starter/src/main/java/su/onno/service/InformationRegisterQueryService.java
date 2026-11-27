package su.onno.service;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.Periodicity;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side queries for information registers. Entity-level RBAC stays with the callers; the
 * subject's record scope (by dimension) is applied here. Read-only: information registers have no
 * generic write REST surface.
 */
public class InformationRegisterQueryService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;
    private final RecordScopeCompiler scopes;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new AccessService(registry));
    }

    public InformationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                                           AccessService access) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.refResolver = new RefResolver(registry, jdbi, scopes, access);
    }

    public InformationRegisterDescriptor require(String name) {
        return registry.allInformationRegisters().stream()
                .filter(d -> d.logicalName().equalsIgnoreCase(name))
                .findFirst().orElseThrow(() -> new ServiceException(ServiceException.Status.NOT_FOUND,
                        "Information register not found: " + name));
    }

    /**
     * One keyset window of stored records, newest first: by {@code _period} then {@code _id} for a
     * periodic register, by {@code _id} for a non-periodic one. The cursor is sealed to the caller's
     * record-access fingerprint like every other feed. {@code where} (see {@link #whereSchema})
     * narrows the rows; an unknown or secret field is a {@code 400}.
     */
    public KeysetPage records(AccessSubject subject, InformationRegisterDescriptor desc,
                              LocalDateTime from, LocalDateTime to, String whereJson,
                              String cursor, int limit) {
        boolean periodic = desc.periodicity() != Periodicity.NONE;
        if ((!periodic && (from != null || to != null)) || (from != null && to != null && from.isAfter(to))) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Invalid period range for this register");
        }
        SqlPredicate scoped = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName())
                .andInto(Where.compile(whereJson, whereSchema(desc)));
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
     * The slice of last values: for every dimension tuple, its latest record at or before {@code at}
     * (now when absent) — the current price per product, the rate in force on a date. A
     * non-periodic register has one record per tuple, so the slice is its records. {@code where}
     * selects among the slice's rows (a resource compares the value in force). At most
     * {@value #SLICE_CAP} rows, ordered by the dimensions.
     */
    public List<Map<String, Object>> sliceLast(AccessSubject subject, InformationRegisterDescriptor desc,
                                               LocalDateTime at, String whereJson) {
        boolean periodic = desc.periodicity() != Periodicity.NONE;
        if (!periodic && at != null) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "This register is not periodic; omit 'at'");
        }
        String table = desc.tableName();
        SqlPredicate predicate = scopes.clause(desc.javaClass(), subject, AccessMode.READ, "t")
                .andInto(Where.compile(whereJson, whereSchema(desc, "t.")));
        StringBuilder sql = new StringBuilder("SELECT t.* FROM ").append(table).append(" t WHERE 1=1");
        if (periodic) {
            sql.append(" AND t._period = (SELECT MAX(s._period) FROM ").append(table)
                    .append(" s WHERE s._period <= :at");
            for (AttributeDescriptor d : desc.dimensions()) {
                sql.append(" AND s.").append(d.columnName()).append(" IS NOT DISTINCT FROM t.").append(d.columnName());
            }
            sql.append(")");
        }
        if (!predicate.isEmpty()) sql.append(" AND (").append(predicate.sql()).append(")");
        String order = desc.dimensions().stream().map(d -> "t." + d.columnName())
                .collect(java.util.stream.Collectors.joining(", "));
        sql.append(" ORDER BY ").append(order.isEmpty() ? "t._id" : order).append(" LIMIT ").append(SLICE_CAP);
        LocalDateTime cutoff = at == null ? LocalDateTime.now() : at;
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery(sql.toString());
            if (periodic) q.bind("at", cutoff);
            predicate.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        EntityQuerySupport.decorateRows(refResolver, allFields(desc), rows, subject);
        return rows;
    }

    /** A scoped, sortable UI window. A dated slice pages without the legacy slice endpoint's cap. */
    public su.onno.query.KeysetWindow.Page<Map<String, Object>> listPage(AccessSubject subject,
            InformationRegisterDescriptor desc, ListFilter.Result filters, String whereJson,
            String sort, boolean descending, LocalDateTime at, String cursor, int limit) {
        SqlPredicate predicate = listPredicate(subject, desc, filters, whereJson);
        String column = sort == null || sort.isBlank()
                ? (desc.periodicity() != Periodicity.NONE ? "_period" : "_id") : sort;
        if (!whereSchema(desc).columns().containsKey(column)) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Unknown sort column: " + column);
        }
        String where = listWhere(desc, predicate, at);
        var order = new ArrayList<su.onno.query.KeysetWindow.Order>();
        order.add(column.equals("_period") || column.equals("_id")
                ? su.onno.query.KeysetWindow.Order.notNull(column, descending)
                : new su.onno.query.KeysetWindow.Order(column, descending));
        if (!"_id".equals(column)) order.add(su.onno.query.KeysetWindow.Order.notNull("_id", descending));
        var page = KeysetWindow.collection(jdbi, desc.tableName(), "*", where, q -> {
            predicate.bindings().forEach(q::bind);
            if (at != null) q.bind("infoAt", at);
        }, order, cursor, limit, su.onno.query.Cursor.context("information-list", desc.tableName(),
                at, predicate.sql(), predicate.bindings(), EntityQuerySupport.cursorSubject(subject)));
        EntityQuerySupport.decorateRows(refResolver, allFields(desc), page.rows(), subject);
        return page;
    }

    /** Total for the standard list's virtual scroller, with the same scope, filters and slice. */
    public long listCount(AccessSubject subject, InformationRegisterDescriptor desc,
            ListFilter.Result filters, String whereJson, LocalDateTime at) {
        SqlPredicate predicate = listPredicate(subject, desc, filters, whereJson);
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + listWhere(desc, predicate, at));
            predicate.bindings().forEach(q::bind);
            if (at != null) q.bind("infoAt", at);
            return q.mapTo(Long.class).one();
        });
    }

    private SqlPredicate listPredicate(AccessSubject subject, InformationRegisterDescriptor desc,
            ListFilter.Result filters, String whereJson) {
        SqlPredicate predicate = scopes.clause(desc.javaClass(), subject, AccessMode.READ, desc.tableName())
                .andInto(Where.compile(whereJson, whereSchema(desc)));
        if (filters == null || filters.isEmpty()) return predicate;
        Map<String, Object> bindings = new java.util.LinkedHashMap<>(predicate.bindings());
        bindings.putAll(filters.bindings());
        return new SqlPredicate(predicate.isEmpty() ? filters.sql()
                : "(" + predicate.sql() + ") AND (" + filters.sql() + ")", bindings);
    }

    private String listWhere(InformationRegisterDescriptor desc, SqlPredicate predicate, LocalDateTime at) {
        String where = predicate.isEmpty() ? "1=1" : "(" + predicate.sql() + ")";
        if (at == null) return where;
        if (desc.periodicity() == Periodicity.NONE) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST, "This register is not periodic; omit 'at'");
        }
        String table = desc.tableName();
        StringBuilder latest = new StringBuilder(" AND ").append(table)
                .append("._period = (SELECT MAX(infoSlice._period) FROM ").append(table)
                .append(" infoSlice WHERE infoSlice._period <= :infoAt");
        for (AttributeDescriptor d : desc.dimensions()) {
            latest.append(" AND infoSlice.").append(d.columnName()).append(" IS NOT DISTINCT FROM ")
                    .append(table).append('.').append(d.columnName());
        }
        return where + latest.append(")");
    }

    /** Most rows a slice returns. */
    public static final int SLICE_CAP = 5000;

    /**
     * What a {@link Where} over an information register may name: its dimensions, resources and
     * (non-secret) attributes by field name, {@code id}, and {@code period} on a periodic register.
     */
    public Where.Schema whereSchema(InformationRegisterDescriptor desc) {
        return whereSchema(desc, "");
    }

    private Where.Schema whereSchema(InformationRegisterDescriptor desc, String qualifier) {
        Map<String, Where.Type> system = new java.util.LinkedHashMap<>();
        system.put("_id", Where.Type.UUID);
        if (desc.periodicity() != Periodicity.NONE) system.put("_period", Where.Type.DATETIME);
        Where.Schema schema = Where.Schema.of(allFields(desc), system, field -> {
            if ("id".equals(field)) return "_id";
            if ("period".equals(field)) return "_period";
            for (AttributeDescriptor a : allFields(desc)) {
                if (a.fieldName().equals(field)) return a.columnName();
            }
            return field;
        });
        if (qualifier.isEmpty()) return schema;
        // The slice reads `t.*` beside a correlated subquery: qualify every column with the outer alias.
        Map<String, Where.Column> qualified = new java.util.LinkedHashMap<>();
        schema.columns().forEach((name, column) -> qualified.put(name,
                new Where.Column(qualifier + column.name(), column.type(), column.enumType())));
        return new Where.Schema(qualified, schema.resolver());
    }

    private static List<AttributeDescriptor> allFields(InformationRegisterDescriptor desc) {
        List<AttributeDescriptor> all = new ArrayList<>(desc.dimensions());
        all.addAll(desc.resources());
        all.addAll(desc.attributes());
        return all;
    }
}
