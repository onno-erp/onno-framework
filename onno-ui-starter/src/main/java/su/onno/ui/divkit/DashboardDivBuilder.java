package su.onno.ui.divkit;

import su.onno.metadata.DashboardWidgetDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the home/dashboard content div: a greeting header and the widget area.
 * Each widget compiles per {@link Widgets} — a native card for {@code count}/
 * {@code metric}, a {@code div-custom} block for {@code chart}/{@code calendar}/
 * {@code kanban}/{@code list} (and any app-registered type) that the client renders
 * with a real component.
 */
public final class DashboardDivBuilder {

    private DashboardDivBuilder() {}

    public static Map<String, Object> build(String title, String greeting,
                                            List<DashboardWidgetDescriptor> widgets, int columns,
                                            Function<DashboardWidgetDescriptor, String> values, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Components.pageHeader(title, greeting, p));

        if (widgets.isEmpty()) {
            items.add(Components.card(List.of(
                    Div.color(Div.text("Nothing here yet", 14, "regular"), p.muted())), p));
        } else {
            items.add(Widgets.grid(widgets, columns, values, p));
        }
        return content(items);
    }

    /**
     * The neutral landing for an app with no dashboard — no authored {@code Page} at
     * {@code "/"} and no (readable) widgets. Deliberately blank: no "Dashboard" title,
     * greeting, or "Nothing here yet" card, so a dashboard-less app never opens onto a
     * phantom dashboard. The client lands the user on the first real nav item instead
     * (see {@code DivKitController#shell} "home"); this surface only shows when the app
     * exposes nothing at all.
     */
    public static Map<String, Object> empty() {
        return content(List.of());
    }

    private static Map<String, Object> content(List<Map<String, Object>> items) {
        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onno-content");
        Div.contentPadding(root);
        Div.matchWidth(root);
        Div.gap(root, 8);
        return root;
    }
}
