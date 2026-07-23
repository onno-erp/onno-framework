package su.onno.ui.divkit;

import su.onno.metadata.PageWidgetDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Lays out a dashboard's widgets and compiles each to DivKit. The {@code count} and
 * {@code metric} tiles render as native cards (a server-resolved big number);
 * everything else — the built-in {@code chart}/{@code calendar}/{@code kanban}/
 * {@code list} and any app-registered widget type — compiles to a {@code div-custom}
 * block of {@code custom_type "onno-widget"} whose {@code custom_props.widget} carries
 * the full descriptor. The web client mounts the matching React component (built-in or
 * one registered via {@code registerWidget(...)}) in its place; a non-web DivKit client
 * can register its own native renderer for the same {@code custom_type}.
 *
 * <p>Widgets flow into rows by their authored {@code width} fraction (e.g. four
 * {@code 1/4}s share a row, a {@code full} calendar takes its own); a single-column
 * (mobile) layout stacks everything full width.</p>
 */
final class Widgets {

    private Widgets() {}

    /** Widget types rendered as a native big-number card rather than a {@code div-custom} block. */
    static final Set<String> NATIVE_CARD_TYPES = Set.of("count", "metric");

    private static final int GAP = 12;

    /**
     * Build the widget area: a vertical stack of width-aware rows. {@code values}
     * resolves the preformatted big-number text for {@code count}/{@code metric} tiles.
     */
    static Map<String, Object> grid(List<PageWidgetDescriptor> widgets, int columns,
                                    Function<PageWidgetDescriptor, String> values,
                                    Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        List<Map<String, Object>> rows = new ArrayList<>();

        if (columns <= 1) {
            for (PageWidgetDescriptor w : widgets) {
                rows.add(Div.matchWidth(block(w, values, canWrite, p)));
            }
        } else {
            List<PageWidgetDescriptor> row = new ArrayList<>();
            double sum = 0;
            for (PageWidgetDescriptor w : widgets) {
                double f = fraction(w.width());
                // An authored rowBreak closes the open row unconditionally; otherwise a row closes
                // when the next widget wouldn't fit.
                if (!row.isEmpty() && (w.rowBreak() || sum + f > 1.0001)) {
                    rows.add(row(row, values, canWrite, p));
                    row = new ArrayList<>();
                    sum = 0;
                }
                row.add(w);
                sum += f;
                if (sum >= 0.999) {
                    rows.add(row(row, values, canWrite, p));
                    row = new ArrayList<>();
                    sum = 0;
                }
            }
            if (!row.isEmpty()) {
                rows.add(row(row, values, canWrite, p));
            }
        }

        Map<String, Object> stack = Div.vertical(rows);
        Div.matchWidth(stack);
        Div.gap(stack, GAP);
        return stack;
    }

    // A row of widgets sharing the main axis by their width fraction. Cells stretch to the row's
    // height (i.e. the tallest widget), so neighbouring cards bottom-align instead of ragging.
    private static Map<String, Object> row(List<PageWidgetDescriptor> widgets,
                                           Function<PageWidgetDescriptor, String> values,
                                           Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (PageWidgetDescriptor w : widgets) {
            Map<String, Object> cell = Div.weight(block(w, values, canWrite, p), fraction(w.width()));
            if (widgets.size() > 1) {
                Div.matchHeight(cell);
            }
            cells.add(cell);
        }
        Map<String, Object> row = Div.horizontal(cells);
        Div.matchWidth(row);
        Div.gap(row, GAP);
        return row;
    }

    private static Map<String, Object> block(PageWidgetDescriptor w,
                                             Function<PageWidgetDescriptor, String> values,
                                             Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        // count/metric tiles are native cards; every other type — built-in or
        // app-registered — flows through the open onno-widget custom block.
        if (w.widgetType() == null || NATIVE_CARD_TYPES.contains(w.widgetType())) {
            return valueCard(w, values.apply(w), p);
        }
        return custom(w, !Boolean.FALSE.equals(canWrite.apply(w)));
    }

    /** A {@code div-custom} block carrying the widget descriptor for the React renderer. */
    static Map<String, Object> custom(PageWidgetDescriptor w, boolean canWrite) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", w.title());
        meta.put("widgetType", w.widgetType());
        meta.put("entityType", w.entityType());
        meta.put("entityName", w.entityName());
        meta.put("maxItems", w.maxItems());
        meta.put("dateField", w.dateField());
        meta.put("titleField", w.titleField());
        meta.put("extraConfig", w.extraConfig());
        meta.put("hint", w.hint() == null ? "" : w.hint());
        // The caller's write access on the widget's entity — an interactive widget (kanban drag,
        // calendar reschedule) disables its mutations for read-only viewers. REST enforces anyway.
        meta.put("canWrite", canWrite);
        Map<String, Object> node = Div.custom("onno-widget", Map.of("widget", meta));
        Div.matchWidth(node);
        return node;
    }

    // A KPI card: a big preformatted value (a record count, or a server-aggregated
    // sum/avg/…) above its title, clickable through to the entity's list surface.
    private static Map<String, Object> valueCard(PageWidgetDescriptor w, String value, Palette p) {
        Map<String, Object> number = Div.color(Div.text(value, 24, "medium"), p.text());
        Map<String, Object> titleText = Div.color(Div.text(w.title(), 13, "regular"), p.muted());
        Map<String, Object> hintGlyph = Components.hint(w.hint(), p.muted(), 14);
        Map<String, Object> title = titleText;
        if (hintGlyph != null) {
            title = Div.horizontal(List.of(titleText, hintGlyph));
            Div.wrapWidth(title);
            Div.gap(title, 5);
            Div.alignV(title, "center");
        }
        Div.margins(title, 4, 0, 0, 0);
        Map<String, Object> card = Components.card(List.of(number, title), p);
        String href = hrefFor(w);
        if (href != null) {
            // Matches the nav's "onno:/" + "/entity..." convention → "onno://entity...".
            Div.action(card, "open", "onno:/" + href);
        }
        return card;
    }

    // The list-surface route for a widget's entity, matching UiLayoutResolver's hrefs.
    private static String hrefFor(PageWidgetDescriptor w) {
        if (w.entityType() == null || w.entityName() == null) {
            return null;
        }
        return "/" + w.entityType() + "s/" + toSnake(w.entityName());
    }

    private static String toSnake(String name) {
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

    // Authored width tokens ("1/4", "1/2", "2/3", "full", ...) → a fraction of the row.
    private static double fraction(String width) {
        if (width == null || width.isBlank() || width.equalsIgnoreCase("full")) {
            return 1.0;
        }
        int slash = width.indexOf('/');
        if (slash > 0) {
            try {
                double num = Double.parseDouble(width.substring(0, slash).trim());
                double den = Double.parseDouble(width.substring(slash + 1).trim());
                if (den != 0) {
                    return num / den;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 1.0 / 3.0;
    }
}
