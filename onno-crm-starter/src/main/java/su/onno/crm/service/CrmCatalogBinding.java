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
    private final LinkedHashMap<String, Column<T>> columns = new LinkedHashMap<>();
    private BiPredicate<T, Principal> readable = (record, principal) -> true;
    private boolean sealed;

    private record Column<T>(Function<T, ?> value, Function<T, String> color) {}

    public CrmCatalogBinding(Class<T> type, Function<UUID, Optional<T>> lookup) {
        if (!type.isAnnotationPresent(Catalog.class)) throw new IllegalArgumentException("CRM binding requires a @Catalog type");
        this.type = type;
        this.lookup = Objects.requireNonNull(lookup);
        field("description", CatalogObject::getDescription);
    }

    /**
     * Expose one field deliberately selected by the host, through a compiler-checked getter. The
     * contact read carries it as {@code key}; how it is labelled and shown is the client's.
     */
    public CrmCatalogBinding<T> field(String key, Function<T, ?> getter) {
        if (sealed) throw new IllegalStateException("CRM binding is already in use");
        if (key == null || !key.matches("[a-z][a-zA-Z0-9]*") || Set.of("id", "version").contains(key))
            throw new IllegalArgumentException("Invalid CRM projection key");
        columns.put(key, new Column<>(Objects.requireNonNull(getter), null));
        return this;
    }

    /**
     * The colour a declared field's value carries — an enumeration's {@code @EnumLabel} colour, a
     * stage's — so a client can draw the value as the same pill it is everywhere else. It travels
     * beside the value as {@code <key>Color} and is not itself a field.
     */
    public CrmCatalogBinding<T> color(String key, Function<T, String> getter) {
        if (sealed) throw new IllegalStateException("CRM binding is already in use");
        Column<T> column = columns.get(key);
        if (column == null) throw new IllegalArgumentException("Declare the field before its colour: " + key);
        columns.put(key, new Column<>(column.value(), Objects.requireNonNull(getter)));
        return this;
    }

    /**
     * Optional host record-level policy, applied in addition to the catalog's onno read roles.
     *
     * @deprecated since 3.4.0: declare a {@link su.onno.access.RecordAccessPolicy} for the bound
     *             catalog instead — it is applied by the CRM inbox and by every other surface, and
     *             pushed into the query rather than filtered in memory. This predicate still runs in
     *             addition and is slated for removal in 4.0.
     */
    @Deprecated(since = "3.4.0")
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
    /** The bound fields as {@code customer.<key>}, in declaration order. */
    public List<String> keys() {
        sealed = true;
        return columns.keySet().stream().map(key -> "customer." + key).toList();
    }
}
