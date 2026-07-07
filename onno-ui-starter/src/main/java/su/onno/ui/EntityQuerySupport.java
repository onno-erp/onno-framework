package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.security.SecretRedactor;

import org.jdbi.v3.core.Jdbi;

import java.math.BigDecimal;
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
                             List<Map<String, Object>> rows) {
        refResolver.resolveAttributes(rows, attributes);
        SecretRedactor.redact(rows, attributes);
    }

    static Long estimateCount(Jdbi jdbi, EntitySurfaceDescriptor surface, boolean filtered) {
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

    static List<Map<String, Object>> rowsByIds(Jdbi jdbi, RefResolver refResolver,
                                               EntitySurfaceDescriptor surface, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + surface.tableName() +
                                " WHERE _deletion_mark = false AND _id IN (<ids>)")
                        .bindList("ids", ids)
                        .mapToMap()
                        .list());
        decorateRows(refResolver, surface.attributes(), rows);
        return rows;
    }

    static BigDecimal aggregate(Jdbi jdbi, EntitySurfaceDescriptor surface,
                                String metric, String field, String filter) {
        String agg = WidgetAggregate.expression(metric, field, surface.columnNames());
        WidgetFilter.Result f = WidgetFilter.parse(filter, surface.columnNames());

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
                                                WidgetBuckets.Request request) {
        Set<String> allowed = new java.util.HashSet<>(surface.columnNames());
        allowed.addAll(surface.widgetSystemColumns());
        return WidgetBuckets.run(jdbi, refResolver, surface.attributes(), surface.tableName(), allowed, request);
    }

    static String searchClause(MetadataRegistry registry, EntitySurfaceDescriptor surface, String search) {
        if (search == null || search.isBlank()) return "";
        List<String> ors = new java.util.ArrayList<>();
        for (String column : surface.searchSystemColumns()) {
            ors.add(likeVarchar(column));
        }
        for (var a : surface.attributes()) {
            if (a.secret()) continue;
            String term = Searching.term(registry, a, search);
            if (term != null) ors.add(term);
        }
        return " AND (" + String.join(" OR ", ors) + ")";
    }

    static String likeVarchar(String column) {
        return "LOWER(CAST(" + column + " AS VARCHAR)) LIKE :search";
    }

    static void bindSearch(org.jdbi.v3.core.statement.Query query, String search) {
        if (search != null && !search.isBlank()) {
            query.bind("search", "%" + search.toLowerCase() + "%");
        }
    }
}
