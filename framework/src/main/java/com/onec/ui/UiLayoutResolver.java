package com.onec.ui;

import com.onec.metadata.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UiLayoutResolver {

    private final MetadataRegistry registry;

    public UiLayoutResolver(MetadataRegistry registry) {
        this.registry = registry;
    }

    public List<UiLayout.ResolvedSection> resolve(UiLayout layout) {
        List<UiLayout.ResolvedSection> result = new ArrayList<>();

        for (UiLayout.Section section : layout.sections()) {
            List<UiLayout.ResolvedItem> items = new ArrayList<>();
            for (UiLayoutBuilder.EntityRef ref : section.entityRefs()) {
                String name = resolveEntityName(ref);
                if (name != null) {
                    String href = "/" + ref.type() + "s/" + toSnakeCase(name);
                    items.add(new UiLayout.ResolvedItem(name, ref.type(), href));
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

    /**
     * Resolve widget configs to dashboard descriptors.
     * If the layout has explicit widgets, use those; otherwise fall back to annotation-based.
     */
    public List<DashboardWidgetDescriptor> resolveWidgets(UiLayout layout) {
        if (layout.widgets().isEmpty()) {
            // Fall back to annotation-based widgets from registry
            return registry.allDashboardWidgets().stream()
                    .sorted(java.util.Comparator.comparingInt(DashboardWidgetDescriptor::order))
                    .toList();
        }

        List<DashboardWidgetDescriptor> result = new ArrayList<>();
        for (UiLayoutBuilder.WidgetConfig wc : layout.widgets()) {
            String entityName = resolveEntityNameByClass(wc.entityType(), wc.entityClass());
            if (entityName == null) continue;

            result.add(new DashboardWidgetDescriptor(
                    wc.title(), wc.type(), wc.order(), wc.width(),
                    wc.entityType(), entityName, wc.maxItems(),
                    wc.dateField(), wc.titleField(), Map.of()
            ));
        }
        return result;
    }

    private String resolveEntityName(UiLayoutBuilder.EntityRef ref) {
        return resolveEntityNameByClass(ref.type(), ref.javaClass());
    }

    private String resolveEntityNameByClass(String type, Class<?> clazz) {
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
