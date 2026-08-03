package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.TabularSectionDescriptor;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates database-shaped entity rows into the stable logical JSON vocabulary exposed by the
 * generated REST API. Storage-shaped responses remain available explicitly for existing headless
 * clients while they migrate; the bundled UI consumes the logical representation.
 */
final class EntityJsonRepresentation {

    enum Mode {
        LOGICAL,
        STORAGE
    }

    private static final Map<String, String> CATALOG_SYSTEM_KEYS = Map.ofEntries(
            Map.entry("_id", "id"),
            Map.entry("_code", "code"),
            Map.entry("_description", "description"),
            Map.entry("_deletion_mark", "deletionMark"),
            Map.entry("_is_folder", "folder"),
            Map.entry("_parent", "parent"),
            Map.entry("_version", "version"),
            Map.entry("_actions", "actions"),
            Map.entry("_style", "style")
    );

    private static final Map<String, String> DOCUMENT_SYSTEM_KEYS = Map.ofEntries(
            Map.entry("_id", "id"),
            Map.entry("_number", "number"),
            Map.entry("_date", "date"),
            Map.entry("_posted", "posted"),
            Map.entry("_deletion_mark", "deletionMark"),
            Map.entry("_version", "version"),
            Map.entry("_actions", "actions"),
            Map.entry("_style", "style")
    );

    private static final Map<String, String> TABULAR_SYSTEM_KEYS = Map.of(
            "_id", "id",
            "_parent_id", "parentId",
            "_line_number", "lineNumber"
    );

    private static final Map<String, String> ATTRIBUTE_SIDECARS = Map.of(
            "_display", "Display",
            "_ref", "Ref",
            "_code", "Code",
            "_avatar", "Avatar",
            "_color", "Color"
    );

    private EntityJsonRepresentation() {
    }

    static Mode parse(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("logical")) {
            return Mode.LOGICAL;
        }
        if (value.equalsIgnoreCase("storage")) {
            return Mode.STORAGE;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown representation '" + value + "'; expected 'logical' or 'storage'");
    }

    static Map<String, Object> catalog(CatalogDescriptor descriptor, Map<String, Object> row,
                                       Mode mode) {
        if (mode == Mode.STORAGE) {
            return row;
        }
        return logicalCatalog(descriptor, row);
    }

    static List<Map<String, Object>> catalogs(CatalogDescriptor descriptor,
                                               List<Map<String, Object>> rows, Mode mode) {
        if (mode == Mode.STORAGE) {
            return rows;
        }
        List<Map<String, Object>> logical = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            logical.add(logicalCatalog(descriptor, row));
        }
        return logical;
    }

    static Map<String, Object> document(DocumentDescriptor descriptor, Map<String, Object> row,
                                        Mode mode) {
        if (mode == Mode.STORAGE) {
            return row;
        }
        return logicalDocument(descriptor, row);
    }

    static List<Map<String, Object>> documents(DocumentDescriptor descriptor,
                                                List<Map<String, Object>> rows, Mode mode) {
        if (mode == Mode.STORAGE) {
            return rows;
        }
        List<Map<String, Object>> logical = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            logical.add(logicalDocument(descriptor, row));
        }
        return logical;
    }

    private static Map<String, Object> logicalCatalog(CatalogDescriptor descriptor,
                                                       Map<String, Object> row) {
        Map<String, Object> logical = logicalRow(row, CATALOG_SYSTEM_KEYS, descriptor.attributes());
        Object children = valueIgnoreCase(row, "children");
        if (children instanceof List<?> rows) {
            logical.put("children", catalogChildren(descriptor, rows));
        }
        return logical;
    }

    private static List<Map<String, Object>> catalogChildren(CatalogDescriptor descriptor, List<?> rows) {
        List<Map<String, Object>> logical = new ArrayList<>(rows.size());
        for (Object value : rows) {
            if (value instanceof Map<?, ?> row) {
                logical.add(logicalCatalog(descriptor, stringKeyMap(row)));
            }
        }
        return logical;
    }

    private static Map<String, Object> logicalDocument(DocumentDescriptor descriptor,
                                                        Map<String, Object> row) {
        Map<String, Object> logical = logicalRow(row, DOCUMENT_SYSTEM_KEYS, descriptor.attributes());
        for (TabularSectionDescriptor section : descriptor.tabularSections()) {
            Object sectionValue = valueIgnoreCase(row, section.name());
            if (!(sectionValue instanceof List<?> rows)) {
                continue;
            }
            List<Map<String, Object>> logicalRows = new ArrayList<>(rows.size());
            for (Object value : rows) {
                if (value instanceof Map<?, ?> sectionRow) {
                    logicalRows.add(logicalRow(stringKeyMap(sectionRow), TABULAR_SYSTEM_KEYS,
                            section.attributes()));
                }
            }
            logical.put(section.name(), logicalRows);
        }
        return logical;
    }

    private static Map<String, Object> logicalRow(Map<String, Object> row,
                                                   Map<String, String> systemKeys,
                                                   List<AttributeDescriptor> attributes) {
        Map<String, Object> logical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            logical.put(logicalKey(entry.getKey(), systemKeys, attributes), entry.getValue());
        }
        return logical;
    }

    private static String logicalKey(String key, Map<String, String> systemKeys,
                                     List<AttributeDescriptor> attributes) {
        String lowerKey = key.toLowerCase(Locale.ROOT);
        String systemKey = systemKeys.get(lowerKey);
        if (systemKey != null) {
            return systemKey;
        }
        for (AttributeDescriptor attribute : attributes) {
            String column = attribute.columnName().toLowerCase(Locale.ROOT);
            if (lowerKey.equals(column)) {
                return attribute.fieldName();
            }
            for (Map.Entry<String, String> sidecar : ATTRIBUTE_SIDECARS.entrySet()) {
                if (lowerKey.equals(column + sidecar.getKey())) {
                    return attribute.fieldName() + sidecar.getValue();
                }
            }
        }
        return key;
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value != null || row.containsKey(key)) {
            return value;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
