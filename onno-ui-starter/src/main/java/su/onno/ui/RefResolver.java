package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.metadata.*;
import su.onno.types.PolyRef;

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
 *
 * <p>Resolution is <strong>per viewer</strong>: a ref target the {@link AccessSubject} may not read
 * (no entity-level read grant, or outside its record scope) is rendered as a <em>restricted</em> ref
 * — {@code {col}_display = "—"}, {@code {col}_restricted = true}, a {@code {col}_ref} of
 * {@code {type, display, restricted: true}} — and, by default, the raw id is withheld from the row,
 * so a list never leaks the display name (or existence) of a record its viewer can't open.
 */
public class RefResolver {

    /** The display a restricted ref renders as. */
    public static final String RESTRICTED_DISPLAY = "—";

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final RecordScopeCompiler scopes;
    private final UiAccessService access;
    private final ConcurrentHashMap<String, Optional<CatalogDescriptor>> catalogsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<DocumentDescriptor>> documentsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Map<String, EnumView>> enumDisplayNames = new ConcurrentHashMap<>();

    /** A resolved enum value's display label and optional badge colour (empty when uncoloured). */
    private record EnumView(String label, String color) {}

    public RefResolver(MetadataRegistry registry, Jdbi jdbi) {
        this(registry, jdbi, RecordScopeCompiler.unrestricted(registry), new UiAccessService(registry));
    }

