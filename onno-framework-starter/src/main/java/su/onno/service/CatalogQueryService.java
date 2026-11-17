package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side queries for catalogs, shared by the REST API and the view descriptors
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
    private final AccessService access;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public CatalogQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new AccessService(registry));
    }

    public CatalogQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                               AccessService access) {
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
                .orElseThrow(() -> new ServiceException(ServiceException.Status.NOT_FOUND,
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
        return search(subject, desc, query, limit, (java.util.Collection<UUID>) null);
    }


    /**
     * As {@link #search}, additionally restricted to
     * the given ids when {@code ids} is non-null: the ref picker's "recent" section re-reads its
     * remembered picks through here, so they pass the same deletion mark, record scope and
     * {@code refFilter} as ordinary options. An empty id list yields no rows.
     */
    public List<Map<String, Object>> search(AccessSubject subject, CatalogDescriptor desc, String query,
                                            int limit, java.util.Collection<UUID> ids) {
        return search(subject, desc, query, limit, null, ids);
    }

    /**
     * As {@link #search},
     * additionally narrowed by a structured {@link Where} predicate (JSON), ANDed with {@code filter}.
     */
    public List<Map<String, Object>> search(AccessSubject subject, CatalogDescriptor desc, String query,
                                            int limit, String whereJson,
                                            java.util.Collection<UUID> ids) {
        if (ids != null && ids.isEmpty()) {
            return new ArrayList<>();
        }
        EntitySurfaceDescriptor surface = surface(desc);
        SqlPredicate wf = predicate(desc, surface, whereJson);
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
     * As {@link #newDraft}, but overlays caller-supplied initial values (from the
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
     * Grouped aggregate buckets for a chart/stat widget — a server-side {@code GROUP BY} returning
     * O(buckets) rows instead of the whole table (#199). See {@link AggregateBuckets}.
     */
    public Map<String, Object> aggregateBuckets(AccessSubject subject, CatalogDescriptor desc,
                                                AggregateBuckets.Request request) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.aggregateBuckets(jdbi, refResolver, surface, request,
                scope(subject, surface), subject, whereSchema(desc, surface));
    }



    /** The window after {@code cursorToken} (the first window when it is null); see the full overload. */
    public KeysetPage keysetPage(AccessSubject subject, CatalogDescriptor desc, String cursorToken, int limit,
                                 String sortColumn, boolean descending, String search,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String whereJson) {
        return keysetPage(subject, desc, cursorToken, null, null, limit, sortColumn, descending, search,
                eq, in, like, prefix, ge, le, whereJson);
    }

    /**
     * As {@link #keysetPage}, additionally narrowed by a structured
     * {@link Where} predicate (JSON) — so it shapes the window, the
     * focus window and the cursors.
     */
    public KeysetPage keysetPage(AccessSubject subject, CatalogDescriptor desc, String cursorToken,
                                 String beforeToken, UUID focus, int limit,
                                 String sortColumn, boolean descending, String search,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String whereJson) {
        EntitySurfaceDescriptor surface = surface(desc);
        String fingerprint = su.onno.query.Cursor.context(EntityQuerySupport.cursorSubject(subject),
                scopes.policies().fingerprint(subject), search, eq, in, like, prefix, ge, le, whereJson);
        String col = surface.safeSort(sortColumn);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        SqlPredicate wf = predicate(desc, surface, whereJson);
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


    /** As {@link #count}, plus a structured {@link Where}. */
    public long count(AccessSubject subject, CatalogDescriptor desc, String search,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le,
                      String whereJson) {
        EntitySurfaceDescriptor surface = surface(desc);
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        SqlPredicate wf = predicate(desc, surface, whereJson);
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


    /** As {@link #groups}, plus a structured {@link Where}. */
    public ListGroups.GroupResult groups(AccessSubject subject, CatalogDescriptor desc, String groupColumn,
                                         String granularity,
                                         String search, List<String> eq, List<String> in, List<String> like,
                                         List<String> prefix, List<String> ge, List<String> le,
                                         String whereJson,
                                         List<ListGroups.Agg> aggregates) {
        EntitySurfaceDescriptor surface = surface(desc);
        if (groupColumn == null || !surface.sortableColumns().contains(groupColumn)) {
            return new ListGroups.GroupResult(List.of(), false);
        }
        Set<String> columns = surface.columnNames();
        boolean date = isTemporalColumn(desc, groupColumn);
        String groupExpr = ListGroups.groupExpression(groupColumn, date, granularity);

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        SqlPredicate wf = predicate(desc, surface, whereJson);
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
                select.append(", ").append(Aggregates.expression(a.fn(), a.column(), columns))
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
            rows.removeLast();
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

    private static String filterClause(SqlPredicate filter) {
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

    /** The structured {@link Where} over a catalog. */
    private static SqlPredicate predicate(CatalogDescriptor desc, EntitySurfaceDescriptor surface, String whereJson) {
        return Where.compile(whereJson, whereSchema(desc, surface));
    }

    /** The catalog's {@link Where} schema; a hierarchical catalog also exposes {@code parent}. */
    private static Where.Schema whereSchema(CatalogDescriptor desc, EntitySurfaceDescriptor surface) {
        Where.Schema schema = surface.whereSchema();
        return desc.hierarchical() ? schema.with("_parent", Where.Type.UUID) : schema;
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
     * As {@link #children}; with {@code foldersOnly} just the
     * folders, each flagged {@code _has_child_folders} when it holds a live sub-folder the subject
     * may read, so a folder tree draws an expand arrow only where there is something to expand
     * (#411). The flag applies the same record scope as the rows, so it never reveals a hidden
     * sub-folder.
     */
    public List<Map<String, Object>> children(AccessSubject subject, CatalogDescriptor desc, UUID parent,
                                              boolean foldersOnly) {
        return su.onno.query.KeysetWindow.drain(c -> childrenPage(subject, desc, parent, foldersOnly, c, su.onno.query.KeysetWindow.MAX_LIMIT));
    }

    public su.onno.query.KeysetWindow.Page<Map<String, Object>> childrenPage(AccessSubject subject,
            CatalogDescriptor desc, UUID parent, boolean foldersOnly, String cursor, int limit) {
        if (!desc.hierarchical()) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST,
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
        var page = KeysetWindow.collection(jdbi, table, table + ".*" + childFlag,
                "_deletion_mark = false AND " + (foldersOnly ? table + "._is_folder = true AND " : "")
                        + (parent == null ? roots : table + "._parent = :parent") + scope.and(), q -> {
                    if (parent != null) q.bind("parent", parent);
                    scope.bind(q);
                    if (parent == null) parentScope.bind(q);
                    childScope.bind(q);
                }, List.of(new su.onno.query.KeysetWindow.Order("_is_folder", true),
                        new su.onno.query.KeysetWindow.Order("_description", false),
                        new su.onno.query.KeysetWindow.Order("_id", false)), cursor, limit,
                "children:" + table + ":" + parent + ":" + foldersOnly + ":" + scopes.policies().fingerprint(subject) + ":" + EntityQuerySupport.cursorSubject(subject));
        List<Map<String, Object>> rows = page.rows();
        EntityQuerySupport.maskParents(jdbi, table, rows, scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return page;
    }

    /** The whole live tree the subject may read; in-scope nodes under an out-of-scope parent become roots. */
    public List<Map<String, Object>> tree(AccessSubject subject, CatalogDescriptor desc) {
        return buildTree(su.onno.query.KeysetWindow.drain(c -> treePage(subject, desc, c, su.onno.query.KeysetWindow.MAX_LIMIT)), null);
    }

    /** Flat paged nodes: parent links allow a client to assemble the hierarchy across windows. */
    public su.onno.query.KeysetWindow.Page<Map<String, Object>> treePage(AccessSubject subject,
            CatalogDescriptor desc, String cursor, int limit) {
        if (!desc.hierarchical()) throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Catalog is not hierarchical");
        ScopeClause scope = scope(subject, surface(desc));
        var page = KeysetWindow.collection(jdbi, desc.tableName(), "*", "_deletion_mark = false" + scope.and(),
                scope::bind, List.of(new su.onno.query.KeysetWindow.Order("_is_folder", true),
                        new su.onno.query.KeysetWindow.Order("_description", false),
                        new su.onno.query.KeysetWindow.Order("_id", false)), cursor, limit,
                "tree:" + desc.tableName() + ":" + scopes.policies().fingerprint(subject) + ":" + EntityQuerySupport.cursorSubject(subject));
        EntityQuerySupport.maskParents(jdbi, desc.tableName(), page.rows(), scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), page.rows(), subject);
        return page;
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
            throw new ServiceException(ServiceException.Status.BAD_REQUEST,
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

    /** One record; a record outside the subject's scope is a 404, exactly like a missing one. */
    public Map<String, Object> get(AccessSubject subject, CatalogDescriptor desc, UUID id) {
        ScopeClause scope = scope(subject, surface(desc));
        Map<String, Object> row = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id" + scope.and()))
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ServiceException(ServiceException.Status.NOT_FOUND))
        );
        if (desc.hierarchical()) EntityQuerySupport.maskParents(jdbi, desc.tableName(), List.of(row), scope);
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), List.of(row), subject);
        for (var section : desc.tabularSections()) {
            List<Map<String, Object>> lines = jdbi.withHandle(h -> h.createQuery(
                            "SELECT * FROM " + section.tableName()
                                    + " WHERE _parent_id = :id ORDER BY _line_number")
                    .bind("id", id).mapToMap().list());
            EntityQuerySupport.numberLines(lines);
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
