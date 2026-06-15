package com.onec.ui;

import com.onec.metadata.DocumentDescriptor;
import com.onec.posting.PostingPreview;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
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

    public GenericDocumentController(DocumentQueryService query,
                                     UiAccessService access,
                                     DocumentCommandService commands,
                                     RelatedListReader relatedLists) {
        this.query = query;
        this.access = access;
        this.commands = commands;
        this.relatedLists = relatedLists;
    }

    @GetMapping("/{name}")
    public List<Map<String, Object>> list(@PathVariable String name,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer limit,
                                          Principal principal) {
        DocumentDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        // A search query or explicit limit switches to the capped typeahead used by the
        // document ref picker; otherwise it's the full (date-ranged) list.
        if (q != null || limit != null) {
            int cap = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
            return query.search(desc, q, cap);
        }
        return query.list(desc, from, to);
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
}