    public RefResolver(MetadataRegistry registry, Jdbi jdbi, RecordScopeCompiler scopes, UiAccessService access) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.scopes = scopes;
        this.access = access;
    }

    /** Resolve refs/enums for {@code subject}, withholding the ids of restricted refs. */
    public void resolveAttributes(List<Map<String, Object>> rows, List<AttributeDescriptor> attributes,
                                  AccessSubject subject) {
        resolveAttributes(rows, attributes, subject, true);
    }

    /**
     * Resolve refs/enums for {@code subject}. With {@code withholdIds = false} a restricted ref keeps
     * its raw id in the row (used where the id is the row's own grouping key, e.g. list group
     * headers, which must still expand to the matching rows) — its display is masked either way.
     */
    public void resolveAttributes(List<Map<String, Object>> rows, List<AttributeDescriptor> attributes,
                                  AccessSubject subject, boolean withholdIds) {
        if (subject == null) {
            throw new IllegalArgumentException("AccessSubject is required; pass AccessSubject.system() for trusted code");
        }
        for (AttributeDescriptor attr : attributes) {
            if (attr.isPolymorphicRef()) {
                resolvePolymorphicRefColumn(rows, attr, subject, withholdIds);
            } else if (attr.isRef() && attr.refTarget() != null) {
                resolveRefColumn(rows, attr, subject, withholdIds);
            } else if (attr.javaType().isEnum()) {
                resolveEnumColumn(rows, attr);
            }
        }
    }

    /** How far a viewer may see one ref target entity. */
    private sealed interface Visibility {
        /** Readable and unscoped: unresolvable ids keep the pre-3.4 raw-id fallback. */
        record Open() implements Visibility {}
        /** Readable within a record scope: an id outside the scope (or missing) is restricted. */
        record Scoped(ScopeClause clause) implements Visibility {}
        /** No entity-level read grant: every ref is restricted. */
        record Denied() implements Visibility {}
    }

    private Visibility visibility(String kind, String logicalName, Class<?> type, String table,
                                  AccessSubject subject) {
        if (!access.canRead(subject, kind, logicalName)) {
            return new Visibility.Denied();
        }
        ScopeClause clause = scopes.clause(type, subject, su.onno.access.AccessMode.READ, table);
        return clause.isAll() ? new Visibility.Open() : new Visibility.Scoped(clause);
    }

    /** Mark {@code row}'s ref column restricted: masked display, no id unless {@code keepId}. */
    static void restrict(Map<String, Object> row, String column, String type, boolean withholdId) {
        if (withholdId) {
            row.put(column, null);
        }
        row.remove(column + "_code");
        row.remove(column + "_avatar");
        row.remove(column + "_color");
        row.put(column + "_display", RESTRICTED_DISPLAY);
        row.put(column + "_restricted", true);
        Map<String, Object> refMap = new LinkedHashMap<>();
        refMap.put("type", type);
        refMap.put("display", RESTRICTED_DISPLAY);
        refMap.put("restricted", true);
        row.put(column + "_ref", refMap);
    }

    private void resolvePolymorphicRefColumn(List<Map<String, Object>> rows,
                                             AttributeDescriptor attr, AccessSubject subject,
                                             boolean withholdIds) {
        Map<String, ReferenceTargetDescriptor> allowed = new HashMap<>();
        for (ReferenceTargetDescriptor target : attr.refTargets()) {
            allowed.put(target.javaTypeName(), target);
        }
        Map<ReferenceTargetDescriptor, Set<UUID>> idsByTarget = new LinkedHashMap<>();
        Map<Map<String, Object>, PolyRef> refsByRow = new IdentityHashMap<>();
        for (Map<String, Object> row : rows) {
            Object raw = value(row, attr.columnName());
            if (raw == null) continue;
            try {
                PolyRef ref = raw instanceof PolyRef poly
                        ? poly : PolyRef.parse(raw.toString());
                ReferenceTargetDescriptor target = allowed.get(ref.type().getName());
                if (target == null) continue;
                refsByRow.put(row, ref);
                idsByTarget.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(ref.id());
            } catch (IllegalArgumentException invalidStoredReference) {
                // Preserve the raw value; malformed legacy data remains visible rather than
                // breaking the entire list response.
            }
        }
        Map<PolyRefKey, String> displays = new HashMap<>();
        Set<String> closedTargets = new HashSet<>();
        for (Map.Entry<ReferenceTargetDescriptor, Set<UUID>> entry : idsByTarget.entrySet()) {
            ReferenceTargetDescriptor target = entry.getKey();
            if ("document".equals(target.kind())) {
                DocumentDescriptor document = registry.allDocuments().stream()
                        .filter(d -> d.logicalName().equals(target.logicalName()))
                        .findFirst().orElse(null);
                if (document == null) continue;
                Visibility vis = visibility("document", document.logicalName(), document.javaClass(),
                        document.tableName(), subject);
                if (!(vis instanceof Visibility.Open)) closedTargets.add(target.javaTypeName());
                if (vis instanceof Visibility.Denied) continue;
                ScopeClause clause = vis instanceof Visibility.Scoped sc ? sc.clause() : ScopeClause.ALL;
                jdbi.withHandle(handle -> {
                    List<Map.Entry<PolyRefKey, String>> resolved = clause.bind(handle.createQuery(
                                    "SELECT _id, _number FROM " + document.tableName()
                                            + " WHERE _id IN (<ids>)" + clause.and()))
                            .bindList("ids", entry.getValue())
                            .map((rs, ctx) -> Map.entry(
                                    new PolyRefKey(target.javaTypeName(), rs.getObject("_id", UUID.class)),
                                    Objects.toString(rs.getString("_number"), "")))
                            .list();
                    resolved.forEach(value -> displays.put(value.getKey(), value.getValue()));
                    return null;
                });
            } else {
                CatalogDescriptor catalog = registry.allCatalogs().stream()
                        .filter(c -> c.logicalName().equals(target.logicalName()))
                        .findFirst().orElse(null);
                if (catalog == null) continue;
                Visibility vis = visibility("catalog", catalog.logicalName(), catalog.javaClass(),
                        catalog.tableName(), subject);
                if (!(vis instanceof Visibility.Open)) closedTargets.add(target.javaTypeName());
                if (vis instanceof Visibility.Denied) continue;
                ScopeClause clause = vis instanceof Visibility.Scoped sc ? sc.clause() : ScopeClause.ALL;
                jdbi.withHandle(handle -> {
                    List<Map.Entry<PolyRefKey, String>> resolved = clause.bind(handle.createQuery(
                                    "SELECT _id, _code, _description FROM " + catalog.tableName()
                                            + " WHERE _id IN (<ids>)" + clause.and()))
                            .bindList("ids", entry.getValue())
                            .map((rs, ctx) -> {
                                String description = rs.getString("_description");
                                String code = rs.getString("_code");
                                return Map.entry(
                                        new PolyRefKey(
                                                target.javaTypeName(),
                                                rs.getObject("_id", UUID.class)),
                                        description != null && !description.isBlank()
                                                ? description : Objects.toString(code, ""));
                            })
                            .list();
                    resolved.forEach(value -> displays.put(value.getKey(), value.getValue()));
                    return null;
                });
            }
        }
        for (Map.Entry<Map<String, Object>, PolyRef> entry : refsByRow.entrySet()) {
            Map<String, Object> row = entry.getKey();
            PolyRef ref = entry.getValue();
            ReferenceTargetDescriptor target = allowed.get(ref.type().getName());
            PolyRefKey key = new PolyRefKey(target.javaTypeName(), ref.id());
            if (!displays.containsKey(key) && closedTargets.contains(target.javaTypeName())) {
                restrict(row, attr.columnName(), target.logicalName(), withholdIds);
                Map<String, Object> refMap = castMap(row.get(attr.columnName() + "_ref"));
                refMap.put("kind", target.kind());
                continue;
            }
            String display = displays.getOrDefault(key, ref.id().toString());
            row.put(attr.columnName() + "_display", display);
            Map<String, Object> refMap = new LinkedHashMap<>();
            refMap.put("id", ref.id().toString());
            refMap.put("type", target.logicalName());
            refMap.put("kind", target.kind());
            refMap.put("javaType", target.javaTypeName());
            refMap.put("display", display);
            row.put(attr.columnName() + "_ref", refMap);
        }
    }

    private record PolyRefKey(String javaType, UUID id) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void resolveRefColumn(List<Map<String, Object>> rows, AttributeDescriptor attr,
                                  AccessSubject subject, boolean withholdIds) {
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
            resolveCatalogRef(rows, attr, catalog, ids, visibility("catalog", catalog.logicalName(),
                    catalog.javaClass(), catalog.tableName(), subject), withholdIds);
            return;
        }
        DocumentDescriptor document = documentsByName.computeIfAbsent(attr.refTarget(), name ->
                registry.allDocuments().stream()
                        .filter(d -> d.logicalName().equals(name))
                        .findFirst()
        ).orElse(null);
        if (document != null) {
            resolveDocumentRef(rows, attr, document, ids, visibility("document", document.logicalName(),
                    document.javaClass(), document.tableName(), subject), withholdIds);
        }
    }

    private void resolveCatalogRef(List<Map<String, Object>> rows, AttributeDescriptor attr,
                                   CatalogDescriptor catalog, Set<UUID> ids, Visibility vis,
                                   boolean withholdIds) {
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
        ScopeClause clause = vis instanceof Visibility.Scoped sc ? sc.clause() : ScopeClause.ALL;
        Map<UUID, ResolvedRef> resolved = vis instanceof Visibility.Denied ? Map.of() : jdbi.withHandle(h ->
                clause.bind(h.createQuery(selectSql + clause.and()))
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
                if (hit == null && !(vis instanceof Visibility.Open)) {
                    restrict(row, attr.columnName(), attr.refTarget(), withholdIds);
                    continue;
                }
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
                                    DocumentDescriptor document, Set<UUID> ids, Visibility vis,
                                    boolean withholdIds) {
        ScopeClause clause = vis instanceof Visibility.Scoped sc ? sc.clause() : ScopeClause.ALL;
        Map<UUID, String> resolved = vis instanceof Visibility.Denied ? Map.of() : jdbi.withHandle(h ->
                clause.bind(h.createQuery("SELECT _id, _number FROM " + document.tableName()
                                + " WHERE _id IN (<ids>)" + clause.and()))
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
            if (!resolved.containsKey(id) && !(vis instanceof Visibility.Open)) {
                restrict(row, attr.columnName(), attr.refTarget(), withholdIds);
                continue;
            }
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
