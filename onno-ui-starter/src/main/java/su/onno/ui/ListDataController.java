package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves pages of list data to the virtualized list grid (the {@code onno-list} React island).
 * Returns the live total (for the scroll height) plus one window of rows — server-side sorted by
 * a validated column and filtered by a case-insensitive search — so a 10k-row entity never ships
 * whole to the client. The descriptor (columns, labels, sort, actions) comes from the DivKit list
 * surface; this endpoint is just the data feed.
 */
@RestController
@RequestMapping("/api/list")
public class ListDataController {

    private static final int MAX_PAGE = 500;

    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final UiActionResolver actionResolver;

    public ListDataController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                              UiAccessService access, UiActionResolver actionResolver) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.actionResolver = actionResolver;
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
        int lim = clamp(limit);
        // Read filter params raw: Spring's List<String> binding splits a single value on commas,
        // which would mangle our "column,value" encoding. getParameterValues keeps each verbatim.
        List<String> eq = multi(request, "eq");
        List<String> in = multi(request, "in");
        List<String> like = multi(request, "like");
        List<String> prefix = multi(request, "prefix");
        List<String> ge = multi(request, "ge");
        List<String> le = multi(request, "le");
        List<Map<String, Object>> rows = catalogQuery.page(desc, offset, lim, sort, descending(dir), q, eq, in, like, prefix, ge, le, filter);
        decorateRowActions(desc.javaClass(), rows);
        return page(catalogQuery.count(desc, q, eq, in, like, prefix, ge, le, filter), offset, rows);
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
        int lim = clamp(limit);
        // See catalogPage: filter params are read raw to avoid Spring's comma-splitting.
        List<String> eq = multi(request, "eq");
        List<String> in = multi(request, "in");
        List<String> like = multi(request, "like");
        List<String> prefix = multi(request, "prefix");
        List<String> ge = multi(request, "ge");
        List<String> le = multi(request, "le");
        List<Map<String, Object>> rows = documentQuery.page(desc, offset, lim, sort, descending(dir), q, from, to, eq, in, like, prefix, ge, le, filter);
        decorateRowActions(desc.javaClass(), rows);
        return page(documentQuery.count(desc, q, from, to, eq, in, like, prefix, ge, le, filter), offset, rows);
    }

    /**
     * Attach per-row state for any state-aware row actions (see {@link ActionSpec}) under each row's
     * {@code _actions} key, so the grid can render a row's button with the right icon/label and
     * honour its visibility/enabled. A no-op (rows untouched) when the entity has only static row
     * actions — the common case — so existing lists pay nothing.
     */
    private void decorateRowActions(Class<?> entity, List<Map<String, Object>> rows) {
        if (entity == null || !actionResolver.hasDynamicRowActions(entity)) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> state = actionResolver.rowActionState(entity, row);
            if (!state.isEmpty()) {
                row.put("_actions", state);
            }
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

    private static boolean descending(String dir) {
        return dir == null || dir.isBlank() || dir.equalsIgnoreCase("desc");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }
}
