package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;

import org.jdbi.v3.core.Jdbi;
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
 * tabular sections), shared by the REST API and the DivKit emitters.
 *
 * <p>Every read takes the {@link AccessSubject} it is made for. Entity-level RBAC stays with the
 * callers; the subject's <strong>record scope</strong> is applied here, inside the {@code WHERE}.
 * Tabular sections inherit the document's scope (they are only reachable through it). Trusted code
 * passes {@link AccessSubject#system()}.
 */
public class DocumentQueryService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RefResolver refResolver;
    private final RecordScopeCompiler scopes;
    private final UiAccessService access;

    /** A service without record policies (every subject unscoped) — for tests and tools. */
    public DocumentQueryService(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new UiAccessService(registry));
    }

    public DocumentQueryService(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes,
                                UiAccessService access) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.access = access;
        this.refResolver = new RefResolver(registry, jdbi, scopes, access);
    }

    /** The ref resolver bound to this service's policies. */
    public RefResolver refResolver() {
        return refResolver;
    }

    /** The record-scope compiler this service applies. */
    public RecordScopeCompiler scopes() {
        return scopes;
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

    /**
     * Capped, case-insensitive typeahead for the document ref picker. Matches across the same text
     * columns the paged list search covers — number plus every (non-secret) String attribute — so a
     * document is findable by a secondary attribute, not just its number (issue #184). Live records
     * only, newest first.
     */
    public List<Map<String, Object>> search(AccessSubject subject, DocumentDescriptor desc, String query, int limit) {
        return search(subject, desc, query, limit, null);
    }

    /**
     * As {@link #search(AccessSubject, DocumentDescriptor, String, int)}, additionally narrowed by a
     * {@link WidgetFilter} predicate — the cascading ref picker sends its resolved
     * {@code refFilter} here, so only compatible documents are offered. Ref/enum columns bind as
     * typed uuids (PG-strict); a null/blank/invalid predicate is simply no filter.
     */
    public List<Map<String, Object>> search(AccessSubject subject, DocumentDescriptor desc, String query,
                                            int limit, String filter) {
        return search(subject, desc, query, limit, filter, null);
    }

    /**
     * As {@link #search(AccessSubject, DocumentDescriptor, String, int, String)}, additionally restricted to
     * the given ids when {@code ids} is non-null: the ref picker's "recent" section re-reads its
     * remembered picks through here, so they pass the same deletion mark, record scope and
     * {@code refFilter} as ordinary options. An empty id list yields no rows.
     */
    public List<Map<String, Object>> search(AccessSubject subject, DocumentDescriptor desc, String query,
                                            int limit, String filter, java.util.Collection<UUID> ids) {
        if (ids != null && ids.isEmpty()) {
            return new ArrayList<>();
        }
        EntitySurfaceDescriptor surface = surface(desc);
        WidgetFilter.Result wf = WidgetFilter.parse(filter, surface.columnNames(), surface.uuidColumns(), surface::storageColumn);
        ScopeClause scope = scope(subject, surface);
        ScopeClause search = searchClause(subject, surface, query);
        String where = "_deletion_mark = false"
                + (wf.isEmpty() ? "" : " AND (" + wf.sql() + ")")
                + (ids == null ? "" : " AND _id IN (<ids>)")
                + search.and() + scope.and();
        List<Map<String, Object>> rows = jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT * FROM " + desc.tableName() +
                            " WHERE " + where +
                            " ORDER BY _date DESC LIMIT :limit")
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
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), rows, subject);
        return rows;
    }

    /**
     * The seed row for a <em>new</em> document form: a fresh instance's field-initializer defaults
     * in the same column-keyed, ref-resolved shape {@link #get} returns for an existing record, so
     * the New form pre-fills declared defaults instead of opening blank (issue #181).
     */
    public Map<String, Object> newDraft(AccessSubject subject, DocumentDescriptor desc) {
        return newDraft(subject, desc, Map.of());
    }

    /**
     * As {@link #newDraft(AccessSubject, DocumentDescriptor)}, but overlays caller-supplied initial values (from the
     * New-form navigation query, keyed by attribute field name) onto the seed row before ref/enum
     * resolution — so a deep link like {@code …/new?startsAt=…&room=<id>} pre-fills those fields.
     */
    public Map<String, Object> newDraft(AccessSubject subject, DocumentDescriptor desc, Map<String, String> prefill) {
        Map<String, Object> row = NewEntityDefaults.columnValues(desc.javaClass(), desc.attributes(), registry);
        NewEntityDefaults.applyPrefill(row, desc.attributes(), EntityQuerySupport.withDefaults(prefill,
                EntityQuerySupport.policyDefaults(scopes, desc.javaClass(), subject)));
        refResolver.resolveAttributes(List.of(row), desc.attributes(), subject);
        return row;
    }

    public long count(AccessSubject subject, DocumentDescriptor desc) {
        ScopeClause scope = scope(subject, surface(desc));
        return jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT COUNT(*) FROM " + desc.tableName()
                                + " WHERE _deletion_mark = false" + scope.and()))
                        .mapTo(Long.class)
                        .one());
    }

    /**
     * One keyset-paginated window of a document list — the constant-time default for the list grid.
     * Seeks past {@code cursorToken} (null/blank for the first window) instead of counting past an
     * offset, so deep paging stays O(window). Honors sort/search/date-range/filters, fetches one
     * extra row for {@code hasMore} (no COUNT), and mints the next cursor
     * from the last row. The default newest-first order seeks on {@code (_date, _id)}.
     */
    public KeysetPage keysetPage(AccessSubject subject, DocumentDescriptor desc, String cursorToken, int limit,
                                 String sortColumn, boolean descending, String search,
                                 String from, String to,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String widgetFilter) {
        return keysetPage(subject, desc, cursorToken, null, null, limit, sortColumn, descending, search,
                from, to, eq, in, like, prefix, ge, le, widgetFilter);
    }

    /**
     * As {@link #keysetPage(AccessSubject, DocumentDescriptor, String, int, String, boolean, String,
     * String, String, List, List, List, List, List, List, String)}, at a chosen position:
     * {@code beforeToken} (a window's {@code prevCursor}) returns the rows just above that window,
     * and {@code focus} returns the window containing that document — up to half the limit of rows
     * before it, the document, and the rows after it. A focused document the subject's query cannot
     * see (outside the date range/filters, out of scope, deleted, unknown) yields the ordinary first
     * window with no focus. {@code cursorToken} wins over {@code beforeToken}, which wins over
     * {@code focus}.
     */
    public KeysetPage keysetPage(AccessSubject subject, DocumentDescriptor desc, String cursorToken,
                                 String beforeToken, UUID focus, int limit,
                                 String sortColumn, boolean descending, String search,
                                 String from, String to,
                                 List<String> eq, List<String> in, List<String> like,
                                 List<String> prefix, List<String> ge, List<String> le,
                                 String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        String fingerprint = scopes.policies().fingerprint(subject);
        boolean defaultSort = surface.isDefaultSort(sortColumn);
        String col = surface.safeSort(sortColumn);
        boolean dirDesc = defaultSort ? surface.defaultDescending() : descending;

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames(), surface.uuidColumns(), surface::storageColumn);
        ScopeClause scope = scope(subject, surface);
        ScopeClause searchClause = searchClause(subject, surface, search);
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause.and());
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
        where.append(scope.and());

        KeysetWindow.Raw window = KeysetWindow.fetch(jdbi, desc.tableName(), where.toString(), q -> {
                    EntityQuerySupport.bindSearch(q, search);
                    searchClause.bind(q);
                    if (from != null) q.bind("from", from);
                    if (to != null) q.bind("to", to);
                    filter.bindings().forEach(q::bind);
                    wf.bindings().forEach(q::bind);
                    scope.bind(q);
                }, col, dirDesc, !surface.isNonNullableSort(col),
                new KeysetWindow.Position(cursorToken, beforeToken, focus), limit, fingerprint);

        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), window.rows(), subject);
        return window.decorated();
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
    public Long estimateCount(AccessSubject subject, DocumentDescriptor desc, boolean filtered) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.estimateCount(jdbi, surface, filtered, scope(subject, surface));
    }

    /**
     * Fetch specific live documents by id, decorated like keyset rows (refs resolved,
     * secrets redacted) so the list island can refresh just the rows that changed instead of
     * re-paging the whole window. Returns only the rows that still exist and aren't deletion-marked.
     */
    public List<Map<String, Object>> rowsByIds(AccessSubject subject, DocumentDescriptor desc, List<UUID> ids) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.rowsByIds(jdbi, refResolver, surface, ids, scope(subject, surface), subject);
    }

    /** Total live rows matching the search (+ optional date range, declarative filters, widget filter). */
    public long count(AccessSubject subject, DocumentDescriptor desc, String search, String from, String to,
                      List<String> eq, List<String> in, List<String> like,
                      List<String> prefix, List<String> ge, List<String> le,
                      String widgetFilter) {
        EntitySurfaceDescriptor surface = surface(desc);
        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, surface.columnNames(), surface.uuidColumns(), surface::storageColumn);
        ScopeClause scope = scope(subject, surface);
        ScopeClause searchClause = searchClause(subject, surface, search);
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause.and());
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
        where.append(scope.and());
        return jdbi.withHandle(h -> {
            var q = h.createQuery("SELECT COUNT(*) FROM " + desc.tableName() + " WHERE " + where);
            EntityQuerySupport.bindSearch(q, search);
            searchClause.bind(q);
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            scope.bind(q);
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
    public ListGroups.GroupResult groups(AccessSubject subject, DocumentDescriptor desc, String groupColumn,
                                         String granularity,
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

        ListFilter.Result filter = ListFilter.parse(eq, in, like, prefix, ge, le,
                surface.filterableColumns(), surface.numericColumns());
        WidgetFilter.Result wf = WidgetFilter.parse(widgetFilter, columns, surface.uuidColumns(), surface::storageColumn);
        ScopeClause scope = scope(subject, surface);
        ScopeClause searchClause = searchClause(subject, surface, search);
        StringBuilder where = new StringBuilder("_deletion_mark = false").append(searchClause.and());
        if (from != null) where.append(" AND _date >= CAST(:from AS TIMESTAMP)");
        if (to != null) where.append(" AND _date <= CAST(:to AS TIMESTAMP)");
        if (!filter.isEmpty()) where.append(" AND (").append(filter.sql()).append(")");
        if (!wf.isEmpty()) where.append(" AND (").append(wf.sql()).append(")");
        where.append(scope.and());

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
            if (from != null) q.bind("from", from);
            if (to != null) q.bind("to", to);
            filter.bindings().forEach(q::bind);
            wf.bindings().forEach(q::bind);
            scope.bind(q);
            return q.mapToMap().list();
        });
        boolean capped = rows.size() > ListGroups.MAX_GROUPS;
        if (capped) {
            rows = new ArrayList<>(rows.subList(0, ListGroups.MAX_GROUPS));
        }
        // The raw value stays the group's expand key; a restricted target only has its label masked.
        refResolver.resolveAttributes(rows, desc.attributes(), subject, false);
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
    private ScopeClause searchClause(AccessSubject subject, EntitySurfaceDescriptor surface, String search) {
        return EntityQuerySupport.searchClause(registry, surface, search,
                EntityQuerySupport.targetGate(scopes, access, subject));
    }

    private ScopeClause scope(AccessSubject subject, EntitySurfaceDescriptor surface) {
        return EntityQuerySupport.readScope(scopes, surface, subject);
    }

    /**
     * A single aggregate value for a count/metric card — {@code count} of rows, or
     * {@code sum|avg|min|max} of one numeric column — restricted to live records and
     * narrowed by an optional safe {@code filter} predicate (see {@link WidgetFilter}).
     */
    public BigDecimal aggregate(AccessSubject subject, DocumentDescriptor desc, String metric, String field,
                                String filter) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.aggregate(jdbi, surface, metric, field, filter, scope(subject, surface));
    }

    /**
     * Grouped aggregate buckets for a chart/stat widget — a server-side {@code GROUP BY} returning
     * O(buckets) rows instead of the whole table (#199). See {@link WidgetBuckets}.
     */
    public Map<String, Object> aggregateBuckets(AccessSubject subject, DocumentDescriptor desc,
                                                WidgetBuckets.Request request) {
        EntitySurfaceDescriptor surface = surface(desc);
        return EntityQuerySupport.aggregateBuckets(jdbi, refResolver, surface, request,
                scope(subject, surface), subject);
    }

    private static EntitySurfaceDescriptor surface(DocumentDescriptor desc) {
        return EntitySurfaceDescriptor.document(desc);
    }

    /**
     * One document with its tabular sections; a document outside the subject's scope is a 404,
     * exactly like a missing one (its lines are only reachable through it).
     */
    public Map<String, Object> get(AccessSubject subject, DocumentDescriptor desc, UUID id) {
        ScopeClause scope = scope(subject, surface(desc));
        Map<String, Object> doc = jdbi.withHandle(h ->
                scope.bind(h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id" + scope.and()))
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );
        EntityQuerySupport.decorateRows(refResolver, desc.attributes(), List.of(doc), subject);

        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            List<Map<String, Object>> rows = jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM " + ts.tableName() +
                                    " WHERE _parent_id = :parentId ORDER BY _line_number")
                            .bind("parentId", id)
                            .mapToMap()
                            .list()
            );
            EntityQuerySupport.decorateRows(refResolver, ts.attributes(), rows, subject);
            doc.put(ts.name(), rows);
        }
        return doc;
    }

    /** Whether document {@code id} exists within the subject's scope for {@code mode} (deleted rows included). */
    public boolean inScope(AccessSubject subject, DocumentDescriptor desc, UUID id, su.onno.access.AccessMode mode) {
        ScopeClause scope = scopes.clause(desc.javaClass(), subject, mode, desc.tableName());
        return jdbi.withHandle(h -> scope.bind(h.createQuery(
                        "SELECT COUNT(*) FROM " + desc.tableName() + " WHERE _id = :id" + scope.and()))
                .bind("id", id)
                .mapTo(Long.class).one() > 0);
    }
}
