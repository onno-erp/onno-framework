package su.onno.picker;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the {@link PickerOptions} bean bound to a reference field — by the field's owner
 * entity and path — and applies it to a picker result page. A request names the owner as
 * {@code ownerKind} ({@code catalog}/{@code document}, singular or plural) and {@code ownerName}
 * (logical name); a field without a bean is left undecorated.
 */
public final class PickerOptionsService {

    private final MetadataRegistry registry;
    private final Map<Binding, PickerOptions<?>> byField;

    private record Binding(Class<?> owner, String field) {
    }

    @SuppressWarnings({"rawtypes"})
    public PickerOptionsService(MetadataRegistry registry, List<PickerOptions> beans) {
        this.registry = registry;
        Map<Binding, PickerOptions<?>> map = new LinkedHashMap<>();
        for (PickerOptions<?> bean : beans) {
            if (bean.entity() == null || bean.field() == null || bean.field().isBlank()) {
                throw new IllegalStateException("PickerOptions " + bean.getClass().getName()
                        + " must name its entity() and field()");
            }
            Binding binding = new Binding(bean.entity(), bean.field());
            PickerOptions<?> previous = map.putIfAbsent(binding, bean);
            if (previous != null) {
                throw new IllegalStateException("Two PickerOptions beans bind " + bean.entity().getSimpleName()
                        + "." + bean.field() + ": " + previous.getClass().getName() + " and "
                        + bean.getClass().getName());
            }
        }
        this.byField = Map.copyOf(map);
    }

    /**
     * Apply the field's decorations to {@code rows} in place: badge/tone/colour/disabled/reason go
     * into {@code _option*} keys and hidden options are dropped. Undecorated fields return the rows
     * unchanged.
     */
    public List<Map<String, Object>> decorate(String ownerKind, String ownerName,
                                              PickerContext context,
                                              List<Map<String, Object>> rows) {
        Map<UUID, PickerOption> resolved = decorations(ownerKind, ownerName, context, rows);
        if (resolved.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> visibleRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            PickerOption option = resolved.get(optionId(row));
            if (option == null) {
                visibleRows.add(row);
                continue;
            }
            if (option.hidden()) {
                continue;
            }
            if (!option.badge().isBlank()) {
                row.put("_optionBadge", option.badge());
                row.put("_optionTone", option.tone().name().toLowerCase());
            }
            if (!option.color().isBlank()) {
                row.put("_optionColor", option.color());
            }
            if (option.disabled()) {
                row.put("_optionDisabled", true);
            }
            if (!option.reason().isBlank()) {
                row.put("_optionReason", option.reason());
            }
            visibleRows.add(row);
        }
        return visibleRows;
    }

    /**
     * The raw decorations the field's bean resolves for {@code rows}, keyed by record id; empty
     * when the field has no bean. The picker dialog applies them to its list window itself, so it
     * hides and disables exactly what the dropdown would.
     */
    public Map<UUID, PickerOption> decorations(String ownerKind, String ownerName,
                                               PickerContext context,
                                               List<Map<String, Object>> rows) {
        PickerOptions<?> bean = find(ownerKind, ownerName, context == null ? null : context.fieldPath());
        if (bean == null || rows.isEmpty()) {
            return Map.of();
        }
        List<PickerCandidate> candidates = rows.stream()
                .map(row -> new PickerCandidate(optionId(row), row))
                .toList();
        Map<UUID, PickerOption> resolved = bean.decorate(context, candidates);
        return resolved == null ? Map.of() : resolved;
    }

    /** The bean bound to {@code ownerKind/ownerName}'s {@code fieldPath}, or {@code null}. */
    PickerOptions<?> find(String ownerKind, String ownerName, String fieldPath) {
        if (byField.isEmpty() || ownerName == null || ownerName.isBlank()
                || fieldPath == null || fieldPath.isBlank()) {
            return null;
        }
        Class<?> owner = owner(ownerKind, ownerName);
        return owner == null ? null : byField.get(new Binding(owner, fieldPath));
    }

    private Class<?> owner(String kind, String name) {
        String k = kind == null ? "" : kind.toLowerCase();
        if (k.startsWith("document")) {
            for (DocumentDescriptor d : registry.allDocuments()) {
                if (d.logicalName().equals(name)) return d.javaClass();
            }
        } else if (k.startsWith("catalog")) {
            for (CatalogDescriptor d : registry.allCatalogs()) {
                if (d.logicalName().equals(name)) return d.javaClass();
            }
        }
        return null;
    }

    private static UUID optionId(Map<String, Object> row) {
        Object value = row.get("_id");
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }
}
