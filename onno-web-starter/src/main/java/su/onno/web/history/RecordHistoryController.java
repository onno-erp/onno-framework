package su.onno.web.history;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.history.HistoryChange;
import su.onno.history.HistoryEntry;
import su.onno.history.RecordHistory;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.query.Cursor;
import su.onno.query.KeysetWindow;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.AccessService;
import su.onno.web.UiMessages;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The record change-history read endpoint: {@code GET /api/history/{kind}/{name}/{id}} returns the
 * recorded events of one catalog item or document, newest first, as one keyset window
 * ({@code {rows, nextCursor, hasMore}}; {@code ?cursor=}, {@code ?limit=} default 100, max 500), addressed by the same
 * {@code {kind}/{name}/{id}} triple the UI routes use.
 *
 * <p>Reading history needs exactly what reading the record needs: the entity's read grant
 * ({@code RoleGrants}, via {@link AccessService}) and, for a record-scoped caller, the record
 * inside its {@code RecordAccessPolicy} read scope (404 otherwise, like a missing record). An entity
 * that has not opted in with {@code history = true} has no history surface (404). Values are
 * presented the way the record itself is: references and enumerations carry a resolved display, a
 * reference the caller may not read is masked (no id, display {@code —}), and {@code secret}
 * attributes are reported as changed without values — they are never stored in the first place.
 */
@RestController
@RequestMapping("/api/history")
public class RecordHistoryController {

    private final RecordHistory history;
    private final AccessService access;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiMessages messages;

    public RecordHistoryController(RecordHistory history, AccessService access,
                                   CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                                   UiMessages messages) {
        this.history = history;
        this.access = access;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.messages = messages;
    }

    /** What the presenter needs to know about the entity the history belongs to. */
    private record Target(Class<?> javaClass, String logicalName, boolean catalog,
                          List<AttributeDescriptor> attributes, List<TabularSectionDescriptor> sections) {
    }

