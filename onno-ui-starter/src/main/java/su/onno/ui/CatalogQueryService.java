package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;

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
 * so the SQL and ref-resolution live in one place.
 *
 * <p>Every read takes the {@link AccessSubject} it is made for. Entity-level RBAC
 * ({@code @AccessControl}) stays with the callers; the subject's <strong>record scope</strong>
 * ({@link su.onno.access.RecordAccessPolicy}) is applied here, inside the {@code WHERE}, because it
 * is part of the query — lists, counts, groups, aggregates, search, trees and {@code get} all see
 * only the subject's rows. Trusted code passes {@link AccessSubject#system()}.
 */
public class CatalogQueryService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;
    private final RecordScopeCompiler scopes;
    private final UiAccessService access;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public CatalogQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new UiAccessService(registry));
    }

    public CatalogQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                               UiAccessService access) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.access = access;
        this.refResolver = new RefResolver(registry, jdbi, scopes, access);
    }

    /** The ref resolver bound to this service's policies. */
    @SuppressWarnings("unused") // Public API available to consumer applications.
    public RefResolver refResolver() {
        return refResolver;
    }

    /** The record-scope compiler this service applies. */
    public RecordScopeCompiler scopes() {
        return scopes;
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

    /**
     * Server-side typeahead for ref pickers: case-insensitive match across the same text columns the
     * paged list search covers — code, description, and every (non-secret) String attribute — so a
     * record is findable by a secondary attribute like a phone, not just its name (issue #184).
     * Capped at {@code limit}, so a 2000-row catalog never ships whole to the client.
     */
    public List<Map<String, Object>> search(AccessSubject subject, CatalogDescriptor desc, String query, int limit) {
        return search(subject, desc, query, limit, null);
    }

    /**
     * As {@link #search(AccessSubject, CatalogDescriptor, String, int)}, additionally narrowed by a
     * {@link WidgetFilter} predicate — the cascading ref picker sends its resolved
     * {@code refFilter} here (e.g. {@code "supplier = <uuid>"}), so only compatible records are
     * offered. Ref/enum columns bind as typed uuids (PG-strict); a null/blank/invalid predicate is
     * simply no filter.
     */
    public List<Map<String, Object>> search(AccessSubject subject, CatalogDescriptor desc, String query,
                                            int limit, String filter) {
        return search(subject, desc, query, limit, filter, null);
    }

    /**
     * As {@link #search(AccessSubject, CatalogDescriptor, String, int, String)}, additionally restricted to
     * the given ids when {@code ids} is non-null: the ref picker's "recent" section re-reads its
     * remembered picks through here, so they pass the same deletion mark, record scope and
     * {@code refFilter} as ordinary options. An empty id list yields no rows.
     */
    public List<Map<String, Object>> search(AccessSubject subject, CatalogDescriptor desc, String query,
                                            int limit, String filter, java.util.Collection<UUID> ids) {
        if (ids != null && ids.isEmpty()) {
            return new ArrayList<>();
        }
        EntitySurfaceDescriptor surface = surface(desc);
        WidgetFilter.Result wf = widgetFilter(desc, surface, filter);
        ScopeClause scope = scope(subject, surface);
        ScopeClause search = searchClause(subject, surface, query);
        String where = "_deletion_mark = false"
                + (wf.isEmpty() ? "" : " AND (" + wf.sql() + ")")
                + (ids == null ? "" : " AND _id IN (<ids>)")
                + search.and() + scope.and();
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY _description LIMIT :limit")
                    .bind("limit", limit);
            wf.bindings().forEach(q::bind);
            if (ids != null) {
                q.bindList("ids", List.copyOf(ids));
            }
            EntityQuerySupport.bindSearch(q, query);
            search.bind(q);
            scope.bind(q);
            return q.mapToMap().list();
        });
        if (desc.hierarchical()) EntityQuerySupport.maskParents(jdbi, desc.tableName(), rows, scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return rows;
    }

    /**
     * The seed row for a <em>new</em> catalog form: a fresh instance's field-initializer defaults in
     * the same column-keyed, ref-resolved shape {@link #get} returns for an existing record, so the
     * New form pre-fills declared defaults instead of opening blank (issue #181).
     */
    @SuppressWarnings("unused") // Public API available to consumer applications.
    public Map<String, Object> newDraft(AccessSubject subject, CatalogDescriptor desc) {
        return newDraft(subject, desc, Map.of());
    }

    /**
     * As {@link #newDraft(AccessSubject, CatalogDescriptor)}, but overlays caller-supplied initial values (from the
     * New-form navigation query, keyed by attribute field name) onto the seed row before ref/enum
     * resolution — so a deep link like {@code …/new?field=value} pre-fills those fields. The
     * subject's record-policy {@code defaults(...)} are applied last, as the create will.
     */
    public Map<String, Object> newDraft(AccessSubject subject, CatalogDescriptor desc, Map<String, String> prefill) {
        Map<String, Object> row = NewEntityDefaults.columnValues(desc.javaClass(), desc.attributes(), registry);
        NewEntityDefaults.applyPrefill(row, desc.attributes(), EntityQuerySupport.withDefaults(prefill,
                EntityQuerySupport.policyDefaults(scopes, desc.javaClass(), subject)));
        refResolver.resolveAttributes(List.of(row), desc.attributes(), subject);
        return row;
    }

    public long count(AccessSubject subject, CatalogDescriptor desc) {
        ScopeClause scope = scope(subject, surface(desc));
        return jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT COUNT(*) FROM " + desc.tableName()
                                + " WHERE _deletion_mark = false" + scope.and()))
                        .mapTo(Long.class)
                        .one());
    }

    /**
     * A single aggregate value for a count/metric card — {@code count} of rows, or
     * {@code sum|avg|min|max} of one numeric column — restricted to live records and
     * narrowed by an optional safe {@code filter} predicate (see {@link WidgetFilter}).
     */
    public BigDecimal aggregate(AccessSubject subject, CatalogDescriptor desc, String metric, String field,
                                String filter) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.aggregate(jdbi, surface, metric, field, filter, scope(subject, surface));
    }

    /**
     * Grouped aggregate buckets for a chart/stat widget — a server-side {@code GROUP BY} returning
     * O(buckets) rows instead of the whole table (#199). See {@link WidgetBuckets}.
     */
    public Map<String, Object> aggregateBuckets(AccessSubject subject, CatalogDescriptor desc,
                                                WidgetBuckets.Request request) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.aggregateBuckets(jdbi, refResolver, surface, request,
                scope(subject, surface), subject);
    }

    /**
     * One keyset-paginated window — the constant-time default for the list grid. Seeks past
     * {@code cursorToken} (null/blank for the first window) instead of counting past an offset, so
     * page 1 and page 10 000 cost the same. Applies server-side sort/search/filters, fetches one
     * extra row to report {@code hasMore} without a COUNT, and mints the next cursor from
     * the last row. A cursor minted for a different sort is ignored (paging restarts), and a sort by
     * a column the framework doesn't keep populated uses the NULL-safe seek shape. A cursor is bound
     * to the subject that minted it (a record-scoped subject's cursor carries its fingerprint, and
     * replaying it as another subject is a 400).
     */
    public KeysetPage keysetPage(AccessSubject subject, CatalogDescriptor desc, String cursorToken, int limit,
                                 String sortColumn, boolean descending, String search,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String widgetFilter) {
        return keysetPage(subject, desc, cursorToken, null, null, limit, sortColumn, descending, search,
                eq, in, like, prefix, ge, le, widgetFilter);
    }

    /**
     * As {@link #keysetPage(AccessSubject, CatalogDescriptor, String, int, String, boolean, String,
     * List, List, List, List, List, List, String)}, at a chosen position: {@code beforeToken} (a
     * window's {@code prevCursor}) returns the rows just above that window, and {@code focus} returns
     * the window containing that record — up to half the limit of rows before it, the record, and
     * the rows after it, with {@code prevCursor}/{@code nextCursor} to page further either way. A
     * focused record the subject's query cannot see (filtered out, out of scope, deleted, unknown)
     * yields the ordinary first window with no focus. {@code cursorToken} wins over
     * {@code beforeToken}, which wins over {@code focus}.
     */
    public KeysetPage keysetPage(AccessSubject subject, CatalogDescriptor desc, String cursorToken,
                                 String beforeToken, UUID focus, int limit,
                                 String sortColumn, boolean descending, String search,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        String fingerprint = scopes.policies().fingerprint(subject);
        String col = surface.safeSort(sortColumn);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        WidgetFilter.Result wf = widgetFilter(desc, surface, widgetFilter);
        ScopeClause scope = scope(subject, surface);
        ScopeClause searchClause = searchClause(subject, surface, search);
        String where = "_deletion_mark = false" + searchClause.and()
                + filterClause(filter) + filterClause(wf) + scope.and();

        KeysetWindow.Raw window = KeysetWindow.fetch(jdbi, desc.tableName(), where, q -> {
                    EntityQuerySupport.bindSearch(q, search);
                    searchClause.bind(q);
                    filter.bindings().forEach(q::bind);
                    wf.bindings().forEach(q::bind);
                    scope.bind(q);
                }, col, descending, !surface.isNonNullableSort(col),
                new KeysetWindow.Position(cursorToken, beforeToken, focus), limit, fingerprint);

        // Cursors were minted from the raw rows; ref-resolution/redaction reshape the maps after.
        List<Map<String, Object>> rows = window.rows();
        if (desc.hierarchical()) EntityQuerySupport.maskParents(jdbi, desc.tableName(), rows, scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return window.decorated();
    }

    /**
     * A cheap live-row estimate for the scroll-height hint, or {@code null} when none is available.
     * Uses PostgreSQL planner statistics ({@code pg_class.reltuples}) so it never scans the table;
     * returns {@code null} on H2 or whenever a search/filter is active (the estimate can't reflect a
     * predicate). Callers wanting an exact figure use {@link #count} instead. For a record-scoped
     * subject the planner estimate would reveal the whole table's size, so the exact count of the
     * subject's rows is returned instead (still {@code null} while filtered).
     */
    public Long estimateCount(AccessSubject subject, CatalogDescriptor desc, boolean filtered) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.estimateCount(jdbi, surface, filtered, scope(subject, surface));
    }

    /**
     * Fetch specific live rows by id, decorated like keyset rows (refs resolved, secrets
     * redacted) so a client can refresh just the rows that changed without re-paging the whole
     * window. Drives the list island's surgical single-row live patch. Returns only the rows that
     * still exist and aren't deletion-marked, in no particular order; an empty/blank input yields an
     * empty list. Ids outside the subject's scope are silently dropped.
     */
    public List<Map<String, Object>> rowsByIds(AccessSubject subject, CatalogDescriptor desc, List<UUID> ids) {
        EntitySurfaceDescriptor surface = surface(desc);
        ScopeClause scope = scope(subject, surface);
        List<Map<String, Object>> rows = EntityQuerySupport.rowsByIds(jdbi, refResolver, surface, ids, scope, subject);
        if (desc.hierarchical()) EntityQuerySupport.maskParents(jdbi, desc.tableName(), rows, scope);
        return rows;
    }

    /** Total live rows matching the search (+ declarative filters + widget filter) — for the virtual scroller. */
    public long count(AccessSubject subject, CatalogDescriptor desc, String search,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le,
                      String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        WidgetFilter.Result wf = widgetFilter(desc, surface, widgetFilter);
        ScopeClause scope = scope(subject, surface);
        ScopeClause searchClause = searchClause(subject, surface, search);
        String where = "_deletion_mark = false" + searchClause.and() + filterClause(filter) + filterClause(wf)
                + scope.and();
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            EntityQuerySupport.bindSearch(q, search);
            searchClause.bind(q);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            scope.bind(q);
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
    public ListGroups.GroupResult groups(AccessSubject subject, CatalogDescriptor desc, String groupColumn,
                                         String granularity,
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

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        WidgetFilter.Result wf = widgetFilter(desc, surface, widgetFilter);
        ScopeClause scope = scope(subject, surface);
        ScopeClause searchClause = searchClause(subject, surface, search);
        String where = "_deletion_mark = false" + searchClause.and() + filterClause(filter) + filterClause(wf)
                + scope.and();

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
            searchClause.bind(q);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            scope.bind(q);
            return q.mapToMap().list();
        });
        boolean capped = rows.size() > ListGroups.MAX_GROUPS;
        if (capped) {
            rows = new ArrayList<>(rows.subList(0, ListGroups.MAX_GROUPS));
        }
        // Resolve the group column if it's a ref/enum so the header reads as a label (+ pill colour),
        // not a raw UUID/code; a no-op for a date bucket (not a ref). The raw value stays: it is the
        // group's expand key (a restricted target only has its label masked).
        refResolver.resolveAttributes(rows, desc.attributes(), subject, false);
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
    @SuppressWarnings("unused") // Public API available to consumer applications.
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
    private ScopeClause searchClause(AccessSubject subject, EntitySurfaceDescriptor surface, String search) {
        return EntityQuerySupport.searchClause(registry, surface, search,
                EntityQuerySupport.targetGate(scopes, access, subject));
    }

    private ScopeClause scope(AccessSubject subject, EntitySurfaceDescriptor surface) {
        return EntityQuerySupport.readScope(scopes, surface, subject);
    }

    private static EntitySurfaceDescriptor surface(CatalogDescriptor desc) {
        return EntitySurfaceDescriptor.catalog(desc);
    }

    /**
     * A catalog's {@link WidgetFilter} over its attribute columns and, for a hierarchical catalog,
     * {@code _parent} (bound as a uuid), so a picker dialog can list one folder's children with
     * {@code _parent = <uuid>} or the roots with {@code _parent = null} (#411).
     */
    private static WidgetFilter.Result widgetFilter(CatalogDescriptor desc, EntitySurfaceDescriptor surface,
                                                    String filter) {
        if (!desc.hierarchical()) {
            return WidgetFilter.parse(filter, surface.columnNames(), surface.uuidColumns(), surface::storageColumn);
        }
        Set<String> columns = new java.util.HashSet<>(surface.columnNames());
        columns.add("_parent");
        Set<String> uuids = new java.util.HashSet<>(surface.uuidColumns());
        uuids.add("_parent");
        return WidgetFilter.parse(filter, columns, uuids, surface::storageColumn);
    }

    void validateWidgetFilter(CatalogDescriptor desc, String filter) {
        widgetFilter(desc, surface(desc), filter);
    }

    /**
     * The live children of {@code parent} ({@code null}: the roots). For a record-scoped subject the
     * scope applies per node, and an in-scope record whose parent is out of scope is returned as a
     * root (with its parent withheld).
     */
    public List<Map<String, Object>> children(AccessSubject subject, CatalogDescriptor desc, UUID parent) {
        return children(subject, desc, parent, false);
    }

    /**
     * As {@link #children(AccessSubject, CatalogDescriptor, UUID)}; with {@code foldersOnly} just the
     * folders, each flagged {@code _has_child_folders} when it holds a live sub-folder the subject
     * may read, so a folder tree draws an expand arrow only where there is something to expand
     * (#411). The flag applies the same record scope as the rows, so it never reveals a hidden
     * sub-folder.
     */
    public List<Map<String, Object>> children(AccessSubject subject, CatalogDescriptor desc, UUID parent,
                                              boolean foldersOnly) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        String table = desc.tableName();
        ScopeClause scope = scope(subject, surface(desc));
        ScopeClause parentScope = scope.isAll() ? ScopeClause.ALL
                : scopes.clause(desc.javaClass(), subject, su.onno.access.AccessMode.READ, "_tp");
        String roots = scope.isAll()
                ? table + "._parent IS NULL"
                : "(" + table + "._parent IS NULL OR NOT EXISTS (SELECT 1 FROM " + table + " _tp WHERE _tp._id = "
                        + table + "._parent" + parentScope.and() + "))";
        ScopeClause childScope = !foldersOnly || scope.isAll() ? ScopeClause.ALL
                : scopes.clause(desc.javaClass(), subject, su.onno.access.AccessMode.READ, "_tc");
        String childFlag = foldersOnly
                ? ", EXISTS (SELECT 1 FROM " + table + " _tc WHERE _tc._parent = " + table + "._id"
                        + " AND _tc._is_folder = true AND _tc._deletion_mark = false" + childScope.and()
                        + ") AS _has_child_folders"
                : "";
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            String sql = "SELECT " + table + ".*" + childFlag + " FROM " + table +
                    " WHERE _deletion_mark = false AND " +
                    (foldersOnly ? table + "._is_folder = true AND " : "") +
                    (parent == null ? roots : table + "._parent = :parent") + scope.and() +
                    " ORDER BY _is_folder DESC, _description";
            var query = h.createQuery(sql);
            if (parent != null) query.bind("parent", parent);
            scope.bind(query);
            if (parent == null) parentScope.bind(query);
            childScope.bind(query);
            return query.mapToMap().list();
        });
        EntityQuerySupport.maskParents(jdbi, table, rows, scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return rows;
    }

    /** The whole live tree the subject may read; in-scope nodes under an out-of-scope parent become roots. */
    public List<Map<String, Object>> tree(AccessSubject subject, CatalogDescriptor desc) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        ScopeClause scope = scope(subject, surface(desc));
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false" + scope.and() + " ORDER BY _is_folder DESC, _description"))
                        .mapToMap()
                        .list()
        );
        EntityQuerySupport.maskParents(jdbi, desc.tableName(), rows, scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return buildTree(rows, null);
    }

    /** Upper bound of records one {@link #paths} call resolves, and of the ancestor levels it walks. */
    static final int MAX_PATH_IDS = 200;
    static final int MAX_PATH_DEPTH = 64;

    /**
     * The tree pruned to the paths from the roots to {@code ids} (#411): each requested record the
     * subject may read, plus its ancestors, nested through {@code children} like {@link #tree}. The
     * walk goes up through readable, live ancestors only; where the next one is out of scope or
     * deleted the path starts there (the node becomes a root, its parent withheld as in
     * {@link #children}). Unknown, deleted or unreadable ids are left out, so the answer names
     * nothing the subject cannot see. A picker opens on a value or labels search hits with one call.
     */
    public List<Map<String, Object>> paths(AccessSubject subject, CatalogDescriptor desc,
                                           java.util.Collection<UUID> ids) {
        if (!desc.hierarchical()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog is not hierarchical: " + desc.logicalName());
        }
        ScopeClause scope = scope(subject, surface(desc));
        Map<UUID, Map<String, Object>> found = new LinkedHashMap<>();
        java.util.Set<UUID> wanted = new java.util.LinkedHashSet<>();
        ids.stream().filter(Objects::nonNull).limit(MAX_PATH_IDS).forEach(wanted::add);
        for (int depth = 0; !wanted.isEmpty() && depth <= MAX_PATH_DEPTH; depth++) {
            List<UUID> batch = List.copyOf(wanted);
            List<Map<String, Object>> rows = jdbi.withHandle(h -> scope.bind(h.createQuery(
                            "SELECT * FROM " + desc.tableName()
                                    + " WHERE _deletion_mark = false AND _id IN (<ids>)" + scope.and()))
                    .bindList("ids", batch)
                    .mapToMap().list());
            wanted.clear();
            for (Map<String, Object> row : rows) {
                UUID id = parseUuid(row.get("_id"));
                if (found.putIfAbsent(id, row) == null) {
                    UUID parent = parseUuid(row.get("_parent"));
                    if (parent != null && !found.containsKey(parent)) {
                        wanted.add(parent);
                    }
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(found.values());
        // A parent the walk did not reach (out of scope, deleted, or past the depth cap) is withheld,
        // so its child is presented as a root exactly like children()/tree() present it.
        for (Map<String, Object> row : rows) {
            UUID parent = parseUuid(row.get("_parent"));
            if (parent != null && !found.containsKey(parent)) {
                row.put("_parent", null);
                row.put("_parent_restricted", true);
            }
        }
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
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
    public List<Map<String, Object>> relatedRows(AccessSubject subject, CatalogDescriptor desc, String viaColumn,
                                                 UUID parentId) {
        ScopeClause scope = scope(subject, surface(desc));
        List<Map<String, Object>> rows = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false AND " + viaColumn + " = :parent" + scope.and() +
                                " ORDER BY _code"))
                        .bind("parent", parentId)
                        .mapToMap()
                        .list()
        );
        if (desc.hierarchical()) EntityQuerySupport.maskParents(jdbi, desc.tableName(), rows, scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return rows;
    }

    /** One record; a record outside the subject's scope is a 404, exactly like a missing one. */
    public Map<String, Object> get(AccessSubject subject, CatalogDescriptor desc, UUID id) {
        ScopeClause scope = scope(subject, surface(desc));
        Map<String, Object> row = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id" + scope.and()))
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
        if (desc.hierarchical()) EntityQuerySupport.maskParents(jdbi, desc.tableName(), List.of(row), scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), List.of(row), subject);
        for (var section : desc.tabularSections()) {
            List<Map<String, Object>> lines = jdbi.withHandle(h -> h.createQuery(
                            "SELECT * FROM " + section.tableName()
                                    + " WHERE _parent_id = :id ORDER BY _line_number")
                    .bind("id", id).mapToMap().list());
            EntityQuerySupport.decorateRows(refResolver, section.attributes(), lines, subject);
            row.put(section.name(), lines);
        }
        return row;
    }

    /** Whether record {@code id} exists within the subject's scope for {@code mode} (deleted rows included). */
    public boolean inScope(AccessSubject subject, CatalogDescriptor desc, UUID id, su.onno.access.AccessMode mode) {
        ScopeClause scope = scopes.clause(desc.javaClass(), subject, mode, desc.tableName());
        return jdbi.withHandle(h -> scope.bind(h.createQuery(
                        "SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _id = :id" + scope.and()))
                .bind("id", id)
                .mapTo(Long.class).one() > 0);
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
