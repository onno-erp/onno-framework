package su.onno.ui;

import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.query.Cursor;
import su.onno.query.Keyset;
import su.onno.security.SecretRedactor;

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
        return list(desc, from, to, null);
    }

    /**
     * The full document list, optionally narrowed by an authored {@link WidgetFilter} predicate (the
     * same {@code config("filter", …)} a dashboard card uses). The un-paged endpoint that chart/list
     * widgets fetch from goes through here, so passing the predicate makes those widgets honor the
     * filter consistently with the server-aggregated count tiles. A null/blank/invalid predicate is
     * simply no filter.
     */
    public List<Map<String, Object>> list(DocumentDescriptor desc, String from, String to, String filter) {
        WidgetFilter.Result wf = WidgetFilter.parse(filter, columnNames(desc));
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM " + desc.tableName() + " WHERE _deletion_mark = false");
        if (from != null) sql.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) sql.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!wf.isEmpty()) sql.append(" AND (").append(wf.sql()).append(")");
        sql.append(" ORDER BY _date DESC LIMIT :rowCap");

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString())
                    .bind("rowCap", CatalogQueryService.MAX_LIST_ROWS + 1);
            if (from != null) query.bind("from", from);
            if (to != null) query.bind("to", to);
            wf.bindings().forEach(query::bind);
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
     * Capped, case-insensitive typeahead for the document ref picker. Matches across the same text
     * columns the paged list search covers — number plus every (non-secret) String attribute — so a
     * document is findable by a secondary attribute, not just its number (issue #184). Live records
     * only, newest first.
     */
    public List<Map<String, Object>> search(DocumentDescriptor desc, String query, int limit) {
        String where = "_deletion_mark = false" + searchClause(desc, query);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY _date DESC LIMIT :limit")
                    .bind("limit", limit);
            bindSearch(q, query);
            return q.mapToMap().list();
        });
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /**
     * The seed row for a <em>new</em> document form: a fresh instance's field-initializer defaults
     * in the same column-keyed, ref-resolved shape {@link #get} returns for an existing record, so
     * the New form pre-fills declared defaults instead of opening blank (issue #181).
     */
    public Map<String, Object> newDraft(DocumentDescriptor desc) {
        Map<String, Object> row = NewEntityDefaults.columnValues(desc.javaClass(), desc.attributes(), registry);
        refResolver.resolveAttributes(List.of(row), desc.attributes());
        return row;
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
     *
     * <p>{@code widgetFilter} is an optional authored {@link WidgetFilter} predicate (the same
     * {@code config("filter", …)} a dashboard card uses) — it lets a chart/list/calendar widget
     * scope its rows to, say, {@code "status != 'DRAFT'"} server-side, on top of any user-driven
     * column filters.
     */
    public List<Map<String, Object>> page(DocumentDescriptor desc, int offset, int limit,
                                          String sortColumn, boolean descending, String search,
                                          String from, String to,
                                          List<String> eq, List<String> in, List<String> like,
                                          List<String> prefix, List<String> ge, List<String> le,
                                          String widgetFilter) {
        boolean defaultSort = sortColumn == null || !sortableColumns(desc).contains(sortColumn);
        String orderBy = defaultSort ? "_date" : sortColumn;
        boolean dirDesc = defaultSort ? true : descending; // newest-first by default
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, filterableColumns(desc));
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, columnNames(desc));
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(desc, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
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
            wf.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /**
     * One keyset-paginated window of a document list — the constant-time default for the list grid.
     * Seeks past {@code cursorToken} (null/blank for the first window) instead of counting past an
     * offset, so deep paging stays O(window). Honors the same sort/search/date-range/filters as
     * {@link #page}; fetches one extra row for {@code hasMore} (no COUNT) and mints the next cursor
     * from the last row. The default newest-first order seeks on {@code (_date, _id)}.
     */
    public KeysetPage keysetPage(DocumentDescriptor desc, String cursorToken, int limit,
                                 String sortColumn, boolean descending, String search,
                                 String from, String to,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String widgetFilter) {
        boolean defaultSort = sortColumn == null || !sortableColumns(desc).contains(sortColumn);
        String col = defaultSort ? "_date" : sortColumn;
        boolean dirDesc = defaultSort || descending; // newest-first by default
        Cursor cursor = Cursor.decodeFor(cursorToken, col, dirDesc);
        Keyset.Plan plan = Keyset.plan(col, dirDesc, !isNonNullableSort(desc, col), cursor);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, filterableColumns(desc));
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, columnNames(desc));
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(desc, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
        where.append(plan.predicate());
        int lim = Keyset.clampLimit(limit);

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY " + plan.orderBy() +
                            " LIMIT :limit")
                    .bind("limit", lim + 1); // one extra row tells us whether another window exists
            bindSearch(q, search);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            if (plan.hasCursor()) {
                q.bind(Keyset.ID_BIND, cursor.id());
                if (plan.bindsValue()) q.bind(Keyset.VALUE_BIND, cursor.value());
            }
            return q.mapToMap().list();
        });

        boolean hasMore = rows.size() > lim;
        if (hasMore) {
            rows = rows.subList(0, lim);
        }
        String nextCursor = (hasMore && !rows.isEmpty())
                ? Cursor.from(col, dirDesc, rows.get(rows.size() - 1)).encode()
                : null;
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return new KeysetPage(rows, nextCursor, hasMore);
    }

    /**
     * Whether {@code column} is safe for the fast (index-only) keyset seek — the framework keeps it
     * populated, so the seek never has to reason about NULLs. True for {@code _date}, {@code _number}
     * and {@code _posted} (all framework-set) and any {@code required} attribute; optional attributes
     * use the NULL-safe seek instead.
     */
    private boolean isNonNullableSort(DocumentDescriptor desc, String column) {
        if (column.equals("_date") || column.equals("_number") || column.equals("_posted")) {
            return true;
        }
        return desc.attributes().stream()
                .anyMatch(a -> a.columnName().equals(column) && a.required());
    }

    /**
     * A cheap live-row estimate for the scroll-height hint, or {@code null} when none is available
     * (H2, or any active search/date-range/filter). Uses PostgreSQL planner statistics
     * ({@code pg_class.reltuples}) so it never scans the table; callers wanting an exact figure use
     * {@link #count} instead.
     */
    public Long estimateCount(DocumentDescriptor desc, boolean filtered) {
        if (filtered) {
            return null;
        }
        return jdbi.withHandle(h -> {
            try {
                return h.createQuery("SELECT reltuples::bigint FROM pg_class WHERE relname = :t")
                        .bind("t", desc.tableName())
                        .mapTo(Long.class).findOne().filter(n -> n >= 0).orElse(null);
            } catch (RuntimeException e) {
                return null; // not PostgreSQL (no pg_class) → no estimate
            }
        });
    }

    /**
     * Fetch specific live documents by id, decorated exactly like {@link #page} (refs resolved,
     * secrets redacted) so the list island can refresh just the rows that changed instead of
     * re-paging the whole window. Returns only the rows that still exist and aren't deletion-marked.
     */
    public List<Map<String, Object>> rowsByIds(DocumentDescriptor desc, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false AND _id IN (<ids>)")
                        .bindList("ids", ids)
                        .mapToMap()
                        .list());
        refResolver.resolveAttributes(rows, desc.attributes());
        SecretRedactor.redact(rows, desc.attributes());
        return rows;
    }

    /** Total live rows matching the search (+ optional date range, declarative filters, widget filter). */
    public long count(DocumentDescriptor desc, String search, String from, String to,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le,
                      String widgetFilter) {
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, filterableColumns(desc));
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, columnNames(desc));
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(desc, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            bindSearch(q, search);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            return q.mapTo(Long.class).one();
        });
    }

    /**
     * Group a document list by {@code groupColumn} (a validated sortable column): one header per
     * distinct value, or — for a date/time column (e.g. {@code _date}) — per {@code granularity}
     * bucket, over the same WHERE (search + date range + declarative + widget filters) as the flat
     * list. Each header carries its row count, the requested {@code aggregates}, and the
     * {@code expand} filter the client replays on the normal feed. Headers capped at
     * {@link ListGroups#MAX_GROUPS}.
     */
    public ListGroups.GroupResult groups(DocumentDescriptor desc, String groupColumn, String granularity,
                                         String search, String from, String to,
                                         List<String> eq, List<String> in, List<String> like,
                                         List<String> prefix, List<String> ge, List<String> le,
                                         String widgetFilter, List<ListGroups.Agg> aggregates) {
        if (groupColumn == null || !sortableColumns(desc).contains(groupColumn)) {
            return new ListGroups.GroupResult(List.of(), false);
        }
        Set<String> columns = columnNames(desc);
        boolean date = isTemporalColumn(desc, groupColumn);
        String groupExpr = ListGroups.groupExpression(groupColumn, date, granularity);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, filterableColumns(desc));
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, columns);
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(desc, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");

        StringBuilder select = new StringBuilder(groupExpr).append(" AS ").append(groupColumn)
                .append(", COUNT(*) AS _count");
        List<ListGroups.Agg> valid = new ArrayList<>();
        for (ListGroups.Agg a : aggregates) {
            try {
                select.append(", ").append(WidgetAggregate.expression(a.fn(), a.column(), columns))
                        .append(" AS a").append(valid.size());
                valid.add(a);
            } catch (IllegalArgumentException ignored) {
                // skip an aggregate over an unknown column/fn
            }
        }

        String sql = "SELECT " + select + " FROM " + desc.tableName()
                + " WHERE " + where
                + " GROUP BY " + groupExpr
                + " ORDER BY " + groupExpr + " ASC"
                + " LIMIT :limit";
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery(sql).bind("limit", ListGroups.MAX_GROUPS + 1);
            bindSearch(q, search);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        boolean capped = rows.size() > ListGroups.MAX_GROUPS;
        if (capped) {
            rows = new ArrayList<>(rows.subList(0, ListGroups.MAX_GROUPS));
        }
        refResolver.resolveAttributes(rows, desc.attributes());
        return new ListGroups.GroupResult(
                ListGroups.buildGroups(rows, groupColumn, date, granularity, valid), capped);
    }

    /** Whether a group column is a date/time — so grouping buckets it by period (see {@link ListGroups}). */
    private static boolean isTemporalColumn(DocumentDescriptor desc, String column) {
        if (column.equals("_date") || column.equals("_period")) {
            return true;
        }
        return desc.attributes().stream()
                .anyMatch(a -> a.columnName().equalsIgnoreCase(column) && ListGroups.isTemporalType(a.javaType()));
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

    /**
     * The free-text search predicate for {@code q}: matches the term against <em>every</em> non-secret
     * column, not just strings — the document number, every scalar attribute (numbers/dates cast to
     * text), each {@code Ref<>} by the displayed value of its target (customer name, assignee), and each
     * enum by its label/name. See {@link Searching}. One bound {@code :search} drives every text term.
     */
    private String searchClause(DocumentDescriptor desc, String search) {
        if (search == null || search.isBlank()) return "";
        List<String> ors = new ArrayList<>(List.of(Searching.likeVarchar("_number")));
        for (var a : desc.attributes()) {
            if (a.secret()) continue;
            String term = Searching.term(registry, a, search);
            if (term != null) ors.add(term);
        }
        return " AND (" + String.join(" OR ", ors) + ")";
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