    @GetMapping("/{kind}/{name}/{id}")
    public Map<String, Object> list(@PathVariable String kind, @PathVariable String name,
                                    @PathVariable UUID id,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "100") int limit,
                                    AccessSubject subject) {
        Target target = requireReadable(kind, name, id, subject);
        // The cursor is bound to this record and the caller's identity; a foreign one is a 400.
        KeysetWindow.Page<HistoryEntry> page = history.page(id, cursor, limit, Cursor.subjectContext(subject));
        List<HistoryEntry> entries = page.rows();
        Map<HistoryChange, Map<String, Object>> presented = present(target, entries, subject);

        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (HistoryEntry entry : entries) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", entry.id().toString());
            json.put("event", entry.event());
            json.put("at", entry.at().toString());
            json.put("actor", entry.actor());
            json.put("actorId", entry.actorId() == null ? null : entry.actorId().toString());
            List<Map<String, Object>> changes = new ArrayList<>(entry.changes().size());
            for (HistoryChange change : entry.changes()) {
                changes.add(presented.get(change));
            }
            json.put("changes", changes);
            out.add(json);
        }
        return su.onno.service.KeysetWindow.envelope(new KeysetWindow.Page<>(out, page.nextCursor(), page.hasMore()));
    }

    /**
     * Gate a history request exactly like a read of the record: known kind, entity read grant,
     * history opted in (404 — the surface does not exist otherwise), record inside the caller's
     * read scope (404 outside it).
     */
    private Target requireReadable(String kind, String name, UUID id, AccessSubject subject) {
        Target target;
        String type;
        if ("catalogs".equals(kind)) {
            CatalogDescriptor d = catalogQuery.require(name);
            type = "catalog";
            target = new Target(d.javaClass(), d.logicalName(), true, d.attributes(), d.tabularSections());
        } else if ("documents".equals(kind)) {
            DocumentDescriptor d = documentQuery.require(name);
            type = "document";
            target = new Target(d.javaClass(), d.logicalName(), false, d.attributes(), d.tabularSections());
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!access.canRead(subject, type, target.logicalName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current user is not allowed to read " + type + ": " + name);
        }
        if (!history.tracks(target.javaClass())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "History is not enabled for " + type + ": " + name);
        }
        if (catalogQuery.scopes().isScoped(target.javaClass(), subject, AccessMode.READ)) {
            boolean readable = target.catalog()
                    ? catalogQuery.inScope(subject, catalogQuery.require(name), id, AccessMode.READ)
                    : documentQuery.inScope(subject, documentQuery.require(name), id, AccessMode.READ);
            if (!readable) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        return target;
    }

    /**
     * Turn every stored change into its response JSON: field/section labels, a value type hint, and —
     * resolved in one batch per attribute — reference/enumeration displays under the caller's read
     * access.
     */
    private Map<HistoryChange, Map<String, Object>> present(Target target, List<HistoryEntry> entries,
                                                            AccessSubject subject) {
        Map<String, AttributeDescriptor> header = new LinkedHashMap<>();
        for (AttributeDescriptor attr : target.attributes()) {
            header.put(attr.fieldName(), attr);
        }
        if (target.catalog()) {
            header.put("parent", selfReference(target.logicalName()));
        }
        Map<String, Map<String, AttributeDescriptor>> sections = new LinkedHashMap<>();
        for (TabularSectionDescriptor section : target.sections()) {
            Map<String, AttributeDescriptor> attrs = new LinkedHashMap<>();
            for (AttributeDescriptor attr : section.attributes()) {
                attrs.put(attr.fieldName(), attr);
            }
            sections.put(section.name(), attrs);
        }

        Map<HistoryChange, Map<String, Object>> out = new IdentityHashMap<>();
        // attribute → the [old, new] probe rows of every change to it, resolved together.
        Map<AttributeDescriptor, List<Map<String, Object>>> probes = new IdentityHashMap<>();
        Map<Map<String, Object>, Object[]> probeTargets = new IdentityHashMap<>();

        for (HistoryEntry entry : entries) {
            for (HistoryChange change : entry.changes()) {
                AttributeDescriptor attr = change.field() == null ? null
                        : change.section() == null
                        ? header.get(change.field())
                        : sections.getOrDefault(change.section(), Map.of()).get(change.field());
                boolean secret = change.secret() || (attr != null && attr.secret());

                Map<String, Object> json = new LinkedHashMap<>();
                json.put("section", change.section());
                json.put("line", change.line());
                json.put("rowChange", change.rowChange());
                json.put("field", change.field());
                json.put("label", label(change, attr));
                json.put("type", type(change.field(), attr));
                json.put("secret", secret);
                json.put("old", secret ? null : change.oldValue());
                json.put("new", secret ? null : change.newValue());
                out.put(change, json);

                if (!secret && attr != null && resolvable(attr)) {
                    List<Map<String, Object>> rows = probes.computeIfAbsent(attr, a -> new ArrayList<>());
                    for (String side : new String[] {"old", "new"}) {
                        Object raw = json.get(side);
                        if (raw == null) continue;
                        Map<String, Object> probe = new LinkedHashMap<>();
                        probe.put(attr.columnName(), storedValue(attr, raw.toString()));
                        rows.add(probe);
                        probeTargets.put(probe, new Object[] {json, side});
                    }
                }
            }
        }

        for (Map.Entry<AttributeDescriptor, List<Map<String, Object>>> e : probes.entrySet()) {
            AttributeDescriptor attr = e.getKey();
            List<Map<String, Object>> rows = e.getValue();
            if (rows.isEmpty()) continue;
            try {
                catalogQuery.refResolver().resolveAttributes(rows, List.of(attr), subject);
            } catch (RuntimeException unresolvable) {
                continue; // a stored value that no longer parses keeps its raw text
            }
            for (Map<String, Object> probe : rows) {
                Object[] where = probeTargets.get(probe);
                @SuppressWarnings("unchecked")
                Map<String, Object> json = (Map<String, Object>) where[0];
                String side = (String) where[1];
                Object display = probe.get(attr.columnName() + "_display");
                if (display != null) {
                    json.put(side + "Display", display.toString());
                }
                if (Boolean.TRUE.equals(probe.get(attr.columnName() + "_restricted"))) {
                    json.put(side, null); // the caller may not read the referenced record: withhold its id
                }
            }
        }
        return out;
    }

    private static boolean resolvable(AttributeDescriptor attr) {
        return attr.isPolymorphicRef() || (attr.isRef() && attr.refTarget() != null) || attr.javaType().isEnum();
    }

    /** The value in the form the stored column holds it, as {@code RefResolver} expects. */
    private static Object storedValue(AttributeDescriptor attr, String raw) {
        if (attr.isPolymorphicRef()) {
            return raw;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAnId) {
            return raw;
        }
    }

    /** A hierarchical catalog's {@code parent} is a reference to the same catalog. */
    private static AttributeDescriptor selfReference(String logicalName) {
        return new AttributeDescriptor("parent", "", "_parent", UUID.class, 0, false, true, logicalName,
                0, 0, AttributeDescriptor.Constraints.NONE, false);
    }

    private String label(HistoryChange change, AttributeDescriptor attr) {
        if (change.field() == null) {
            return null;
        }
        if (attr != null && attr.displayName() != null && !attr.displayName().isBlank()) {
            return attr.displayName();
        }
        if (change.section() == null) {
            String key = "history.field." + change.field();
            String system = messages.get(key);
            if (system != null && !system.equals(key)) {
                return system;
            }
        }
        return change.field();
    }

    /** A coarse value type so a client can format the raw text: dates, numbers, booleans. */
    private static String type(String field, AttributeDescriptor attr) {
        if (attr == null) {
            return "date".equals(field) ? "datetime" : "folder".equals(field) ? "boolean" : "text";
        }
        Class<?> t = attr.javaType();
        if (attr.isPolymorphicRef() || attr.isRef()) return "ref";
        if (t.isEnum()) return "enum";
        if (t == boolean.class || t == Boolean.class) return "boolean";
        if (t == LocalDate.class) return "date";
        if (t == LocalDateTime.class) return "datetime";
        if (Number.class.isAssignableFrom(t) || t == BigDecimal.class || (t.isPrimitive() && t != char.class)) {
            return "number";
        }
        return "text";
    }
}
