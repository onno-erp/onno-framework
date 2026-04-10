package com.onec.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UiLayoutBuilder {

    private final Map<String, SectionBuilder> sections = new LinkedHashMap<>();
    private final List<WidgetBuilder> widgets = new ArrayList<>();

    public SectionBuilder section(String name) {
        return sections.computeIfAbsent(name, SectionBuilder::new);
    }

    public WidgetBuilder widget(String title) {
        WidgetBuilder wb = new WidgetBuilder(this, title);
        widgets.add(wb);
        return wb;
    }

    public List<UiLayout.Section> build() {
        List<UiLayout.Section> result = new ArrayList<>();
        for (SectionBuilder sb : sections.values()) {
            result.add(sb.build());
        }
        result.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return result;
    }

    public List<WidgetConfig> buildWidgets() {
        List<WidgetConfig> result = new ArrayList<>();
        for (WidgetBuilder wb : widgets) {
            result.add(wb.build());
        }
        result.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return result;
    }

    public boolean hasWidgets() {
        return !widgets.isEmpty();
    }

    public static class SectionBuilder {
        private final String name;
        private int order = 999;
        private String icon = "";
        private UiLayout.Placement placement = UiLayout.Placement.SIDEBAR;
        private final List<EntityRef> entities = new ArrayList<>();

        SectionBuilder(String name) {
            this.name = name;
        }

        public SectionBuilder order(int order) {
            this.order = order;
            return this;
        }

        public SectionBuilder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public SectionBuilder placement(UiLayout.Placement placement) {
            this.placement = placement;
            return this;
        }

        public SectionBuilder catalog(Class<?> clazz) {
            entities.add(new EntityRef("catalog", clazz));
            return this;
        }

        public SectionBuilder document(Class<?> clazz) {
            entities.add(new EntityRef("document", clazz));
            return this;
        }

        public SectionBuilder register(Class<?> clazz) {
            entities.add(new EntityRef("register", clazz));
            return this;
        }

        UiLayout.Section build() {
            return new UiLayout.Section(name, order, icon, placement, List.copyOf(entities));
        }
    }

    public static class WidgetBuilder {
        private final UiLayoutBuilder parent;
        private String title;
        private String type = "count";
        private int order = 0;
        private String width = "1/3";
        private Class<?> entityClass;
        private String entityType;
        private int maxItems = 10;
        private String dateField = "";
        private String titleField = "";

        WidgetBuilder(UiLayoutBuilder parent, String title) {
            this.parent = parent;
            this.title = title;
        }

        public WidgetBuilder type(String type) {
            this.type = type;
            return this;
        }

        public WidgetBuilder order(int order) {
            this.order = order;
            return this;
        }

        public WidgetBuilder width(String width) {
            this.width = width;
            return this;
        }

        public WidgetBuilder catalog(Class<?> clazz) {
            this.entityClass = clazz;
            this.entityType = "catalog";
            return this;
        }

        public WidgetBuilder document(Class<?> clazz) {
            this.entityClass = clazz;
            this.entityType = "document";
            return this;
        }

        public WidgetBuilder register(Class<?> clazz) {
            this.entityClass = clazz;
            this.entityType = "register";
            return this;
        }

        public WidgetBuilder maxItems(int maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public WidgetBuilder dateField(String dateField) {
            this.dateField = dateField;
            return this;
        }

        public WidgetBuilder titleField(String titleField) {
            this.titleField = titleField;
            return this;
        }

        /** Start a new widget. */
        public WidgetBuilder widget(String title) {
            return parent.widget(title);
        }

        /** Switch back to section configuration. */
        public SectionBuilder section(String name) {
            return parent.section(name);
        }

        WidgetConfig build() {
            return new WidgetConfig(title, type, order, width, entityClass, entityType,
                    maxItems, dateField, titleField);
        }
    }

    public record EntityRef(String type, Class<?> javaClass) {}

    public record WidgetConfig(
            String title,
            String type,
            int order,
            String width,
            Class<?> entityClass,
            String entityType,
            int maxItems,
            String dateField,
            String titleField
    ) {}
}
