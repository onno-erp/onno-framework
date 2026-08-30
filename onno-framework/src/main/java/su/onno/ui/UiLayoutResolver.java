package su.onno.ui;

import su.onno.metadata.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UiLayoutResolver {

    private record OrderedItem(int order, UiLayout.ResolvedItem item) {}

    private final MetadataRegistry registry;

    public UiLayoutResolver(MetadataRegistry registry) {
        this.registry = registry;
    }

    /**
     * Look up field hints for an entity declared in the layout.
     *
     * <p>Returns an empty map if the entity is not referenced by any section or
     * was added via the no-lambda overload. Multiple sections referencing the
     * same entity are not expected; the first match wins.</p>
     */
    public Map<String, FieldHint> resolveFieldHints(UiLayout layout,
                                                     String entityType,
                                                     String entityName) {
        return resolveFieldHints(layout.sections(), entityType, entityName);
    }

    public Map<String, FieldHint> resolveFieldHints(UiLayout.Profile profile,
                                                     String entityType,
                                                     String entityName) {
        return resolveFieldHints(profile.sections(), entityType, entityName);
    }

    private Map<String, FieldHint> resolveFieldHints(List<UiLayout.Section> sections,
                                                     String entityType,
                                                     String entityName) {
        for (UiLayout.Section section : sections) {
            for (UiLayoutBuilder.EntityRef ref : section.entityRefs()) {
                if (!ref.type().equals(entityType)) continue;
                String resolved = resolveEntityNameByClass(ref.type(), ref.javaClass());
                if (entityName.equals(resolved)) {
                    return ref.fieldHints();
                }
            }
        }
        return Map.of();
    }

    public List<UiLayout.ResolvedSection> resolve(UiLayout layout) {
        return resolveSections(layout.sections());
    }

    public List<UiLayout.ResolvedSection> resolve(UiLayout.Profile profile) {
        return resolveSections(profile.sections());
    }

    private List<UiLayout.ResolvedSection> resolveSections(List<UiLayout.Section> sections) {
        List<UiLayout.ResolvedSection> result = new ArrayList<>();

        for (UiLayout.Section section : sections) {
            List<OrderedItem> ordered = new ArrayList<>();
            for (UiLayoutBuilder.EntityRef ref : section.entityRefs()) {
                String name = resolveEntityName(ref);
                if (name != null) {
                    String title = resolveEntityTitleByClass(ref.type(), ref.javaClass());
                    // Route key stays derived from the URL-safe name; the title is display-only.
                    String href = "/" + ref.type() + "s/" + toSnakeCase(name);
                    ordered.add(new OrderedItem(ref.navOrder(),
                            new UiLayout.ResolvedItem(name, title, ref.type(), href, ref.javaClass(), ref.icon())));
                }
            }
            // Page links and entity links share one authored navigation sequence. They remain stored
            // in separate typed collections for entity hint resolution, but merge here in the exact
            // order the Layout DSL declared them.
            for (UiLayout.PageRef page : section.pageRefs()) {
                ordered.add(new OrderedItem(page.navOrder(), new UiLayout.ResolvedItem(
                        page.label(), page.label(), "page", page.route(), null, page.icon())));
            }
            List<UiLayout.ResolvedItem> items = ordered.stream()
                    .sorted(Comparator.comparingInt(OrderedItem::order))
                    .map(OrderedItem::item)
                    .toList();
            result.add(new UiLayout.ResolvedSection(
                    section.name(),
                    section.order(),
                    section.icon(),
                    section.placement().name().toLowerCase(),
                    items
            ));
        }

        return result;
    }

    /** Resolve widget configs to page-widget descriptors. */
    public List<PageWidgetDescriptor> resolveWidgets(UiLayout layout) {
        return resolveWidgets(layout.widgets());
    }

    public List<PageWidgetDescriptor> resolveWidgets(UiLayout.Profile profile) {
        return resolveWidgets(profile.widgets());
    }

    private List<PageWidgetDescriptor> resolveWidgets(List<UiLayoutBuilder.WidgetConfig> widgets) {
        return resolveWidgetConfigs(widgets);
    }

    /**
     * Resolve explicit widget configs (e.g. composed by a {@link Page}) to
     * descriptors. Unlike {@link #resolveWidgets}, an empty list yields an empty
     * result — no annotation fallback — so a page renders exactly what it composes.
     */
    public List<PageWidgetDescriptor> resolveWidgetConfigs(List<UiLayoutBuilder.WidgetConfig> widgets) {
        List<PageWidgetDescriptor> result = new ArrayList<>();
        for (UiLayoutBuilder.WidgetConfig wc : widgets) {
            // An entity-less widget (e.g. a shared time-range picker) is kept as-is; only a widget
            // that *declares* an entity but can't be resolved is dropped.
            String entityName = null;
            if (wc.entityType() != null && wc.entityClass() != null) {
                entityName = resolveEntityNameByClass(wc.entityType(), wc.entityClass());
                if (entityName == null) continue;
            }

            result.add(new PageWidgetDescriptor(
                    wc.title(), wc.type(), wc.order(), wc.width(),
                    wc.entityType(), entityName, wc.maxItems(),
                    resolveWidgetField(wc, wc.dateField()), resolveWidgetField(wc, wc.titleField()),
                    resolveWidgetConfig(wc),
                    wc.hint(), wc.rowBreak()
            ));
        }
        return result;
    }

    private Map<String, String> resolveWidgetConfig(UiLayoutBuilder.WidgetConfig widget) {
        if (widget.extraConfig() == null || widget.extraConfig().isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>(widget.extraConfig());
        for (String key : List.of(
                "endDateField", "durationField", "secondaryField", "amountField",
                "currencyField", "metricField", "secondaryMetricField", "field2",
                "groupBy", "seriesBy", "colorBy",
                "latField", "lngField", "geoJsonField")) {
            String value = resolved.get(key);
            if (value == null || value.isBlank() || value.contains(",")) {
                continue;
            }
            resolved.put(key, resolveWidgetField(widget, value));
        }
        return Map.copyOf(resolved);
    }

    /**
     * Catalog/document widgets consume the preferred logical REST representation, so both typed
     * Java property names and legacy storage-column strings resolve to logical field names here.
     * Registers retain their storage-shaped read contract.
     */
    private String resolveWidgetField(UiLayoutBuilder.WidgetConfig widget, String authored) {
        if (authored == null || authored.isBlank() || widget.entityClass() == null) {
            return authored;
        }
        boolean logicalEntity = "catalog".equals(widget.entityType()) || "document".equals(widget.entityType());
        String field = authored;
        String sidecar = "";
        if (logicalEntity && field.endsWith("_display")) {
            field = field.substring(0, field.length() - "_display".length());
            sidecar = "Display";
        } else if (logicalEntity && field.endsWith("Display")) {
            field = field.substring(0, field.length() - "Display".length());
            sidecar = "Display";
        }
        String lookupField = field;
        String resolvedSidecar = sidecar;
        String systemColumn = switch (widget.entityType()) {
            case "catalog" -> switch (lookupField) {
                case "id", "_id" -> "id";
                case "code", "_code" -> "code";
                case "description", "_description" -> "description";
                case "deletionMark", "_deletion_mark" -> "deletionMark";
                case "folder", "_is_folder" -> "folder";
                case "parent", "_parent" -> "parent";
                case "version", "_version" -> "version";
                default -> null;
            };
            case "document" -> switch (lookupField) {
                case "id", "_id" -> "id";
                case "number", "_number" -> "number";
                case "date", "_date" -> "date";
                case "posted", "_posted" -> "posted";
                case "deletionMark", "_deletion_mark" -> "deletionMark";
                case "version", "_version" -> "version";
                default -> null;
            };
            case "register" -> switch (lookupField) {
                case "id" -> "_id";
                case "period" -> "_period";
                case "active" -> "_active";
                default -> null;
            };
            default -> null;
        };
        if (systemColumn != null) {
            return systemColumn + resolvedSidecar;
        }
        List<AttributeDescriptor> attributes = switch (widget.entityType()) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(d -> d.javaClass().equals(widget.entityClass()))
                    .findFirst().map(CatalogDescriptor::attributes).orElse(List.of());
            case "document" -> registry.allDocuments().stream()
                    .filter(d -> d.javaClass().equals(widget.entityClass()))
                    .findFirst().map(DocumentDescriptor::attributes).orElse(List.of());
            case "register" -> registry.allRegisters().stream()
                    .filter(d -> d.javaClass().equals(widget.entityClass()))
                    .findFirst().map(d -> {
                        List<AttributeDescriptor> all = new ArrayList<>(d.dimensions());
                        all.addAll(d.resources());
                        return all;
                    }).orElse(List.of());
            default -> List.of();
        };
        return attributes.stream()
                .filter(a -> a.fieldName().equals(lookupField) || a.columnName().equals(lookupField))
                .findFirst()
                .map(a -> "register".equals(widget.entityType()) ? a.columnName() : a.fieldName())
                .map(name -> name + resolvedSidecar)
                .orElse(authored);
    }

    private String resolveEntityName(UiLayoutBuilder.EntityRef ref) {
        return resolveEntityNameByClass(ref.type(), ref.javaClass());
    }

    private String resolveEntityNameByClass(String type, Class<?> clazz) {
        if (type == null) return null;
        return switch (type) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(c -> c.javaClass().equals(clazz))
                    .findFirst().map(CatalogDescriptor::logicalName).orElse(null);
            case "document" -> registry.allDocuments().stream()
                    .filter(c -> c.javaClass().equals(clazz))
                    .findFirst().map(DocumentDescriptor::logicalName).orElse(null);
            case "register" -> registry.allRegisters().stream()
                    .filter(c -> c.javaClass().equals(clazz))
                    .findFirst().map(AccumulationRegisterDescriptor::logicalName).orElse(null);
            default -> null;
        };
    }

    /** The human-facing display label (falls back to the logical name when no title is declared). */
    private String resolveEntityTitleByClass(String type, Class<?> clazz) {
        return switch (type) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(c -> c.javaClass().equals(clazz))
                    .findFirst().map(CatalogDescriptor::displayTitle).orElse(null);
            case "document" -> registry.allDocuments().stream()
                    .filter(c -> c.javaClass().equals(clazz))
                    .findFirst().map(DocumentDescriptor::displayTitle).orElse(null);
            case "register" -> registry.allRegisters().stream()
                    .filter(c -> c.javaClass().equals(clazz))
                    .findFirst().map(AccumulationRegisterDescriptor::displayTitle).orElse(null);
            default -> null;
        };
    }

    private static String toSnakeCase(String name) {
        String normalized = name.replace(" ", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
