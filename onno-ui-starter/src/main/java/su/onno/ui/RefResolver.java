package su.onno.ui;

import su.onno.metadata.*;

import org.jdbi.v3.core.Jdbi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Ref UUID columns and Enum UUID columns to human-readable display values.
 * Adds "{columnName}_display" and "{columnName}_ref" keys to each row map, plus
 * "{columnName}_color" for an enum value that declares an {@code @EnumLabel(color = …)} badge colour.
 *
 * <p>Descriptor lookups (logical name → catalog/document, enum class → enumeration,
 * enum id → display name) are cached: the registry never changes after startup
 * scanning, and these resolvers run on every list/get response.
 */
public class RefResolver {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final ConcurrentHashMap<String, Optional<CatalogDescriptor>> catalogsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<DocumentDescriptor>> documentsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Map<String, EnumView>> enumDisplayNames = new ConcurrentHashMap<>();

    /** A resolved enum value's display label and optional badge colour (empty when uncoloured). */
    private record EnumView(String label, String color) {}

    public RefResolver(MetadataRegistry registry, Jdbi jdbi) {
        this.registry = registry;
        this.jdbi = jdbi;
    }

    public void resolveAttributes(List<Map<String, Object>> rows, List<AttributeDescriptor> attributes) {
        for (AttributeDescriptor attr : attributes) {
            if (attr.isRef() && attr.refTarget() != null) {
                resolveRefColumn(rows, attr);
            } else if (attr.javaType().isEnum()) {
                resolveEnumColumn(rows, attr);
            }
        }
    }

    private void resolveRefColumn(List<Map<String, Object>> rows, AttributeDescriptor attr) {
        Set<UUID> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object val = row.get(attr.columnName());
            if (val == null) val = row.get(attr.columnName().toUpperCase(Locale.ROOT));
            if (val != null) {
                ids.add(toUUID(val));
            }
        }
        if (ids.isEmpty()) return;

