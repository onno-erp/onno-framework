package com.onec.ui;

import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.security.SecretRedactor;

import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side queries for catalogs, shared by the REST API and the DivKit emitters
 * so the SQL and ref-resolution live in one place. Pure data access — access
 * control stays with the callers.
 */
public class CatalogQueryService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;

    public CatalogQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.refResolver = new RefResolver(registry, jdbi);
    }

    public CatalogDescriptor require(String name) {
        String normalized = name.replace("_", "").replace(" ", "").toLowerCase();
        return registry.allCatalogs().stream()
                .filter(d -> d.logicalName().replace(" ", "").replace("_", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog not found: " + name));
    }

    /** The catalog descriptor for a domain class, or {@code null} if it isn't a registered catalog. */
    public CatalogDescriptor forClass(Class<?> clazz) {
        return registry.allCatalogs().stream()
                .filter(d -> d.javaClass().equals(clazz))
                .findFirst()
                .orElse(null);
    }

    public List<Map<String, Object>> list(CatalogDescriptor desc) {
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _deletion_mark = false")
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /**
     * Server-side typeahead for ref pickers: case-insensitive match on code/description,
     * capped at {@code limit}, so a 2000-row catalog never ships whole to the client.
     */
    public List<Map<String, Object>> search(CatalogDescriptor desc, String query, int limit) {
        String like = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false" +
                                " AND (LOWER(_description) LIKE :q OR LOWER(_code) LIKE :q)" +
                                " ORDER BY _description LIMIT :limit")
                        .bind("q", like)
                        .bind("limit", limit)
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    public long count(CatalogDescriptor desc) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _deletion_mark = false")
                        .mapTo(Long.class)
                        .one());
    }

    /**
     * A single aggregate value for a count/metric card — {@code count} of rows, or
     * {@code sum|avg|min|max} of one numeric column — restricted to live records and
     * narrowed by an optional safe {@code filter} predicate (see {@link WidgetFilter}).
     */
    public BigDecimal aggregate(CatalogDescriptor desc, String metric, String field, String filter) {
        Set<String> columns = columnNames(desc);
        String agg = WidgetAggregate.expression(metric, field, columns);
        WidgetFilter.Result f = WidgetFilter.parse(filter, columns);

        StringBuilder sql = new StringBuilder("SELECT ").append(agg)
                .append(" FROM ").append(desc.tableName())
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

    private static Set<String> columnNames(CatalogDescriptor desc) {
        return desc.attributes().stream()
                .map(a -> a.columnName().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
    }

    public List<Map<String, Object>> list(CatalogDescriptor desc, int offset, int limit) {
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false ORDER BY _code LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /**
     * One page of a catalog list, server-side sorted and filtered — the engine behind the
     * virtualized/paged list grid. {@code sortColumn} must be one of the entity's real columns
     * (validated by the caller via {@link #sortableColumns}); {@code search} matches case-insensitively
     * across the text columns. Live records only.
     */
    public List<Map<String, Object>> page(CatalogDescriptor desc, int offset, int limit,
                                           String sortColumn, boolean descending, String search,
                                           List<String> eq, List<String> ge, List<String> le) {
        String orderBy = safeSort(desc, sortColumn, "_code");
        ListFilter.Result filter = ListFilter.parse(eq, ge, le, filterableColumns(desc));
        String where = "_deletion_mark = false" + searchClause(desc, search) + filterClause(filter);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                    " WHERE " + where +
                    " ORDER BY " + orderBy + (descending ? " DESC" : " ASC") +
                    " LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", Math.max(0, offset));
            bindSearch(q, search);
            filter.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /** Total live rows matching the search (+ any declarative filters) — for the virtual scroller. */
    public long count(CatalogDescriptor desc, String search,
                      List<String> eq, List<String> ge, List<String> le) {
        ListFilter.Result filter = ListFilter.parse(eq, ge, le, filterableColumns(desc));
        String where = "_deletion_mark = false" + searchClause(desc, search) + filterClause(filter);
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            bindSearch(q, search);
            filter.bindings().forEach(q::bind);
            return q.mapTo(Long.class).one();
        });
    }

    /** Lowercased attribute column names a declarative list filter may bind to (see {@link ListFilter}). */
    private Set<String> filterableColumns(CatalogDescriptor desc) {
        return desc.attributes().stream()
                .map(a -> a.columnName().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String filterClause(ListFilter.Result filter) {
        return filter.isEmpty() ? "" : " AND (" + filter.sql() + ")";
    }

    /** Column names that may be sorted on: the system columns + every attribute column. */
    public Set<String> sortableColumns(CatalogDescriptor desc) {
        Set<String> cols = new java.util.LinkedHashSet<>(Set.of("_code", "_description"));
        desc.attributes().forEach(a -> cols.add(a.columnName()));
        return cols;
    }

    private String safeSort(CatalogDescriptor desc, String sortColumn, String fallback) {
        return sortColumn != null && sortableColumns(desc).contains(sortColumn) ? sortColumn : fallback;
    }

    /** The text columns searched: code, description + every String attribute. */
    private List<String> searchColumns(CatalogDescriptor desc) {
        List<String> cols = new ArrayList<>(List.of("_code", "_description"));
        desc.attributes().stream()
                .filter(a -> a.javaType() == String.class && !a.secret())
                .forEach(a -> cols.add(a.columnName()));
        return cols;
    }

    private String searchClause(CatalogDescriptor desc, String search) {
        if (search == null || search.isBlank()) return "";
        String ors = searchColumns(desc).stream()
                .map(c -> "LOWER(CAST(" + c + " AS VARCHAR)) LIKE :search")
                .collect(java.util.stream.Collectors.joining(" OR "));
        return " AND (" + ors + ")";
    }

    private void bindSearch(org.jdbi.v3.core.statement.Query q, String search) {
        if (search != null && !search.isBlank()) {
            q.bind("search", "%" + search.toLowerCase() + "%");
        }
    }

    public List<Map<String, Object>> children(CatalogDescriptor desc, UUID parent) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            String sql = "SELECT * FROM " + desc.tableName() +
                    " WHERE _deletion_mark = false AND " +
                    (parent == null ? "_parent IS NULL" : "_parent = :parent") +
                    " ORDER BY _is_folder DESC, _description";
            var query = h.createQuery(sql);
            if (parent != null) query.bind("parent", parent);
            return query.mapToMap().list();
        });
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    public List<Map<String, Object>> tree(CatalogDescriptor desc) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false ORDER BY _is_folder DESC, _description")
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return buildTree(rows, null);
    }

    /**
     * Live rows of a join catalog whose {@code viaColumn} ref points at {@code parentId} — the
     * read side of a related-list panel (see {@link RelatedList}). Ordered by code so the inline
     * roster is stable. Refs are resolved (so the {@code display} ref shows its description) and
     * secrets redacted, exactly like the standalone catalog list. {@code viaColumn} must be a real
     * column on {@code desc} (the caller resolves it from the join catalog's metadata, never from
     * user input) so this stays injection-safe.
     */
    public List<Map<String, Object>> relatedRows(CatalogDescriptor desc, String viaColumn, UUID parentId) {
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false AND " + viaColumn + " = :parent" +
                                " ORDER BY _code")
                        .bind("parent", parentId)
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    public Map<String, Object> get(CatalogDescriptor desc, UUID id) {
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
        refResolver.resolveAttributes(List.of(row), desc.attributes());
        SecretRedactor.redact(List.of(row), desc.attributes());
        return row;
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> rows, UUID parent) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID rowParent = parseUuid(row.get("_parent"));
            if (!Objects.equals(rowParent, parent)) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("children", buildTree(rows, parseUuid(row.get("_id"))));
            result.add(copy);
        }
        return result;
    }

    private static UUID parseUuid(Object value) {
        if (value == null || "".equals(value)) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
