package com.onec.ui;

import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves pages of list data to the virtualized list grid (the {@code onec-list} React island).
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

    public ListDataController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                              UiAccessService access) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
    }

    @GetMapping("/catalogs/{name}")
    public Map<String, Object> catalogPage(@PathVariable String name,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(required = false) String dir,
                                           @RequestParam(required = false) String q,
                                           Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        int lim = clamp(limit);
        List<Map<String, Object>> rows = catalogQuery.page(desc, offset, lim, sort, descending(dir), q);
        return page(catalogQuery.count(desc, q), offset, rows);
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
                                            Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        int lim = clamp(limit);
        List<Map<String, Object>> rows = documentQuery.page(desc, offset, lim, sort, descending(dir), q, from, to);
        return page(documentQuery.count(desc, q, from, to), offset, rows);
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
