package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.security.SecretRedactor;

import org.jdbi.v3.core.Jdbi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class EntityQuerySupport {

    private EntityQuerySupport() {
    }

    static String filterClause(ListFilter.Result filter) {
        return filter.isEmpty() ? "" : " AND (" + filter.sql() + ")";
    }

    static String filterClause(WidgetFilter.Result filter) {
        return filter.isEmpty() ? "" : " AND (" + filter.sql() + ")";
    }

    static void decorateRows(RefResolver refResolver, List<AttributeDescriptor> attributes,
                             List<Map<String, Object>> rows, AccessSubject subject) {
        normalizeTemporals(rows, attributes);
        refResolver.resolveAttributes(rows, attributes, subject);
        SecretRedactor.redact(rows, attributes);
    }

    /** The subject's READ scope over the surface's table, qualified by the table name. */
    static ScopeClause readScope(RecordScopeCompiler scopes, EntitySurfaceDescriptor surface, AccessSubject subject) {
        return scopes.clause(surface.javaClass(), subject, AccessMode.READ, surface.tableName());
    }

    /** The search {@link Searching.TargetGate} for {@code subject}: entity read grant + target scope. */
    static Searching.TargetGate targetGate(RecordScopeCompiler scopes, UiAccessService access, AccessSubject subject) {
        if (subject instanceof AccessSubject.System) {
            return Searching.TargetGate.OPEN;
        }
        return (kind, name, type, alias) -> access.canRead(subject, kind, name)
                ? scopes.clause(type, subject, AccessMode.READ, alias)
                : null;
    }

    /**
     * Keep the read contract database-independent: DATE is {@code yyyy-MM-dd}; TIMESTAMP /
     * {@link LocalDateTime} is offset-free ISO. PostgreSQL/JDBI can surface timestamp values as
     * {@link java.sql.Timestamp} or an offset-bearing temporal that Jackson would otherwise render
     * with {@code +00:00}; H2 commonly returns a space-separated timestamp. LocalDateTime is a
     * wall-clock value, so normalization never shifts the local fields.
     */
    private static void normalizeTemporals(List<Map<String, Object>> rows,
                                           List<AttributeDescriptor> attributes) {
        for (Map<String, Object> row : rows) {
            // Normalize declared temporal attributes even when a driver returned a String.
            for (AttributeDescriptor attr : attributes) {
                Object value = row.get(attr.columnName());
                if (value == null) continue;
                if (attr.javaType() == LocalDate.class) {
                    row.put(attr.columnName(), TemporalValues.toLocalDate(value).toString());
                } else if (attr.javaType() == LocalDateTime.class) {
                    row.put(attr.columnName(), TemporalValues.toLocalDateTime(value).toString());
                }
            }
            // Also normalize framework system timestamps such as _date and _period.
            row.replaceAll((column, value) -> canonicalTemporal(value));
        }
    }

    private static Object canonicalTemporal(Object value) {
        if (value instanceof LocalDate || value instanceof java.sql.Date) {
            return TemporalValues.toLocalDate(value).toString();
        }
        if (value instanceof LocalDateTime || value instanceof java.sql.Timestamp
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime) {
            return TemporalValues.toLocalDateTime(value).toString();
        }
        return value;
    }

    /**
     * The planner's row estimate — unless the subject is record-scoped, where {@code reltuples}
     * would reveal the size of the whole table: then the exact count of the subject's live rows.
     */
    static Long estimateCount(Jdbi jdbi, EntitySurfaceDescriptor surface, boolean filtered, ScopeClause scope) {
        if (!scope.isAll()) {
            return filtered ? null : jdbi.withHandle(h -> scope.bind(h.createQuery(
                            "SELECT COUNT(*) FROM " + surface.tableName() + " WHERE _deletion_mark = false" + scope.and()))
                    .mapTo(Long.class).one());
        }
        if (filtered) {
            return null;
        }
        return jdbi.withHandle(h -> {
            try {
                return h.createQuery("SELECT reltuples::bigint FROM pg_class WHERE relname = :t")
                        .bind("t", surface.tableName())
                        .mapTo(Long.class).findOne().filter(n -> n >= 0).orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    /** Live rows by id, silently dropping ids outside the subject's scope. */
    static List<Map<String, Object>> rowsByIds(Jdbi jdbi, RefResolver refResolver,
                                               EntitySurfaceDescriptor surface, List<UUID> ids,
                                               ScopeClause scope, AccessSubject subject) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + surface.tableName() +
                                " WHERE _deletion_mark = false AND _id IN (<ids>)" + scope.and()))
                        .bindList("ids", ids)
                        .mapToMap()
                        .list());
        decorateRows(refResolver, surface.attributes(), rows, subject);
        return rows;
    }

    static BigDecimal aggregate(Jdbi jdbi, EntitySurfaceDescriptor surface,
                                String metric, String field, String filter, ScopeClause scope) {
        String agg = WidgetAggregate.expression(metric, surface.storageColumn(field), surface.columnNames());
        WidgetFilter.Result f = scope.andInto(
                WidgetFilter.parse(filter, surface.columnNames(), surface.uuidColumns()));

        StringBuilder sql = new StringBuilder("SELECT ").append(agg)
                .append(" FROM ").append(surface.tableName())
                .append(" WHERE _deletion_mark = false");
        if (!f.isEmpty()) {
            sql.append(" AND ").append(f.sql());
        }
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            f.bindings().forEach(query::bind);
            return query.mapTo(BigDecimal.class).findOne().orElse(BigDecimal.ZERO);
        });
    }

    static Map<String, Object> aggregateBuckets(Jdbi jdbi, RefResolver refResolver,
                                                EntitySurfaceDescriptor surface,
                                                WidgetBuckets.Request request,
                                                ScopeClause scope, AccessSubject subject) {
        Set<String> allowed = new java.util.HashSet<>(surface.columnNames());
        allowed.addAll(surface.widgetSystemColumns());
        WidgetBuckets.Request storageRequest = new WidgetBuckets.Request(
                request.metric(), surface.storageColumn(request.field()),
                request.metric2(), surface.storageColumn(request.field2()),
                surface.storageColumn(request.groupBy()), request.groupByDate(),
                surface.storageColumn(request.seriesBy()), request.filter(),
                surface.storageColumn(request.dateField()), request.from(), request.to());
        return WidgetBuckets.run(jdbi, refResolver, surface.attributes(), surface.tableName(), allowed,
                surface.uuidColumns(), storageRequest, scope, subject);
    }

    /**
     * The free-text search predicate over every non-secret column of the surface (see
     * {@link Searching}), or {@link ScopeClause#ALL} for no search. Ref terms look only through
     * targets {@code gate} admits. Bind {@code :search} with {@link #bindSearch} as well.
     */
    static ScopeClause searchClause(MetadataRegistry registry, EntitySurfaceDescriptor surface, String search,
                                    Searching.TargetGate gate) {
        if (search == null || search.isBlank()) return ScopeClause.ALL;
        List<String> ors = new java.util.ArrayList<>();
        for (String column : surface.searchSystemColumns()) {
            ors.add(likeVarchar(surface.tableName() + "." + column));
        }
        Map<String, Object> bindings = new java.util.LinkedHashMap<>();
        int i = 0;
        for (var a : surface.attributes()) {
            if (a.secret()) continue;
            String term = Searching.term(registry, a, search, surface.tableName(), "_st" + (i++), gate, bindings);
            if (term != null) ors.add(term);
        }
        return new ScopeClause(String.join(" OR ", ors), bindings);
    }

    static String likeVarchar(String column) {
        return "LOWER(CAST(" + column + " AS VARCHAR)) LIKE :search";
    }

    static void bindSearch(org.jdbi.v3.core.statement.Query query, String search) {
        if (search != null && !search.isBlank()) {
            query.bind("search", "%" + search.toLowerCase() + "%");
        }
    }

    /**
     * The policy {@code defaults(...)} for a record {@code subject} creates, as New-form prefill
     * strings keyed by field name (refs/enums as UUID strings) — so the form shows the value the
     * create will store.
     */
    static Map<String, String> policyDefaults(RecordScopeCompiler scopes, Class<?> type, AccessSubject subject) {
        su.onno.access.RecordPolicies policies = scopes.policies();
        su.onno.access.ScopedEntity entity = policies.entity(type);
        Map<String, Object> defaults = policies.defaultsFor(type, subject);
        if (entity == null || defaults.isEmpty()) return Map.of();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        defaults.forEach((field, value) -> {
            su.onno.access.ScopedEntity.Field f = entity.field(field);
            Object coerced = f == null ? value : policies.coerce(f, value);
            if (coerced != null && coerced != su.onno.access.RecordPolicies.UNMATCHABLE) {
                out.put(field, coerced.toString());
            }
        });
        return out;
    }

    /** Overlay {@code defaults} onto caller prefill; a policy default always wins. */
    static Map<String, String> withDefaults(Map<String, String> prefill, Map<String, String> defaults) {
        if (defaults.isEmpty()) return prefill;
        Map<String, String> merged = new java.util.LinkedHashMap<>(prefill == null ? Map.of() : prefill);
        merged.putAll(defaults);
        return merged;
    }

    // ------------------------------------------------------------- subject-bound keyset cursors

    private static final char CURSOR_SEAL = '~'; // not in the URL-safe Base64 alphabet

    /**
     * Bind a keyset cursor to the subject that minted it: a restricted subject's cursor carries
     * its {@link su.onno.access.RecordPolicies#fingerprint fingerprint}; an unrestricted one is
     * unchanged.
     */
    static String sealCursor(String token, String fingerprint) {
        if (token == null || fingerprint == null || fingerprint.isEmpty()) return token;
        return token + CURSOR_SEAL + fingerprint;
    }

    /**
     * The raw cursor token if it was minted for {@code fingerprint}; a cursor minted for another
     * subject is rejected with 400 rather than silently re-seeked.
     */
    static String openCursor(String token, String fingerprint) {
        if (token == null || token.isBlank()) return token;
        int seal = token.lastIndexOf(CURSOR_SEAL);
        String raw = seal < 0 ? token : token.substring(0, seal);
        String minted = seal < 0 ? "" : token.substring(seal + 1);
        String expected = fingerprint == null ? "" : fingerprint;
        if (!minted.equals(expected)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Cursor was issued for a different user");
        }
        return raw;
    }

    // ------------------------------------------------------------- hierarchical catalogs

    /**
     * For a record-scoped viewer, withhold {@code _parent} where the parent is outside the scope —
     * such a row is presented as a root ({@code _parent = null}, {@code _parent_restricted = true}).
     */
    static void maskParents(Jdbi jdbi, String table, List<Map<String, Object>> rows, ScopeClause scope) {
        if (scope.isAll() || rows.isEmpty()) return;
        java.util.Set<UUID> parents = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) {
            Object p = row.get("_parent");
            if (p != null && !"".equals(p)) parents.add(p instanceof UUID u ? u : UUID.fromString(p.toString()));
        }
        if (parents.isEmpty()) return;
        java.util.Set<UUID> visible = new java.util.HashSet<>(jdbi.withHandle(h -> scope.bind(h.createQuery(
                        "SELECT _id FROM " + table + " WHERE _id IN (<ids>)" + scope.and()))
                .bindList("ids", new java.util.ArrayList<>(parents))
                .mapTo(UUID.class).list()));
        for (Map<String, Object> row : rows) {
            Object p = row.get("_parent");
            if (p == null || "".equals(p)) continue;
            UUID id = p instanceof UUID u ? u : UUID.fromString(p.toString());
            if (!visible.contains(id)) {
                row.put("_parent", null);
                row.put("_parent_restricted", true);
            }
        }
    }
}
