package su.onno.ui;

import su.onno.metadata.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UiLayoutResolver {

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
            List<UiLayout.ResolvedItem> items = new ArrayList<>();
            for (UiLayoutBuilder.EntityRef ref : section.entityRefs()) {
                String name = resolveEntityName(ref);
                if (name != null) {
                    String title = resolveEntityTitleByClass(ref.type(), ref.javaClass());
                    // Route key stays derived from the URL-safe name; the title is display-only.
                    String href = "/" + ref.type() + "s/" + toSnakeCase(name);
                    items.add(new UiLayout.ResolvedItem(name, title, ref.type(), href, ref.javaClass(), ref.icon()));
                }
            }
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
                    wc.dateField(), wc.titleField(),
                    wc.extraConfig() == null ? Map.of() : wc.extraConfig(),
                    wc.hint(), wc.rowBreak()
            ));
        }
        return result;
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
