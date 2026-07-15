package su.onno.ui;

import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.query.Cursor;
import su.onno.query.Keyset;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        EntitySurfaceDescriptor surface = surface(desc);
        WidgetFilter.Result wf = WidgetFilter.parse(filter, surface.columnNames());
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return rows;
    }

    /**
     * Capped, case-insensitive typeahead for the document ref picker. Matches across the same text
     * columns the paged list search covers — number plus every (non-secret) String attribute — so a
     * document is findable by a secondary attribute, not just its number (issue #184). Live records
     * only, newest first.
     */
    public List<Map<String, Object>> search(DocumentDescriptor desc, String query, int limit) {
        return search(desc, query, limit, null);
    }

    /**
     * As {@link #search(DocumentDescriptor, String, int)}, additionally narrowed by a
     * {@link WidgetFilter} predicate — the cascading ref picker sends its resolved
     * {@code refFilter} here, so only compatible documents are offered. Ref/enum columns bind as
     * typed uuids (PG-strict); a null/blank/invalid predicate is simply no filter.
     */
    public List<Map<String, Object>> search(DocumentDescriptor desc, String query, int limit, String filter) {
        EntitySurfaceDescriptor surface = surface(desc);
        WidgetFilter.Result wf = WidgetFilter.parse(filter, surface.columnNames(), surface.uuidColumns());
        String where = "_deletion_mark = false"
                + (wf.isEmpty() ? "" : " AND (" + wf.sql() + ")")
                + searchClause(surface, query);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY _date DESC LIMIT :limit")
                    .bind("limit", limit);
            wf.bindings().forEach(q::bind);
            EntityQuerySupport.bindSearch(q, query);
            return q.mapToMap().list();
        });
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return rows;
    }

    /**
     * The seed row for a <em>new</em> document form: a fresh instance's field-initializer defaults
     * in the same column-keyed, ref-resolved shape {@link #get} returns for an existing record, so
     * the New form pre-fills declared defaults instead of opening blank (issue #181).
     */
    public Map<String, Object> newDraft(DocumentDescriptor desc) {
        return newDraft(desc, Map.of());
    }

    /**
     * As {@link #newDraft(DocumentDescriptor)}, but overlays caller-supplied initial values (from the
     * New-form navigation query, keyed by attribute field name) onto the seed row before ref/enum
     * resolution — so a deep link like {@code …/new?startsAt=…&room=<id>} pre-fills those fields.
     */
    public Map<String, Object> newDraft(DocumentDescriptor desc, Map<String, String> prefill) {
        Map<String, Object> row = NewEntityDefaults.columnValues(desc.javaClass(), desc.attributes(), registry);
        NewEntityDefaults.applyPrefill(row, desc.attributes(), prefill);
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
        EntitySurfaceDescriptor surface = surface(desc);
        boolean defaultSort = surface.isDefaultSort(sortColumn);
        String orderBy = surface.safeSort(sortColumn);
        boolean dirDesc = defaultSort ? surface.defaultDescending() : descending;
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames());
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(surface, search));
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
            EntityQuerySupport.bindSearch(q, search);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
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
        EntitySurfaceDescriptor surface = surface(desc);
        boolean defaultSort = surface.isDefaultSort(sortColumn);
        String col = surface.safeSort(sortColumn);
        boolean dirDesc = defaultSort ? surface.defaultDescending() : descending;
        Cursor cursor = Cursor.decodeFor(cursorToken, col, dirDesc);
        Keyset.Plan plan = Keyset.plan(col, dirDesc, !surface.isNonNullableSort(col), cursor);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames());
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(surface, search));
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
            EntityQuerySupport.bindSearch(q, search);
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return new KeysetPage(rows, nextCursor, hasMore);
    }

    /**
     * Whether {@code column} is safe for the fast (index-only) keyset seek — the framework keeps it
     * populated, so the seek never has to reason about NULLs. True for {@code _date}, {@code _number}
     * and {@code _posted} (all framework-set) and any {@code required} attribute; optional attributes
     * use the NULL-safe seek instead.
     */
    /**
     * A cheap live-row estimate for the scroll-height hint, or {@code null} when none is available
     * (H2, or any active search/date-range/filter). Uses PostgreSQL planner statistics
     * ({@code pg_class.reltuples}) so it never scans the table; callers wanting an exact figure use
     * {@link #count} instead.
     */
    public Long estimateCount(DocumentDescriptor desc, boolean filtered) {
        return EntityQuerySupport.estimateCount(jdbi, surface(desc), filtered);
    }

    /**
     * Fetch specific live documents by id, decorated exactly like {@link #page} (refs resolved,
     * secrets redacted) so the list island can refresh just the rows that changed instead of
     * re-paging the whole window. Returns only the rows that still exist and aren't deletion-marked.
     */
    public List<Map<String, Object>> rowsByIds(DocumentDescriptor desc, List<UUID> ids) {
        return EntityQuerySupport.rowsByIds(jdbi, refResolver, surface(desc), ids);
    }

    /** Total live rows matching the search (+ optional date range, declarative filters, widget filter). */
    public long count(DocumentDescriptor desc, String search, String from, String to,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le,
                      String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames());
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(surface, search));
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            EntityQuerySupport.bindSearch(q, search);
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
        EntitySurfaceDescriptor surface = surface(desc);
        if (groupColumn == null || !surface.sortableColumns().contains(groupColumn)) {
            return new ListGroups.GroupResult(List.of(), false);
        }
        Set<String> columns = surface.columnNames();
        boolean date = isTemporalColumn(desc, groupColumn);
        String groupExpr = ListGroups.groupExpression(groupColumn, date, granularity);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, columns);
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause(surface, search));
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
            EntityQuerySupport.bindSearch(q, search);
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

    /** Columns that may be sorted on: the system columns + every attribute column. */
    public Set<String> sortableColumns(DocumentDescriptor desc) {
        return surface(desc).sortableColumns();
    }

    /**
     * The free-text search predicate for {@code q}: matches the term against <em>every</em> non-secret
     * column, not just strings — the document number, every scalar attribute (numbers/dates cast to
     * text), each {@code Ref<>} by the displayed value of its target (customer name, assignee), and each
     * enum by its label/name. See {@link Searching}. One bound {@code :search} drives every text term.
     */
    private String searchClause(EntitySurfaceDescriptor surface, String search) {
        return EntityQuerySupport.searchClause(registry, surface, search);
    }

    /**
     * A single aggregate value for a count/metric card — {@code count} of rows, or
     * {@code sum|avg|min|max} of one numeric column — restricted to live records and
     * narrowed by an optional safe {@code filter} predicate (see {@link WidgetFilter}).
     */
    public BigDecimal aggregate(DocumentDescriptor desc, String metric, String field, String filter) {
        return EntityQuerySupport.aggregate(jdbi, surface(desc), metric, field, filter);
    }

    /**
     * Grouped aggregate buckets for a chart/stat widget — a server-side {@code GROUP BY} returning
     * O(buckets) rows instead of the whole table (#199). See {@link WidgetBuckets}.
     */
    public Map<String, Object> aggregateBuckets(DocumentDescriptor desc, WidgetBuckets.Request request) {
        return EntityQuerySupport.aggregateBuckets(jdbi, refResolver, surface(desc), request);
    }

    private static EntitySurfaceDescriptor surface(DocumentDescriptor desc) {
        return EntitySurfaceDescriptor.document(desc);
    }

    public Map<String, Object> get(DocumentDescriptor desc, UUID id) {
        Map<String, Object> doc = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), List.of(doc));

        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            List<Map<String, Object>> rows = jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM " + ts.tableName() +
                                    " WHERE _parent_id = :parentId ORDER BY _line_number")
                            .bind("parentId", id)
                            .mapToMap()
                            .list()
            );
            EntityQuerySupport.decorateRows(refResolver, ts.attributes(), rows);
            doc.put(ts.name(), rows);
        }
        return doc;
    }
}
