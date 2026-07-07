package su.onno.ui.divkit;

import su.onno.metadata.PageWidgetDescriptor;
import su.onno.ui.PageComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders an authored {@link su.onno.ui.Page} to DivKit content: a header, the
 * composed widget area, then any freeform {@link PageComponent} blocks. Built from
 * native DivKit primitives (custom blocks become {@code div-custom}), so it renders
 * on every official SDK.
 */
public final class PageDivBuilder {

    private PageDivBuilder() {}

    public static Map<String, Object> build(String title, String subtitle,
                                            List<PageWidgetDescriptor> widgets,
                                            List<PageComponent> components, int columns,
                                            Function<PageWidgetDescriptor, String> values,
                                            Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        // On desktop the shared time-range picker folds into the header's title row instead of
        // taking a widget row of its own; on mobile (one column) it keeps a full-width row.
        PageWidgetDescriptor timeRange = columns > 1 ? timeRangeWidget(widgets) : null;
        List<PageWidgetDescriptor> grid = timeRange == null
                ? widgets
                : widgets.stream().filter(w -> w != timeRange).toList();
        // The time-range picker is entity-less and writes nothing — write access is moot.
        items.add(Components.pageHeader(title, subtitle,
                timeRange == null ? null : Widgets.custom(timeRange, true), p));

        if (!grid.isEmpty()) {
            items.add(Widgets.grid(grid, columns, values, canWrite, p));
        }

        for (PageComponent c : components) {
            items.add(component(c, p));
        }

        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onno-content");
        Div.contentPadding(root);
        Div.matchWidth(root);
        Div.gap(root, 8);
        return root;
    }

    /** The first shared time-range picker among the page's widgets, or null. */
    static PageWidgetDescriptor timeRangeWidget(List<PageWidgetDescriptor> widgets) {
        return widgets.stream()
                .filter(w -> "timeRange".equals(w.widgetType()))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> component(PageComponent c, Palette p) {
        if (c.kind() == PageComponent.Kind.CUSTOM) {
            Map<String, Object> custom = Div.custom(c.customType(), c.payload());
            Div.matchWidth(custom);
            return custom;
        }
        Map<String, Object> text = Div.color(Div.text(c.text(), 14, "regular"), p.text());
        Div.matchWidth(text);
        return text;
    }
}
