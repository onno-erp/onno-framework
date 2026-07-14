package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalogs")
public class GenericCatalogController {

    private final CatalogQueryService query;
    private final UiAccessService access;
    private final CatalogCommandService commands;
    private final RelatedListReader relatedLists;
    private final UiMessages messages;
    private final BatchRunner batch;

    public GenericCatalogController(CatalogQueryService query,
                                    UiAccessService access,
                                    CatalogCommandService commands,
                                    RelatedListReader relatedLists,
                                    UiMessages messages,
                                    BatchRunner batch) {
        this.query = query;
        this.access = access;
        this.commands = commands;
        this.relatedLists = relatedLists;
        this.messages = messages;
        this.batch = batch;
    }

    @GetMapping("/{name}")
    public List<Map<String, Object>> list(@PathVariable String name,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String filter,
                                          Principal principal) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        // A search query or an explicit limit switches to the capped server-side
        // typeahead (for ref pickers); without either, the full list is returned for
        // back-compat with callers that page client-side. `filter` is an authored
        // WidgetFilter predicate so chart/list widgets scope their rows server-side.
        if (q != null || limit != null) {
            int cap = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
            return query.search(desc, q, cap);
        }
        return query.list(desc, filter);
    }

    @GetMapping("/{name}/children")
    public List<Map<String, Object>> children(@PathVariable String name,
                                              @RequestParam(required = false) UUID parent,
                                              Principal principal) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.children(desc, parent);
    }

    @GetMapping("/{name}/tree")
    public List<Map<String, Object>> tree(@PathVariable String name, Principal principal) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.tree(desc);
    }

    @GetMapping("/{name}/{id}")
    public Map<String, Object> get(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.get(desc, id);
    }

    /**
     * Live rows of a related-list panel declared on the {@code name} catalog's editor (see
     * {@link RelatedList}) — the junction rows whose back-reference points at record {@code id}.
     * For a join-catalog junction, add/remove go through the plain create/delete endpoints on the
     * join catalog itself (so its own write roles apply), which the form drives from this panel's
     * metadata; an information-register junction is read-only. The shared {@link RelatedListReader}
     * resolves the junction and enforces read access on it.
     */
    @GetMapping("/{name}/{id}/related/{relatedName}")
    public List<Map<String, Object>> related(@PathVariable String name, @PathVariable UUID id,
                                             @PathVariable String relatedName, Principal principal) {
        CatalogDescriptor parent = query.require(name);
        access.requireRead(principal, parent);
        return relatedLists.rows(parent.javaClass(), parent.logicalName(), relatedName, id, principal);
    }

    @PostMapping("/{name}")
    public Map<String, Object> create(@PathVariable String name, @RequestBody Map<String, Object> body,
                                      Principal principal) {
        return commands.create(query.require(name), body, principal);
    }

    /**
     * Create a copy of record {@code id}: same description/attributes/parent, fresh identity (new
     * id, next code). Secret attributes are not copied — reads are redacted, so a copy would store
     * the sentinel; the clone starts with them unset. Runs through the normal create path
     * (lifecycle hooks, validation, write access). Powers the list's clipboard paste (⌘C/⌘V).
     */
    @PostMapping("/{name}/{id}/duplicate")
    public Map<String, Object> duplicate(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(principal, desc); // create() enforces write below
        Map<String, Object> row = query.get(desc, id);
        Map<String, Object> body = new LinkedHashMap<>();
        if (row.get("_description") != null) {
            // Same-named copies are indistinguishable in pickers and lists — suffix the clone
            // (localizable via onno.ui.messages "duplicate.copySuffix"; blank disables).
            body.put("description", row.get("_description") + messages.get("duplicate.copySuffix"));
        }
        if (row.get("_parent") != null) {
            body.put("parent", row.get("_parent"));
        }
        for (AttributeDescriptor attr : desc.attributes()) {
            if (attr.secret()) {
                continue;
            }
            Object v = row.get(attr.columnName());
            if (v != null) {
                body.put(attr.fieldName(), v);
            }
        }
        return commands.create(desc, body, principal);
    }

    @PutMapping("/{name}/{id}")
    public Map<String, Object> update(@PathVariable String name, @PathVariable UUID id,
                                      @RequestBody Map<String, Object> body,
                                      Principal principal) {
        return commands.update(query.require(name), id, body, principal);
    }

    @DeleteMapping("/{name}/{id}")
    public void delete(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        commands.delete(query.require(name), id, principal);
    }

    /**
     * Soft-delete a set of records in one request — the list's batch selection posts here instead
     * of firing N single DELETEs. Per-id failures are recorded and the batch continues; returns
     * {@code {ok, failed, total}}. Ids run concurrently on the shared {@link BatchRunner} pool
     * ({@code onno.ui.batch.parallelism}). Capped like the action batch (see
     * ActionController.BATCH_LIMIT).
     */
    @PostMapping("/{name}/batch-delete")
    public Map<String, Object> batchDelete(@PathVariable String name,
                                           @RequestBody Map<String, Object> body, Principal principal) {
        CatalogDescriptor desc = query.require(name);
        List<UUID> ids = ActionController.idList(body);
        return batch.run(ids, rid -> commands.delete(desc, rid, principal));
    }
}
