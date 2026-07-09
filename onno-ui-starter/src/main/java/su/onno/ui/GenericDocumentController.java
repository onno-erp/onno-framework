package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.posting.PostingPreview;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class GenericDocumentController {

    private final UiAccessService access;
    private final DocumentQueryService query;
    private final DocumentCommandService commands;
    private final RelatedListReader relatedLists;
    private final BatchRunner batch;

    public GenericDocumentController(DocumentQueryService query,
                                     UiAccessService access,
                                     DocumentCommandService commands,
                                     RelatedListReader relatedLists,
                                     BatchRunner batch) {
        this.query = query;
        this.access = access;
        this.commands = commands;
        this.relatedLists = relatedLists;
        this.batch = batch;
    }

    @GetMapping("/{name}")
    public List<Map<String, Object>> list(@PathVariable String name,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String filter,
                                          Principal principal) {
        DocumentDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        // A search query or explicit limit switches to the capped typeahead used by the
        // document ref picker; otherwise it's the full (date-ranged) list. `filter` is an
        // authored WidgetFilter predicate so chart/list widgets scope their rows server-side.
        if (q != null || limit != null) {
            int cap = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
            return query.search(desc, q, cap);
        }
        return query.list(desc, from, to, filter);
    }

    @GetMapping("/{name}/{id}")
    public Map<String, Object> get(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        DocumentDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        return query.get(desc, id);
    }

    /**
     * Live rows of a related-list panel declared on the {@code name} document's view (see
     * {@link RelatedList}) — the junction rows whose back-reference points at record {@code id}.
     * The document-side parity with {@code GenericCatalogController#related} (1C surfaces
     * subordinate/related records on documents too); the shared {@link RelatedListReader} resolves
     * the junction (join catalog or information register) and enforces read access on it.
     */
    @GetMapping("/{name}/{id}/related/{relatedName}")
    public List<Map<String, Object>> related(@PathVariable String name, @PathVariable UUID id,
                                             @PathVariable String relatedName, Principal principal) {
        DocumentDescriptor parent = query.require(name);
        access.requireRead(principal, parent);
        return relatedLists.rows(parent.javaClass(), parent.logicalName(), relatedName, id, principal);
    }

    @PostMapping("/{name}")
    public Map<String, Object> create(@PathVariable String name, @RequestBody Map<String, Object> body,
                                      Principal principal) {
        return commands.create(query.require(name), body, principal);
    }

    /**
     * Create a copy of document {@code id}: same attributes and line items, fresh identity (new
     * id, next number), dated now, and — like any new document — unposted. Secret attributes are
     * not copied (reads are redacted, so a copy would store the sentinel). Runs through the normal
     * create path (lifecycle hooks, validation, write access). Powers the list's clipboard paste
     * (⌘C/⌘V).
     */
    @PostMapping("/{name}/{id}/duplicate")
    public Map<String, Object> duplicate(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        DocumentDescriptor desc = query.require(name);
        access.requireRead(principal, desc); // create() enforces write below
        Map<String, Object> row = query.get(desc, id);
        Map<String, Object> body = new LinkedHashMap<>();
        for (AttributeDescriptor attr : desc.attributes()) {
            if (attr.secret()) {
                continue;
            }
            Object v = row.get(attr.columnName());
            if (v != null) {
                body.put(attr.fieldName(), v);
            }
        }
        // Line items ride under the section name as fieldName-keyed rows — the create() contract.
        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            if (!(row.get(ts.name()) instanceof List<?> rows)) {
                continue;
            }
            List<Map<String, Object>> copies = new ArrayList<>();
            for (Object o : rows) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>();
                for (AttributeDescriptor attr : ts.attributes()) {
                    if (attr.secret()) {
                        continue;
                    }
                    Object v = m.get(attr.columnName());
                    if (v != null) {
                        copy.put(attr.fieldName(), v);
                    }
                }
                copies.add(copy);
            }
            body.put(ts.name(), copies);
        }
        return commands.create(desc, body, principal);
    }

    @PutMapping("/{name}/{id}")
    public Map<String, Object> update(@PathVariable String name, @PathVariable UUID id,
                                      @RequestBody Map<String, Object> body,
                                      Principal principal) {
        return commands.update(query.require(name), id, body, principal);
    }

    @PostMapping("/{name}/{id}/post")
    public Map<String, Object> post(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        return commands.post(query.require(name), id, principal);
    }

    @GetMapping("/{name}/{id}/posting-preview")
    public PostingPreview postingPreview(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        return commands.postingPreview(query.require(name), id, principal);
    }

    @PostMapping("/{name}/{id}/unpost")
    public Map<String, Object> unpost(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        return commands.unpost(query.require(name), id, principal);
    }

    @DeleteMapping("/{name}/{id}")
    public void delete(@PathVariable String name, @PathVariable UUID id, Principal principal) {
        commands.delete(query.require(name), id, principal);
    }

    /**
     * Soft-delete a set of documents in one request (auto-unposting posted ones, like the single
     * DELETE). Per-id failures are recorded and the batch continues; returns {@code {ok, failed,
     * total}}. Ids run concurrently on the shared {@link BatchRunner} pool
     * ({@code onno.ui.batch.parallelism}) — note that parallel unposting reverses register entries
     * concurrently, so heavily-shared accumulation registers may see lock contention. Capped like the
     * action batch (see ActionController.BATCH_LIMIT).
     */
    @PostMapping("/{name}/batch-delete")
    public Map<String, Object> batchDelete(@PathVariable String name,
                                           @RequestBody Map<String, Object> body, Principal principal) {
        DocumentDescriptor desc = query.require(name);
        List<UUID> ids = ActionController.idList(body);
        return batch.run(ids, rid -> commands.delete(desc, rid, principal));
    }
}
