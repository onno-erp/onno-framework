package su.onno.ui.divkit;

import su.onno.metadata.DashboardWidgetDescriptor;
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
                                            List<DashboardWidgetDescriptor> widgets,
                                            List<PageComponent> components, int columns,
                                            Function<DashboardWidgetDescriptor, String> values, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Components.pageHeader(title, subtitle, p));

        if (!widgets.isEmpty()) {
            items.add(Widgets.grid(widgets, columns, values, p));
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
