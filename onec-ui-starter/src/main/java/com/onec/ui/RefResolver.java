package com.onec.ui;

import com.onec.metadata.*;

import org.jdbi.v3.core.Jdbi;

import java.util.*;

/**
 * Resolves Ref UUID columns and Enum UUID columns to human-readable display values.
 * Adds "{columnName}_display" and "{columnName}_ref" keys to each row map.
 */
public class RefResolver {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;

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

        CatalogDescriptor catalog = registry.allCatalogs().stream()
                .filter(c -> c.logicalName().equals(attr.refTarget()))
                .findFirst().orElse(null);
        if (catalog == null) return;

        // Detect optional avatar column on the target catalog (convention: avatar_url).
        String avatarColumn = catalog.attributes().stream()
                .map(AttributeDescriptor::columnName)
                .filter(c -> c.equalsIgnoreCase("avatar_url"))
                .findFirst()
                .orElse(null);

        String selectSql = "SELECT _id, _code, _description"
                + (avatarColumn != null ? ", " + avatarColumn : "")
                + " FROM " + catalog.tableName()
                + " WHERE _id IN (<ids>)";

        final String avatarCol = avatarColumn;
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
                            map.put(rv.getColumn("_id", UUID.class),
                                    new ResolvedRef(display, code, avatar));
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

                row.put(attr.columnName() + "_display", display);
                if (code != null && !code.isBlank()) {
                    row.put(attr.columnName() + "_code", code);
                }
                if (avatarUrl != null && !avatarUrl.isBlank()) {
                    row.put(attr.columnName() + "_avatar", avatarUrl);
                }

                Map<String, Object> refMap = new LinkedHashMap<>();
                refMap.put("id", id.toString());
                refMap.put("type", attr.refTarget());
                refMap.put("display", display);
                if (code != null && !code.isBlank()) refMap.put("code", code);
                if (avatarUrl != null && !avatarUrl.isBlank()) refMap.put("avatarUrl", avatarUrl);
                row.put(attr.columnName() + "_ref", refMap);
            }
        }
    }

    private record ResolvedRef(String display, String code, String avatarUrl) {}

    private void resolveEnumColumn(List<Map<String, Object>> rows, AttributeDescriptor attr) {
        EnumerationDescriptor enumDesc = registry.allEnumerations().stream()
                .filter(e -> e.javaClass().equals(attr.javaType()))
                .findFirst().orElse(null);
        if (enumDesc == null) return;

        Map<String, String> idToName = new HashMap<>();
        for (EnumerationValueDescriptor v : enumDesc.values()) {
            idToName.put(v.id().toString(), v.name());
        }

        for (Map<String, Object> row : rows) {
            Object val = value(row, attr.columnName());
            if (val != null) {
                String name = idToName.get(val.toString());
                row.put(attr.columnName() + "_display", name != null ? name : val.toString());
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
