package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.BatchRunner;
import su.onno.service.CatalogCommandService;
import su.onno.service.CatalogQueryService;
import su.onno.service.CommandService;
import su.onno.service.EntityJsonRepresentation;
import su.onno.service.KeysetWindow;
import su.onno.access.AccessSubject;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.TabularSectionDescriptor;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalogs")
public class GenericCatalogController {

    private final CatalogQueryService query;
    private final AccessService access;
    private final CatalogCommandService commands;
    private final UiMessages messages;
    private final BatchRunner batch;
    private su.onno.service.RelationWriteService relations;

    public GenericCatalogController(CatalogQueryService query,
                                    AccessService access,
                                    CatalogCommandService commands,
                                    UiMessages messages,
                                    BatchRunner batch) {
        this.query = query;
        this.access = access;
        this.commands = commands;
        this.messages = messages;
        this.batch = batch;
    }

    /**
     * Saves join-entity relations sent with a create/update ({@code @relations}) in the record's
     * transaction. Optional so the controller still constructs without it (tests, slim wiring).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRelations(su.onno.service.RelationWriteService relations) {
        this.relations = relations;
    }

    private Map<String, Object> withRelations(CatalogDescriptor desc, Map<String, Object> body, AccessSubject subject,
                                              java.util.function.Supplier<Map<String, Object>> write) {
        var related = su.onno.service.RelationWriteService.extract(body);
        if (related.isEmpty()) return write.get();
        if (relations == null) {
            throw new su.onno.service.ServiceException(su.onno.service.ServiceException.Status.BAD_REQUEST,
                    "Relation writes are not available");
        }
        return relations.write(desc.javaClass(), related, subject, write);
    }

    /**
     * Direct children of {@code parent} (roots without it). {@code folders=true} returns just the
     * folders, each with {@code hasChildFolders} (#411).
     */
    @GetMapping("/{name}/children")
    public Map<String, Object> children(@PathVariable String name,
            @RequestParam(required = false) UUID parent, @RequestParam(defaultValue = "false") boolean folders,
            @RequestParam(defaultValue = "logical") String representation,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        var page = query.childrenPage(subject, desc, parent, folders, cursor, limit);
        return KeysetWindow.envelope(new su.onno.query.KeysetWindow.Page<>(
                EntityJsonRepresentation.catalogs(desc, page.rows(), EntityJsonRepresentation.parse(representation)),
                page.nextCursor(), page.hasMore()));
    }

    /**
     * The full nested tree; with {@code ids} only the paths from the roots to those records (#411).
     * {@code ids} are comma-separated or repeated; malformed ones are ignored.
     */
    @GetMapping("/{name}/tree")
    public Object tree(@PathVariable String name, @RequestParam(required = false) List<String> ids,
            @RequestParam(defaultValue = "logical") String representation,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        var mode = EntityJsonRepresentation.parse(representation);
        if (ids != null) return EntityJsonRepresentation.catalogs(desc, query.paths(subject, desc, parseIds(ids)), mode);
        var page = query.treePage(subject, desc, cursor, limit);
        return KeysetWindow.envelope(new su.onno.query.KeysetWindow.Page<>(
                EntityJsonRepresentation.catalogs(desc, page.rows(), mode), page.nextCursor(), page.hasMore()));
    }

