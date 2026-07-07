package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.query.Cursor;
import su.onno.query.Keyset;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CatalogQueryService.class);

    /**
     * Hard safety cap for the un-paged list APIs, so one request can never pull an
     * entire large table (and ref-resolve every row) into memory. Callers that need
     * more rows should use the paged/searched variants.
     */
    static final int MAX_LIST_ROWS = 1000;

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
        return list(desc, null);
    }

    /**
     * The full catalog list, optionally narrowed by an authored {@link WidgetFilter} predicate (the
     * same {@code config("filter", …)} a dashboard card uses). The un-paged endpoint that chart/list
     * widgets fetch from goes through here, so passing the predicate makes those widgets honor the
     * filter consistently with the server-aggregated count tiles. A null/blank/invalid predicate is
     * simply no filter.
     */
    public List<Map<String, Object>> list(CatalogDescriptor desc, String filter) {
        EntitySurfaceDescriptor surface = surface(desc);
        WidgetFilter.Result wf = WidgetFilter.parse(filter, surface.columnNames());
        String where = "_deletion_mark = false" + (wf.isEmpty() ? "" : " AND (" + wf.sql() + ")");
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where + " ORDER BY _code LIMIT :limit")
                    .bind("limit", MAX_LIST_ROWS + 1);
            wf.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        if (rows.size() > MAX_LIST_ROWS) {
            log.warn("Catalog '{}' has more than {} live records; the un-paged list API truncated "
                    + "the result. Use the paged list endpoint for complete data.",
                    desc.logicalName(), MAX_LIST_ROWS);
            rows = rows.subList(0, MAX_LIST_ROWS);
        }
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return rows;
    }

    /**
     * Server-side typeahead for ref pickers: case-insensitive match across the same text columns the
     * paged list search covers — code, description, and every (non-secret) String attribute — so a
     * record is findable by a secondary attribute like a phone, not just its name (issue #184).
     * Capped at {@code limit}, so a 2000-row catalog never ships whole to the client.
     */
    public List<Map<String, Object>> search(CatalogDescriptor desc, String query, int limit) {
        EntitySurfaceDescriptor surface = surface(desc);
        String where = "_deletion_mark = false" + searchClause(surface, query);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY _description LIMIT :limit")
                    .bind("limit", limit);
            EntityQuerySupport.bindSearch(q, query);
            return q.mapToMap().list();
        });
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return rows;
    }

    /**
     * The seed row for a <em>new</em> catalog form: a fresh instance's field-initializer defaults in
     * the same column-keyed, ref-resolved shape {@link #get} returns for an existing record, so the
     * New form pre-fills declared defaults instead of opening blank (issue #181).
     */
    public Map<String, Object> newDraft(CatalogDescriptor desc) {
        Map<String, Object> row = NewEntityDefaults.columnValues(desc.javaClass(), desc.attributes(), registry);
        refResolver.resolveAttributes(List.of(row), desc.attributes());
        return row;
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
        return EntityQuerySupport.aggregate(jdbi, surface(desc), metric, field, filter);
    }

    /**
     * Grouped aggregate buckets for a chart/stat widget — a server-side {@code GROUP BY} returning
     * O(buckets) rows instead of the whole table (#199). See {@link WidgetBuckets}.
     */
    public Map<String, Object> aggregateBuckets(CatalogDescriptor desc, WidgetBuckets.Request request) {
        return EntityQuerySupport.aggregateBuckets(jdbi, refResolver, surface(desc), request);
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
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
                                           List<String> eq, List<String> in, List<String> like,
                                           List<String> prefix, List<String> ge, List<String> le,
                                           String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        String orderBy = surface.safeSort(sortColumn);
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames());
        String where = "_deletion_mark = false" + searchClause(surface, search) + filterClause(filter) + filterClause(wf);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                    " WHERE " + where +
                    " ORDER BY " + orderBy + (descending ? " DESC" : " ASC") +
                    " LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", Math.max(0, offset));
            EntityQuerySupport.bindSearch(q, search);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return rows;
    }

    /**
     * One keyset-paginated window — the constant-time default for the list grid. Seeks past
     * {@code cursorToken} (null/blank for the first window) instead of counting past an offset, so
     * page 1 and page 10 000 cost the same. Same server-side sort/search/filters as {@link #page};
     * fetches one extra row to report {@code hasMore} without a COUNT, and mints the next cursor from
     * the last row. A cursor minted for a different sort is ignored (paging restarts), and a sort by
     * a column the framework doesn't keep populated uses the NULL-safe seek shape.
     */
    public KeysetPage keysetPage(CatalogDescriptor desc, String cursorToken, int limit,
                                 String sortColumn, boolean descending, String search,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        String col = surface.safeSort(sortColumn);
        Cursor cursor = Cursor.decodeFor(cursorToken, col, descending);
        Keyset.Plan plan = Keyset.plan(col, descending, !surface.isNonNullableSort(col), cursor);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames());
        String where = "_deletion_mark = false" + searchClause(surface, search)
                + filterClause(filter) + filterClause(wf) + plan.predicate();
        int lim = Keyset.clampLimit(limit);

        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY " + plan.orderBy() +
                            " LIMIT :limit")
                    .bind("limit", lim + 1); // one extra row tells us whether another window exists
            EntityQuerySupport.bindSearch(q, search);
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
        // Mint the cursor from the raw last row before ref-resolution/redaction reshape the map.
        String nextCursor = (hasMore && !rows.isEmpty())
                ? Cursor.from(col, descending, rows.get(rows.size() - 1)).encode()
                : null;
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
        return new KeysetPage(rows, nextCursor, hasMore);
    }

    /**
     * Whether {@code column} is safe for the fast (index-only) keyset seek — i.e. the framework keeps
     * it populated, so the seek never has to reason about NULLs. True for {@code _code} (always
     * generated) and any {@code required} attribute; {@code _description} and optional attributes use
     * the NULL-safe seek instead.
     */
    /**
     * A cheap live-row estimate for the scroll-height hint, or {@code null} when none is available.
     * Uses PostgreSQL planner statistics ({@code pg_class.reltuples}) so it never scans the table;
     * returns {@code null} on H2 or whenever a search/filter is active (the estimate can't reflect a
     * predicate). Callers wanting an exact figure use {@link #count} instead.
     */
    public Long estimateCount(CatalogDescriptor desc, boolean filtered) {
        return EntityQuerySupport.estimateCount(jdbi, surface(desc), filtered);
    }

    /**
     * Fetch specific live rows by id, decorated exactly like {@link #page} (refs resolved, secrets
     * redacted) so a client can refresh just the rows that changed without re-paging the whole
     * window. Drives the list island's surgical single-row live patch. Returns only the rows that
     * still exist and aren't deletion-marked, in no particular order; an empty/blank input yields an
     * empty list.
     */
    public List<Map<String, Object>> rowsByIds(CatalogDescriptor desc, List<UUID> ids) {
        return EntityQuerySupport.rowsByIds(jdbi, refResolver, surface(desc), ids);
    }

    /** Total live rows matching the search (+ declarative filters + widget filter) — for the virtual scroller. */
    public long count(CatalogDescriptor desc, String search,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le,
                      String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le, surface.filterableColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames());
        String where = "_deletion_mark = false" + searchClause(surface, search) + filterClause(filter) + filterClause(wf);
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            EntityQuerySupport.bindSearch(q, search);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            return q.mapTo(Long.class).one();
        });
    }

    /**
     * Group a catalog list by {@code groupColumn} (a validated sortable column): one header per
     * distinct value, or — for a date/time column — per {@code granularity} bucket, over the same
     * WHERE (search + declarative + widget filters) as the flat list. Each header carries its row
     * count, the requested {@code aggregates}, and the {@code expand} filter the client replays on the
     * normal feed to load that group's rows. Headers are capped at {@link ListGroups#MAX_GROUPS}.
     */
    public ListGroups.GroupResult groups(CatalogDescriptor desc, String groupColumn, String granularity,
                                         String search, List<String> eq, List<String> in, List<String> like,
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
        String where = "_deletion_mark = false" + searchClause(surface, search) + filterClause(filter) + filterClause(wf);

        // Aggregate select list: drop any the validator rejects (unknown fn/column) rather than fail.
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
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            return q.mapToMap().list();
        });
        boolean capped = rows.size() > ListGroups.MAX_GROUPS;
        if (capped) {
            rows = new ArrayList<>(rows.subList(0, ListGroups.MAX_GROUPS));
        }
        // Resolve the group column if it's a ref/enum so the header reads as a label (+ pill colour),
        // not a raw UUID/code; a no-op for a date bucket (not a ref).
        refResolver.resolveAttributes(rows, desc.attributes());
        return new ListGroups.GroupResult(
                ListGroups.buildGroups(rows, groupColumn, date, granularity, valid), capped);
    }

    /** Whether a group column is a date/time — so grouping buckets it by period (see {@link ListGroups}). */
    private static boolean isTemporalColumn(CatalogDescriptor desc, String column) {
        if (column.equals("_date") || column.equals("_period")) {
            return true;
        }
        return desc.attributes().stream()
                .anyMatch(a -> a.columnName().equalsIgnoreCase(column) && ListGroups.isTemporalType(a.javaType()));
    }

    private static String filterClause(ListFilter.Result filter) {
        return EntityQuerySupport.filterClause(filter);
    }

    private static String filterClause(WidgetFilter.Result filter) {
        return EntityQuerySupport.filterClause(filter);
    }

    /** Column names that may be sorted on: the system columns + every attribute column. */
    public Set<String> sortableColumns(CatalogDescriptor desc) {
        return surface(desc).sortableColumns();
    }

    /**
     * The free-text search predicate for {@code q}: matches the term against <em>every</em> non-secret
     * column, not just strings — the system code/description, every scalar attribute (numbers and dates
     * cast to text), each {@code Ref<>} by the <em>displayed</em> value of its target (so typing a
     * customer's name finds their orders), and each enum by its label/name. One bound {@code :search}
     * ({@code %term%}, lowercased) drives every term.
     */
    private String searchClause(EntitySurfaceDescriptor surface, String search) {
        return EntityQuerySupport.searchClause(registry, surface, search);
    }

    private static EntitySurfaceDescriptor surface(CatalogDescriptor desc) {
        return EntitySurfaceDescriptor.catalog(desc);
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows);
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), List.of(row));
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
