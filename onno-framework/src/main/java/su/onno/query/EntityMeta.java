package su.onno.query;

import su.onno.metadata.AttributeDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A query-layer view over a single queryable entity (catalog / document / register),
 * unifying the per-kind descriptors behind one shape: table name, primary-key column,
 * the built-in system columns, and the user attributes.
 *
 * <p>The {@code systemColumns} map keys are bean-property names (as resolved from getter
 * references) and values are physical column names &mdash; mirroring the framework's
 * naming convention ({@code description -> _description}, {@code number -> _number}, …).
 */
public record EntityMeta(Kind kind,
                         Class<?> type,
                         String table,
                         String pk,
                         Map<String, String> systemColumns,
                         List<AttributeDescriptor> attributes) {

    public enum Kind { CATALOG, DOCUMENT, ACCUMULATION_REGISTER, INFORMATION_REGISTER }

    /** Physical column for a bean-property name, or {@code null} if unknown. */
    public String column(String field) {
        String system = systemColumns.get(field);
        if (system != null) {
            return system;
        }
        for (AttributeDescriptor attr : attributes) {
            if (attr.fieldName().equals(field)) {
                return attr.columnName();
            }
        }
        return null;
    }

    /** Attribute descriptor for a property name, or {@code null} for system / unknown fields. */
    public AttributeDescriptor attribute(String field) {
        for (AttributeDescriptor attr : attributes) {
            if (attr.fieldName().equals(field)) {
                return attr;
            }
        }
        return null;
    }

    // --- System-column maps for each entity kind (mirrors OnnoNamingStrategy) ---

    static Map<String, String> catalogSystemColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "_id");
        m.put("code", "_code");
        m.put("description", "_description");
        m.put("deletionMark", "_deletion_mark");
        m.put("folder", "_is_folder");
        m.put("parent", "_parent");
        m.put("version", "_version");
        return m;
    }

    static Map<String, String> documentSystemColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "_id");
        m.put("number", "_number");
        m.put("date", "_date");
        m.put("posted", "_posted");
        m.put("deletionMark", "_deletion_mark");
        m.put("version", "_version");
        return m;
    }

    static Map<String, String> registerSystemColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "_id");
        m.put("period", "_period");
        m.put("active", "_active");
        m.put("documentRef", "_document_ref");
        m.put("movementType", "_movement_type");
        return m;
    }
}