    private static List<UUID> parseIds(List<String> values) {
        List<UUID> ids = new java.util.ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                try {
                    if (!part.isBlank()) {
                        ids.add(UUID.fromString(part.trim()));
                    }
                } catch (IllegalArgumentException malformed) {
                    // an unparseable id names no record: skip it
                }
            }
        }
        return ids;
    }

    @GetMapping("/{name}/{id}")
    public Map<String, Object> get(@PathVariable String name, @PathVariable UUID id,
                                   @RequestParam(defaultValue = "logical") String representation,
                                   AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return EntityJsonRepresentation.catalog(desc, query.get(subject, desc, id),
                EntityJsonRepresentation.parse(representation));
    }


    @PostMapping("/{name}")
    public Map<String, Object> create(@PathVariable String name, @RequestBody Map<String, Object> body,
                                      @RequestParam(defaultValue = "logical") String representation,
                                      AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        EntityJsonRepresentation.Mode mode = EntityJsonRepresentation.parse(representation);
        return EntityJsonRepresentation.catalog(desc,
                withRelations(desc, body, subject, () -> commands.create(desc, body, subject)), mode);
    }

    /**
     * Create a copy of record {@code id}: same description/attributes/parent and line items, fresh identity (new
     * id, next code). Secret attributes are not copied — reads are redacted, so a copy would store
     * the sentinel; the clone starts with them unset. Runs through the normal create path
     * (lifecycle hooks, validation, write access). Powers the list's clipboard paste (⌘C/⌘V).
     */
    @PostMapping("/{name}/{id}/duplicate")
    public Map<String, Object> duplicate(@PathVariable String name, @PathVariable UUID id,
                                         @RequestParam(defaultValue = "logical") String representation,
                                         AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        access.requireRead(subject, desc); // create() enforces write below
        EntityJsonRepresentation.Mode mode = EntityJsonRepresentation.parse(representation);
        Map<String, Object> row = query.get(subject, desc, id);
        Map<String, Object> body = new LinkedHashMap<>();
        if (row.get("_description") != null) {
            // Same-named copies are indistinguishable in pickers and lists — suffix the clone
            // (localizable via onno.ui.messages "duplicate.copySuffix"; blank disables).
            body.put("description", row.get("_description") + messages.get("duplicate.copySuffix"));
        }
        if (desc.hierarchical() && row.get("_parent") != null) {
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
        // Match document duplication: copy declared row attributes, never row identities or secrets.
        for (TabularSectionDescriptor section : desc.tabularSections()) {
            if (!(row.get(section.name()) instanceof List<?> rows)) continue;
            List<Map<String, Object>> copies = new ArrayList<>();
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> source)) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                for (AttributeDescriptor attr : section.attributes()) {
                    if (!attr.secret() && source.get(attr.columnName()) != null) {
                        copy.put(attr.fieldName(), source.get(attr.columnName()));
                    }
                }
                copies.add(copy);
            }
            body.put(section.name(), copies);
        }
        return EntityJsonRepresentation.catalog(desc, commands.create(desc, body, subject),
                mode);
    }

    @PutMapping("/{name}/{id}")
    public Map<String, Object> update(@PathVariable String name, @PathVariable UUID id,
                                      @RequestBody Map<String, Object> body,
                                      @RequestParam(defaultValue = "logical") String representation,
                                      AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        EntityJsonRepresentation.Mode mode = EntityJsonRepresentation.parse(representation);
        return EntityJsonRepresentation.catalog(desc,
                withRelations(desc, body, subject, () -> commands.update(desc, id, body, subject)), mode);
    }

    /**
     * Dry-run validation for the form's live (as-you-type) feedback: runs the full write lifecycle —
     * declarative constraints, hooks, {@code Validated} business rules — against the submitted
     * values without persisting, and reports {@code {valid, fieldErrors, formErrors}} with 200
     * either way. The id-less form validates a create; with {@code id}, the changes are overlaid
     * onto the stored record first, exactly like {@code PUT}.
     */
    @PostMapping({"/{name}/validate", "/{name}/{id}/validate"})
    public Map<String, Object> validate(@PathVariable String name,
                                        @PathVariable(required = false) UUID id,
                                        @RequestBody Map<String, Object> body,
                                        AccessSubject subject) {
        return commands.validate(query.require(name), id, body, subject);
    }

    @DeleteMapping("/{name}/{id}")
    public void delete(@PathVariable String name, @PathVariable UUID id, AccessSubject subject) {
        commands.delete(query.require(name), id, subject);
    }

    /**
     * Soft-delete a set of records in one request — the list's batch selection posts here instead
     * of firing N single DELETEs. Per-id failures are recorded and the batch continues; returns
     * {@code {ok, failed, total}}. Ids run concurrently on the shared {@link BatchRunner} pool
     * ({@code onno.web.batch.parallelism}). Capped like the action batch (see
     * CommandService.BATCH_LIMIT).
     */
    @PostMapping("/{name}/batch-delete")
    public Map<String, Object> batchDelete(@PathVariable String name,
                                           @RequestBody Map<String, Object> body, AccessSubject subject) {
        CatalogDescriptor desc = query.require(name);
        List<UUID> ids = CommandService.idList(body);
        return batch.run(ids, rid -> commands.delete(desc, rid, subject));
    }
}
