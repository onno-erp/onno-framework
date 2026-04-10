package com.onec.ui;

import java.util.List;

public record UiLayout(List<Section> sections, List<UiLayoutBuilder.WidgetConfig> widgets) {

    public UiLayout(List<Section> sections) {
        this(sections, List.of());
    }

    public enum Placement {
        SIDEBAR,
        HIDDEN
    }

    public record Section(
            String name,
            int order,
            String icon,
            Placement placement,
            List<UiLayoutBuilder.EntityRef> entityRefs
    ) {}

    public record ResolvedSection(
            String name,
            int order,
            String icon,
            String placement,
            List<ResolvedItem> items
    ) {}

    public record ResolvedItem(
            String name,
            String type,
            String href
    ) {}
}
