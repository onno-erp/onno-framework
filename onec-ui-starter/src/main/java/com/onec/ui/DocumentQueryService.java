package com.onec.ui;

import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.TabularSectionDescriptor;
import com.onec.security.SecretRedactor;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side queries for documents (list with optional date range; detail with
 * tabular sections), shared by the REST API and the DivKit emitters. Pure data
 * access — access control stays with the callers.
 */
public class DocumentQueryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQueryService.class);

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;

    public DocumentQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.refResolver = new RefResolver(registry, jdbi);
    }

    public DocumentDescriptor require(String name) {
        String normalized = name.replace("_", "").replace(" ", "").toLowerCase();
        return registry.allDocuments().stream()
                .filter(d -> d.logicalName().replace(" ", "").replace("_", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not found: " + name));
    }

    /** The document descriptor for a domain class, or {@code null} if it isn't a registered document. */
    public DocumentDescriptor forClass(Class<?> clazz) {
        return registry.allDocuments().stream()
                .filter(d -> d.javaClass().equals(clazz))
                .findFirst()
                .orElse(null);
    }

    public List<Map<String, Object>> list(DocumentDescriptor desc, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _deletion_mark = false");
        if (from != null) sql.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        sql.append(" ORDER BY _date DESC LIMIT :rowCap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString())
                    .bind("rowCap", CatalogQueryService.MAX_LIST_ROWS + 1);
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            return query.mapToMap().list();
        });
        if (rows.size() > CatalogQueryService.MAX_LIST_ROWS) {
            log.warn("Document '{}' has more than {} live records in range; the un-paged list API "
                    + "truncated the result. Use the paged list endpoint or a date range for "
                    + "complete data.", desc.logicalName(), CatalogQueryService.MAX_LIST_ROWS);
            rows = rows.subList(0, CatalogQueryService.MAX_LIST_ROWS);
        }
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /**
     * Capped, case-insensitive typeahead by document number — the document-ref-picker
     * counterpart of the catalog search. Live records only, newest first.
     */
    public List<Map<String, Object>> search(DocumentDescriptor desc, String query, int limit) {
        String like = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false" +
                                " AND LOWER(_number) LIKE :q" +
                                " ORDER BY _date DESC LIMIT :limit")
                        .bind("q", like)
                        .bind("limit", limit)
                        .mapToMap()
                        .list()
        );
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    public long count(DocumentDescriptor desc) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _deletion_mark = false")
                        .mapTo(Long.class)
                        .one());
    }

    /**
     * One page of a document list — server-side sorted, filtered, optionally date-ranged. The
     * engine behind the virtualized/paged list grid. {@code sortColumn} is validated against the
     * entity's columns; {@code search} matches case-insensitively across the text columns.
     */
    public List<Map<String, Object>> page(DocumentDescriptor desc, int offset, int limit,
                                          String sortColumn, boolean descending, String search,
                                          String from, String to,
                                          List<String> eq, List<String> in, List<String> like,
                                          List<String> prefix, List<String> ge, List<String> le) {
        boolean defaultSort = sortColumn == null || !sortableColumns(desc).contains(sortColumn);
        String orderBy = defaultSort ? "_date" : sortColumn;
        boolean dirDesc = defaultSort ? true : descending; // newest-first by default
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, filterableColumns(desc));
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(desc, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                    " WHERE " + where +
                    " ORDER BY " + orderBy + (dirDesc ? " DESC" : " ASC") +
                    " LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", Math.max(0, offset));
            bindSearch(q, search);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /** Total live rows matching the search (+ optional date range and declarative filters). */
    public long count(DocumentDescriptor desc, String search, String from, String to,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le) {
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, filterableColumns(desc));
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(desc, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            bindSearch(q, search);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            return q.mapTo(Long.class).one();
        });
    }

    /** Lowercased attribute column names a declarative list filter may bind to (see {@link ListFilter}). */
    private Set<String> filterableColumns(DocumentDescriptor desc) {
        return desc.attributes().stream()
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    /** Columns that may be sorted on: the system columns + every attribute column. */
    public Set<String> sortableColumns(DocumentDescriptor desc) {
        Set<String> cols = new LinkedHashSet<>(Set.of("_number", "_date", "_posted"));
        desc.attributes().forEach(a -> cols.add(a.columnName()));
        return cols;
    }

    /** The text columns searched: number + every String attribute. */
    private List<String> searchColumns(DocumentDescriptor desc) {
        List<String> cols = new ArrayList<>(List.of("_number"));
        desc.attributes().stream()
                .filter(a -> a.javaType() == String.class && !a.secret())
                .forEach(a -> cols.add(a.columnName()));
        return cols;
    }

    private String searchClause(DocumentDescriptor desc, String search) {
        if (search == null || search.isBlank()) return "";
        String ors = searchColumns(desc).stream()
                .map(c -> "LOWER(CAST(" + c + " AS VARCHAR)) LIKE :search")
                .collect(Collectors.joining(" OR "));
        return " AND (" + ors + ")";
    }

    private void bindSearch(org.jdbi.v3.core.statement.Query q, String search) {
        if (search != null && !search.isBlank()) {
            q.bind("search", "%" + search.toLowerCase() + "%");
        }
    }

    /**
     * A single aggregate value for a count/metric card — {@code count} of rows, or
     * {@code sum|avg|min|max} of one numeric column — restricted to live records and
     * narrowed by an optional safe {@code filter} predicate (see {@link WidgetFilter}).
     */
    public BigDecimal aggregate(DocumentDescriptor desc, String metric, String field, String filter) {
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

    private static Set<String> columnNames(DocumentDescriptor desc) {
        return desc.attributes().stream()
                .map(a -> a.columnName().toLowerCase())
                .collect(Collectors.toSet());
    }

    public Map<String, Object> get(DocumentDescriptor desc, UUID id) {
        Map<String, Object> doc = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
        refResolver.resolveAttributes(List.of(doc), desc.attributes());
        SecretRedactor.redact(List.of(doc), desc.attributes());

        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            List<Map<String, Object>> rows = jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM " + ts.tableName() +
                                    " WHERE _parent_id = :parentId ORDER BY _line_number")
                            .bind("parentId", id)
                            .mapToMap()
                            .list()
            );
            refResolver.resolveAttributes(rows, ts.attributes());
            SecretRedactor.redact(rows, ts.attributes());
            doc.put(ts.name(), rows);
        }
        return doc;
    }
}