        // refTarget is the registered logical name; a ref can point at a catalog or a
        // document. Resolve each against the right table (catalogs by code/description,
        // documents by number).
        CatalogDescriptor catalog = catalogsByName.computeIfAbsent(attr.refTarget(), name ->
                registry.allCatalogs().stream()
                        .filter(c -> c.logicalName().equals(name))
                        .findFirst()
        ).orElse(null);
        if (catalog != null) {
            resolveCatalogRef(rows, attr, catalog, ids);
            return;
        }
        DocumentDescriptor document = documentsByName.computeIfAbsent(attr.refTarget(), name ->
                registry.allDocuments().stream()
                        .filter(d -> d.logicalName().equals(name))
                        .findFirst()
        ).orElse(null);
        if (document != null) {
            resolveDocumentRef(rows, attr, document, ids);
        }
    }

    private void resolveCatalogRef(List<Map<String, Object>> rows, AttributeDescriptor attr,
                                   CatalogDescriptor catalog, Set<UUID> ids) {
        // Detect optional presentation columns on the target catalog by name convention:
        // avatar_url → a thumbnail beside the display name; color → the referencing cell renders
        // as a colored pill ({col}_color), exactly like an @EnumLabel(color=…) enum value — this
        // is what lets a user-editable status CATALOG keep the colored status pills an enum had.
        String avatarColumn = catalog.attributes().stream()
                .map(AttributeDescriptor::columnName)
                .filter(c -> c.equalsIgnoreCase("avatar_url"))
                .findFirst()
                .orElse(null);
        String colorColumn = catalog.attributes().stream()
                .map(AttributeDescriptor::columnName)
                .filter(c -> c.equalsIgnoreCase("color"))
                .findFirst()
                .orElse(null);

        String selectSql = "SELECT _id, _code, _description"
                + (avatarColumn != null ? ", " + avatarColumn : "")
                + (colorColumn != null ? ", " + colorColumn : "")
                + " FROM " + catalog.tableName()
                + " WHERE _id IN (<ids>)";

        final String avatarCol = avatarColumn;
        final String colorCol = colorColumn;
        Map<UUID, ResolvedRef> resolved = jdbi.withHandle(h ->
                h.createQuery(selectSql)
                        .bindList("ids", new ArrayList<>(ids))
                        .reduceRows(new HashMap<>(), (map, rv) -> {
                            String code = rv.getColumn("_code", String.class);
                            String description = rv.getColumn("_description", String.class);
                            String display = description != null && !description.isBlank()
                                    ? description
                                    : (code != null ? code : "");
                            String avatar = avatarCol != null ? rv.getColumn(avatarCol, String.class) : null;
                            String color = colorCol != null ? rv.getColumn(colorCol, String.class) : null;
                            map.put(rv.getColumn("_id", UUID.class),
                                    new ResolvedRef(display, code, avatar, color));
                            return map;
                        })
        );

        for (Map<String, Object> row : rows) {
            Object val = value(row, attr.columnName());
            if (val != null) {
                UUID id = toUUID(val);
                ResolvedRef hit = resolved.get(id);
                String display = hit != null ? hit.display() : null;
                if (display == null || display.isBlank()) display = val.toString();
                String code = hit != null ? hit.code() : null;
                String avatarUrl = hit != null ? hit.avatarUrl() : null;
                String color = hit != null ? hit.color() : null;

                row.put(attr.columnName() + "_display", display);
                if (code != null && !code.isBlank()) {
                    row.put(attr.columnName() + "_code", code);
                }
                if (avatarUrl != null && !avatarUrl.isBlank()) {
                    row.put(attr.columnName() + "_avatar", avatarUrl);
                }
                if (color != null && !color.isBlank()) {
                    row.put(attr.columnName() + "_color", color);
                }

                Map<String, Object> refMap = new LinkedHashMap<>();
                refMap.put("id", id.toString());
                refMap.put("type", attr.refTarget());
                refMap.put("display", display);
                if (code != null && !code.isBlank()) refMap.put("code", code);
                if (avatarUrl != null && !avatarUrl.isBlank()) refMap.put("avatarUrl", avatarUrl);
                if (color != null && !color.isBlank()) refMap.put("color", color);
                row.put(attr.columnName() + "_ref", refMap);
            }
        }
    }

    private void resolveDocumentRef(List<Map<String, Object>> rows, AttributeDescriptor attr,
                                    DocumentDescriptor document, Set<UUID> ids) {
        Map<UUID, String> resolved = jdbi.withHandle(h ->
                h.createQuery("SELECT _id, _number FROM " + document.tableName() + " WHERE _id IN (<ids>)")
                        .bindList("ids", new ArrayList<>(ids))
                        .reduceRows(new HashMap<>(), (map, rv) -> {
                            map.put(rv.getColumn("_id", UUID.class), rv.getColumn("_number", String.class));
                            return map;
                        })
        );

        for (Map<String, Object> row : rows) {
            Object val = value(row, attr.columnName());
            if (val == null) continue;
            UUID id = toUUID(val);
            String display = resolved.get(id);
            if (display == null || display.isBlank()) display = val.toString();

            row.put(attr.columnName() + "_display", display);

            Map<String, Object> refMap = new LinkedHashMap<>();
            refMap.put("id", id.toString());
            refMap.put("type", attr.refTarget());
            refMap.put("display", display);
            row.put(attr.columnName() + "_ref", refMap);
        }
    }

    private record ResolvedRef(String display, String code, String avatarUrl, String color) {}

    private void resolveEnumColumn(List<Map<String, Object>> rows, AttributeDescriptor attr) {
        Map<String, EnumView> idToView = enumDisplayNames.computeIfAbsent(attr.javaType(), type -> {
            EnumerationDescriptor enumDesc = registry.allEnumerations().stream()
                    .filter(e -> e.javaClass().equals(type))
                    .findFirst().orElse(null);
            if (enumDesc == null) return Map.of();
            Map<String, EnumView> views = new HashMap<>();
            for (EnumerationValueDescriptor v : enumDesc.values()) {
                views.put(v.id().toString(), new EnumView(v.label(), v.color()));
            }
            return Map.copyOf(views);
        });
        if (idToView.isEmpty()) return;

        for (Map<String, Object> row : rows) {
            Object val = value(row, attr.columnName());
            if (val != null) {
                EnumView view = idToView.get(val.toString());
                row.put(attr.columnName() + "_display", view != null ? view.label() : val.toString());
                // A colour rides alongside _display so list/detail cells can paint a status pill.
                if (view != null && !view.color().isEmpty()) {
                    row.put(attr.columnName() + "_color", view.color());
                }
            }
        }
    }

    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value != null) return value;
        value = row.get(key.toUpperCase(Locale.ROOT));
        if (value != null) return value;
        return row.get(key.toLowerCase(Locale.ROOT));
    }

    private static UUID toUUID(Object val) {
        return val instanceof UUID u ? u : UUID.fromString(val.toString());
    }
}
