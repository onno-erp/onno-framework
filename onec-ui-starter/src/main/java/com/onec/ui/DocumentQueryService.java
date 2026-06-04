package com.onec.ui;

import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.TabularSectionDescriptor;
import com.onec.security.SecretRedactor;

import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;

    public DocumentQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.refResolver = new RefResolver(registry, jdbi);
    }

    public DocumentDescriptor require(String name) {
        String normalized = name.replace("_", "").toLowerCase();
        return registry.allDocuments().stream()
                .filter(d -> d.logicalName().replace(" ", "").toLowerCase().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not found: " + name));
    }

    public List<Map<String, Object>> list(DocumentDescriptor desc, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _deletion_mark = false");
        if (from != null) sql.append(" AND _date >= :from");
        if (to != null) sql.append(" AND _date <= :to");
        sql.append(" ORDER BY _date DESC");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            return query.mapToMap().list();
        });
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
