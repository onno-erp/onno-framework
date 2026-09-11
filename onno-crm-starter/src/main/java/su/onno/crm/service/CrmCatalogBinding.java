package su.onno.crm.service;

import java.security.Principal;
import java.util.*;
import java.util.function.*;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

/** Typed, read-only projection of a host catalog. No inheritance from a CRM model is required. */
public final class CrmCatalogBinding<T extends CatalogObject> {
    private final Class<T> type;
    private final Function<UUID, Optional<T>> lookup;
    /** Where a projection files when the host names no section. */
    private static final String DEFAULT_SECTION = "Contact";
    private final LinkedHashMap<String, Column<T>> columns = new LinkedHashMap<>();
    private BiPredicate<T, Principal> readable = (record, principal) -> true;
    private boolean sealed;

    private record Column<T>(String label, String section, String format, Function<T, ?> value,
                             Function<T, String> color) {}

    public CrmCatalogBinding(Class<T> type, Function<UUID, Optional<T>> lookup) {
        if (!type.isAnnotationPresent(Catalog.class)) throw new IllegalArgumentException("CRM binding requires a @Catalog type");
        this.type = type;
        this.lookup = Objects.requireNonNull(lookup);
        field("description", "Name", "text", CatalogObject::getDescription);
    }

    /** Expose only fields deliberately selected by the host, using compiler-checked getters. */
    public CrmCatalogBinding<T> field(String key, String label, String format, Function<T, ?> getter) {
        return field(key, label, DEFAULT_SECTION, format, getter);
    }

    /**
     * The same projection, filed under a named section. The contact panel groups its fields by
     * section and draws one heading per group, so a host that exposes more than a handful of fields
     * can say which belong together — "The wedding" apart from "Pipeline" — instead of handing the
     * reader one undifferentiated list.
     */
    public CrmCatalogBinding<T> field(String key, String label, String section, String format, Function<T, ?> getter) {
        if (sealed) throw new IllegalStateException("CRM binding is already in use");
        if (key == null || !key.matches("[a-z][a-zA-Z0-9]*") || Set.of("id", "version").contains(key))
            throw new IllegalArgumentException("Invalid CRM projection key");
        if (label == null || label.isBlank() || !CrmWorkspaceService.FORMATS.contains(format))
            throw new IllegalArgumentException("Invalid CRM projection label or format");
        if (section == null || section.isBlank() || section.length() > 80)
            throw new IllegalArgumentException("Invalid CRM projection section");
        columns.put(key, new Column<>(label, section, format, Objects.requireNonNull(getter), null));
        return this;
    }

    /**
     * The colour a declared field's value carries, for the formats that draw as a badge. An
     * enumeration already names its own colour on {@code @EnumLabel}; handing it over here is what
     * lets a stage read as the same pill in the contact panel as it does everywhere else. The colour
     * travels beside the value as {@code <key>Color} and is not itself a displayable field.
     */
    public CrmCatalogBinding<T> color(String key, Function<T, String> getter) {
        if (sealed) throw new IllegalStateException("CRM binding is already in use");
        Column<T> column = columns.get(key);
        if (column == null) throw new IllegalArgumentException("Declare the field before its colour: " + key);
        columns.put(key, new Column<>(column.label(), column.section(), column.format(), column.value(),
                Objects.requireNonNull(getter)));
        return this;
    }

    /** Optional host record-level policy, applied in addition to the catalog's onno read roles. */
    public CrmCatalogBinding<T> readableWhen(BiPredicate<T, Principal> policy) {
        if (sealed) throw new IllegalStateException("CRM binding is already in use");
        readable = Objects.requireNonNull(policy);
        return this;
    }

    public Class<T> type() { return type; }
    public String name() { return type.getAnnotation(Catalog.class).name(); }
    public Ref<T> ref(UUID id) { return Ref.of(type, Objects.requireNonNull(id)); }
    public Optional<T> find(UUID id) { return id == null ? Optional.empty() : lookup.apply(id).filter(record -> id.equals(record.getId()) && !record.isDeletionMark()); }
    public boolean canRead(UUID id, Principal principal) { return find(id).filter(record -> readable.test(record, principal)).isPresent(); }
    public T require(UUID id) { return find(id).orElseThrow(() -> new IllegalArgumentException("Bound catalog record is no longer active")); }
    public Map<String, Object> fields(UUID id) {
        sealed = true;
        T record = require(id);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", record.getId().toString());
        columns.forEach((key, column) -> {
            fields.put(key, column.value().apply(record));
            if (column.color() != null) fields.put(key + "Color", column.color().apply(record));
        });
        return fields;
    }
    public List<CrmWorkspaceService.DisplayField> displayFields() {
        sealed = true;
        return columns.entrySet().stream().map(e -> new CrmWorkspaceService.DisplayField(
                "customer." + e.getKey(), e.getValue().label(), e.getValue().section(), e.getValue().format(), true, false)).toList();
    }
}
