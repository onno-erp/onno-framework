package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves pages of list data to the list grid. The default is <b>keyset (seek) pagination</b>: the
 * client passes an opaque {@code cursor} (the {@code nextCursor} from the previous window) plus a
 * {@code limit}, and the server seeks straight to the next window with an indexed comparison — so
 * page 1 and page 10 000 cost the same, and rows are never skipped/duplicated when data shifts mid
 * scroll. The envelope is {@code {rows, nextCursor, hasMore}}; a COUNT runs only when the client
 * opts in via {@code ?count=exact} (or {@code estimate} for a cheap planner figure), since the
 * scroller needs {@code hasMore}, not a live total, to keep loading.
 *
 * <p><b>Legacy offset mode</b> is retained for back-compat: passing {@code ?offset=N} switches to
 * {@code LIMIT/OFFSET} with the old {@code {total, offset, rows}} envelope. New clients omit
 * {@code offset}. Both honour the same server-side sort, case-insensitive search, and column
 * filters; the descriptor (columns, labels, sort, actions) comes from the DivKit list surface.
 */
@RestController
@RequestMapping("/api/list")
public class ListDataController {

    private static final int MAX_PAGE = 500;

    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final UiActionResolver actionResolver;
    private final UiViewResolver viewResolver;

    public ListDataController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                              UiAccessService access, UiActionResolver actionResolver,
                              UiViewResolver viewResolver) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.actionResolver = actionResolver;
        this.viewResolver = viewResolver;
    }

    @GetMapping("/catalogs/{name}")
    public Map<String, Object> catalogPage(@PathVariable String name,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(required = false) String dir,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String filter,
                                           HttpServletRequest request,
                                           Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        // Surgical refresh: when the island asks for specific ids (a single-row live patch), return
        // just those decorated rows and skip paging/filtering entirely. The presence of the param —
        // not the parse result — selects ids-mode, so an all-unparseable request returns no rows
        // (the requested ids matched nothing) rather than falling back to a full page.
        if (request.getParameterValues("ids") != null) {
            List<UUID> ids = parseIds(request);
            List<Map<String, Object>> picked = ids.isEmpty() ? List.of() : catalogQuery.rowsByIds(desc, ids);
            decorateRowActions(desc.javaClass(), picked);
            return page(picked.size(), 0, picked);
        }
        // Read filter params raw: Spring's List<String> binding splits a single value on commas,
        // which would mangle our "column,value" encoding. getParameterValues keeps each verbatim.
        List<String> eq = multi(request, "eq");
        List<String> in = multi(request, "in");
        List<String> like = multi(request, "like");
        List<String> prefix = multi(request, "prefix");
        List<String> ge = multi(request, "ge");
        List<String> le = multi(request, "le");
        // Legacy offset mode: served only when the client explicitly passes ?offset (the current
        // grid still does). Everyone else gets keyset by default — see classdoc.
        if (request.getParameter("offset") != null) {
            List<Map<String, Object>> rows = catalogQuery.page(desc, offset, clamp(limit), sort,
                    descending(dir), q, eq, in, like, prefix, ge, le, filter);
            decorateRowActions(desc.javaClass(), rows);
            return page(catalogQuery.count(desc, q, eq, in, like, prefix, ge, le, filter), offset, rows);
        }
        KeysetPage kp = catalogQuery.keysetPage(desc, request.getParameter("cursor"), limit, sort,
                descending(dir), q, eq, in, like, prefix, ge, le, filter);
        decorateRowActions(desc.javaClass(), kp.rows());
        boolean filtered = isFiltered(q, eq, in, like, prefix, ge, le, filter);
        Long total = total(request.getParameter("count"), filtered,
                () -> catalogQuery.count(desc, q, eq, in, like, prefix, ge, le, filter),
                () -> catalogQuery.estimateCount(desc, filtered));
        return keysetEnvelope(kp, total);
    }

    @GetMapping("/documents/{name}")
    public Map<String, Object> documentPage(@PathVariable String name,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String dir,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String filter,
                                            HttpServletRequest request,
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        // Surgical refresh by id (single-row live patch) — see catalogPage.
        if (request.getParameterValues("ids") != null) {
            List<UUID> ids = parseIds(request);
            List<Map<String, Object>> picked = ids.isEmpty() ? List.of() : documentQuery.rowsByIds(desc, ids);
            decorateRowActions(desc.javaClass(), picked);
            return page(picked.size(), 0, picked);
        }
        // See catalogPage: filter params are read raw to avoid Spring's comma-splitting.
        List<String> eq = multi(request, "eq");
        List<String> in = multi(request, "in");
        List<String> like = multi(request, "like");
        List<String> prefix = multi(request, "prefix");
        List<String> ge = multi(request, "ge");
        List<String> le = multi(request, "le");
        // Legacy offset mode: only when ?offset is explicitly present (see catalogPage).
        if (request.getParameter("offset") != null) {
            List<Map<String, Object>> rows = documentQuery.page(desc, offset, clamp(limit), sort,
                    descending(dir), q, from, to, eq, in, like, prefix, ge, le, filter);
            decorateRowActions(desc.javaClass(), rows);
            return page(documentQuery.count(desc, q, from, to, eq, in, like, prefix, ge, le, filter), offset, rows);
        }
        KeysetPage kp = documentQuery.keysetPage(desc, request.getParameter("cursor"), limit, sort,
                descending(dir), q, from, to, eq, in, like, prefix, ge, le, filter);
        decorateRowActions(desc.javaClass(), kp.rows());
        boolean filtered = from != null || to != null || isFiltered(q, eq, in, like, prefix, ge, le, filter);
        Long total = total(request.getParameter("count"), filtered,
                () -> documentQuery.count(desc, q, from, to, eq, in, like, prefix, ge, le, filter),
                () -> documentQuery.estimateCount(desc, filtered));
        return keysetEnvelope(kp, total);
    }

    /**
     * Group a catalog list by a column: one collapsible header per distinct value (or per date
     * bucket), with a row count + requested subtotals, over the same sort/search/filters as the flat
     * list. The flat list ({@link #catalogPage}) loads a group's rows via the {@code expand} filter
     * each header carries. Envelope: {@code {groups, capped}} — {@code capped} true when the group
     * count hit {@link ListGroups#MAX_GROUPS}.
     */
    @GetMapping("/catalogs/{name}/groups")
    public Map<String, Object> catalogGroups(@PathVariable String name,
                                             @RequestParam String groupBy,
                                             @RequestParam(required = false) String granularity,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String filter,
                                             HttpServletRequest request,
                                             Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        ListGroups.GroupResult result = catalogQuery.groups(desc, groupBy, granularity, q,
                multi(request, "eq"), multi(request, "in"), multi(request, "like"),
                multi(request, "prefix"), multi(request, "ge"), multi(request, "le"),
                filter, aggregates(request));
        return groupsEnvelope(result);
    }

    /** Group a document list by a column — the document analogue of {@link #catalogGroups}. */
    @GetMapping("/documents/{name}/groups")
    public Map<String, Object> documentGroups(@PathVariable String name,
                                              @RequestParam String groupBy,
                                              @RequestParam(required = false) String granularity,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String filter,
                                              HttpServletRequest request,
                                              Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        ListGroups.GroupResult result = documentQuery.groups(desc, groupBy, granularity, q, from, to,
                multi(request, "eq"), multi(request, "in"), multi(request, "like"),
                multi(request, "prefix"), multi(request, "ge"), multi(request, "le"),
                filter, aggregates(request));
        return groupsEnvelope(result);
    }

    /**
     * Pre-aggregated buckets for a chart/stat/sparkline/gauge widget (#199): a server-side
     * {@code GROUP BY groupBy[, seriesBy]} with an optional date-unit bucket and time window,
     * returning O(buckets) rows instead of the entity's whole table. Blank {@code groupBy} yields
     * one grand-total bucket (the gauge case). See {@link WidgetBuckets} for the envelope.
     */
    @GetMapping("/catalogs/{name}/aggregate")
    public Map<String, Object> catalogAggregate(@PathVariable String name,
                                                WidgetBuckets.Request request,
                                                Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        try {
            return catalogQuery.aggregateBuckets(desc, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** The document analogue of {@link #catalogAggregate}. */
    @GetMapping("/documents/{name}/aggregate")
    public Map<String, Object> documentAggregate(@PathVariable String name,
                                                 WidgetBuckets.Request request,
                                                 Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        try {
            return documentQuery.aggregateBuckets(desc, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static Map<String, Object> groupsEnvelope(ListGroups.GroupResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", result.groups());
        out.put("capped", result.capped());
        return out;
    }

    /**
     * The requested per-group subtotals: the repeated {@code agg} param, each a {@code "fn,column"}
     * pair (e.g. {@code agg=sum,total}). Malformed tokens are skipped; the query service re-validates
     * every fn/column, so an unknown one is dropped rather than 400ing the request.
     */
    private static List<ListGroups.Agg> aggregates(HttpServletRequest request) {
        String[] raw = request.getParameterValues("agg");
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<ListGroups.Agg> out = new java.util.ArrayList<>();
        for (String value : raw) {
            if (value == null) continue;
            int comma = value.indexOf(',');
            if (comma <= 0 || comma == value.length() - 1) continue;
            out.add(new ListGroups.Agg(value.substring(0, comma).trim(), value.substring(comma + 1).trim()));
        }
        return out;
    }

    /**
     * Attach per-row state for any state-aware row actions (see {@link ActionSpec}) under each row's
     * {@code _actions} key, so the grid can render a row's button with the right icon/label and
     * honour its visibility/enabled — plus the row's conditional formatting tone (see
     * {@link su.onno.ui.ListSpec#rowStyle}) under {@code _style}. A no-op (rows untouched) when the
     * entity has neither — the common case — so existing lists pay nothing.
     */
    private void decorateRowActions(Class<?> entity, List<Map<String, Object>> rows) {
        if (entity == null) {
            return;
        }
        boolean dynamicActions = actionResolver.hasDynamicRowActions(entity);
        java.util.function.Function<ActionRow, ListSpec.RowStyle> style = viewResolver.rowStyle(entity);
        if (!dynamicActions && style == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (dynamicActions) {
                Map<String, Object> state = actionResolver.rowActionState(entity, row);
                if (!state.isEmpty()) {
                    row.put("_actions", state);
                }
            }
            if (style != null) {
                ListSpec.RowStyle tone = rowTone(style, row);
                if (tone != null) {
                    row.put("_style", tone.name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
    }

    /** One row's tone; a throwing function reads as "no tone" so a bad predicate can't break the list. */
    private static ListSpec.RowStyle rowTone(java.util.function.Function<ActionRow, ListSpec.RowStyle> style,
                                             Map<String, Object> row) {
        try {
            return style.apply(new ActionRow(row));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Raw repeated query-param values (no comma-splitting), or an empty list when absent. */
    private static List<String> multi(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }

    /**
     * The requested row ids for a surgical refresh: the repeated {@code ids} param, also accepting a
     * single comma-separated value. Unparseable tokens are skipped, so a malformed id never 400s the
     * refresh (it just resolves to fewer/zero rows and the client falls back to a full reload).
     */
    private static List<UUID> parseIds(HttpServletRequest request) {
        String[] raw = request.getParameterValues("ids");
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<UUID> out = new java.util.ArrayList<>();
        for (String value : raw) {
            if (value == null) continue;
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    out.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                    // skip a malformed id rather than failing the whole refresh
                }
            }
        }
        return out;
    }

    private static Map<String, Object> page(long total, int offset, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("offset", offset);
        out.put("rows", rows);
        return out;
    }

    /**
     * The keyset envelope: rows + the opaque {@code nextCursor} to fetch the next window and a
     * {@code hasMore} flag. {@code total} is included only when the client opted into a count
     * ({@code ?count=exact|estimate}); the default omits it, which is what makes the fast path fast.
     */
    private static Map<String, Object> keysetEnvelope(KeysetPage kp, Long total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", kp.rows());
        out.put("nextCursor", kp.nextCursor());
        out.put("hasMore", kp.hasMore());
        if (total != null) {
            out.put("total", total);
        }
        return out;
    }

    /**
     * Resolve the optional total for keyset mode: {@code exact} runs a COUNT, {@code estimate} uses
     * cheap planner statistics (PostgreSQL; may be {@code null} → omitted), anything else (the
     * default) skips counting entirely.
     */
    private static Long total(String countMode, boolean filtered,
                              java.util.function.Supplier<Long> exact,
                              java.util.function.Supplier<Long> estimate) {
        if ("exact".equalsIgnoreCase(countMode)) {
            return exact.get();
        }
        if ("estimate".equalsIgnoreCase(countMode)) {
            return estimate.get();
        }
        return null;
    }

    /** Whether any search or column/widget filter is active — gates whether an estimate is meaningful. */
    private static boolean isFiltered(String q, List<String> eq, List<String> in, List<String> like,
                                      List<String> prefix, List<String> ge, List<String> le, String filter) {
        return (q != null && !q.isBlank())
                || !eq.isEmpty() || !in.isEmpty() || !like.isEmpty()
                || !prefix.isEmpty() || !ge.isEmpty() || !le.isEmpty()
                || (filter != null && !filter.isBlank());
    }

    private static boolean descending(String dir) {
        return dir == null || dir.isBlank() || dir.equalsIgnoreCase("desc");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }
}
