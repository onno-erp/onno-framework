package su.onno.access;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The storage shape of an entity a {@link RecordScope} can be evaluated against: its tables and the
 * fields a scope may name, each mapped to its column. Built from the metadata; secret and
 * polymorphic-reference attributes are not scope-able.
 *
 * @param type            the domain class
 * @param kind            the entity kind
 * @param logicalName     the registered logical name
 * @param tableName       the main table (movements for an accumulation register)
 * @param totalsTableName the balance totals table of an accumulation register, else {@code null}
 * @param readRoles       the entity-level read roles
 * @param fields          field name to scope-able field
 */
public record ScopedEntity(
        Class<?> type,
        Kind kind,
        String logicalName,
        String tableName,
        String totalsTableName,
        List<String> readRoles,
        Map<String, Field> fields
) {

    public enum Kind { CATALOG, DOCUMENT, ACCUMULATION_REGISTER, INFORMATION_REGISTER }

    /**
     * A field a scope may compare.
     *
     * @param name      the logical field name
     * @param column    the storage column
     * @param javaType  the Java type (an enum class, {@code UUID} for refs and ids, ...)
     * @param refTarget the logical name of the referenced catalog/document for a ref, else {@code null}
     */
    public record Field(String name, String column, Class<?> javaType, String refTarget) {

        public boolean isRef() {
            return refTarget != null;
        }

        /** Whether the column stores a UUID (a ref, an enum, or an id). */
        public boolean uuidColumn() {
            return refTarget != null || javaType == UUID.class || javaType.isEnum()
                    || su.onno.types.Ref.class.isAssignableFrom(javaType);
        }
    }

    public boolean isRegister() {
        return kind == Kind.ACCUMULATION_REGISTER || kind == Kind.INFORMATION_REGISTER;
    }

    /** The field named {@code name}, or {@code null}. */
    public Field field(String name) {
        return fields.get(name);
    }

    static ScopedEntity of(CatalogDescriptor d) {
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("id", new Field("id", "_id", UUID.class, null));
        fields.put("code", new Field("code", "_code", String.class, null));
        fields.put("description", new Field("description", "_description", String.class, null));
        if (d.hierarchical()) {
            fields.put("parent", new Field("parent", "_parent", UUID.class, d.logicalName()));
        }
        addAttributes(fields, d.attributes());
        return new ScopedEntity(d.javaClass(), Kind.CATALOG, d.logicalName(), d.tableName(), null,
                d.readRoles(), Collections.unmodifiableMap(fields));
    }

    static ScopedEntity of(DocumentDescriptor d) {
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("id", new Field("id", "_id", UUID.class, null));
        fields.put("number", new Field("number", "_number", String.class, null));
        fields.put("date", new Field("date", "_date", LocalDateTime.class, null));
        fields.put("posted", new Field("posted", "_posted", Boolean.class, null));
        addAttributes(fields, d.attributes());
        return new ScopedEntity(d.javaClass(), Kind.DOCUMENT, d.logicalName(), d.tableName(), null,
                d.readRoles(), Collections.unmodifiableMap(fields));
    }

    static ScopedEntity of(AccumulationRegisterDescriptor d) {
        Map<String, Field> fields = new LinkedHashMap<>();
        addAttributes(fields, d.dimensions());
        return new ScopedEntity(d.javaClass(), Kind.ACCUMULATION_REGISTER, d.logicalName(), d.tableName(),
                d.totalsTableName(), d.readRoles(), Collections.unmodifiableMap(fields));
    }

    static ScopedEntity of(InformationRegisterDescriptor d) {
        Map<String, Field> fields = new LinkedHashMap<>();
        addAttributes(fields, d.dimensions());
        return new ScopedEntity(d.javaClass(), Kind.INFORMATION_REGISTER, d.logicalName(), d.tableName(),
                null, d.readRoles(), Collections.unmodifiableMap(fields));
    }

    private static void addAttributes(Map<String, Field> fields, List<AttributeDescriptor> attributes) {
        for (AttributeDescriptor a : attributes) {
            if (a.secret() || a.isPolymorphicRef()) continue; // not scope-able
            fields.put(a.fieldName(), new Field(a.fieldName(), a.columnName(), a.javaType(),
                    a.isRef() ? a.refTarget() : null));
        }
    }
}
