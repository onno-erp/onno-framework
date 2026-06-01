package com.onec.ui.divkit;

import com.onec.metadata.DashboardWidgetDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lays out a dashboard's widgets and compiles each to DivKit. Data-light widgets
 * ({@code count}, {@code list}) render as native cards; rich, interactive widgets
 * ({@code chart}, {@code calendar}, {@code kanban}) compile to a {@code div-custom}
 * block of {@code custom_type "onec-widget"} whose {@code custom_props.widget}
 * carries the full descriptor — the web client mounts the matching React component
 * (recharts / FullCalendar / dnd board) in its place, while a non-web DivKit client
 * can register its own native renderer for the same {@code custom_type}.
 *
 * <p>Widgets flow into rows by their authored {@code width} fraction (e.g. four
 * {@code 1/4}s share a row, a {@code full} calendar takes its own); a single-column
 * (mobile) layout stacks everything full width.</p>
 */
final class Widgets {

    private Widgets() {}

    /** Widget types backed by a {@code div-custom} React component rather than a card. */
    static final Set<String> CUSTOM_TYPES = Set.of("chart", "calendar", "kanban");

    private static final int GAP = 12;

    /** Build the widget area: a vertical stack of width-aware rows. */
    static Map<String, Object> grid(List<DashboardWidgetDescriptor> widgets, int columns, Palette p) {
        List<Map<String, Object>> rows = new ArrayList<>();

        if (columns <= 1) {
            for (DashboardWidgetDescriptor w : widgets) {
                rows.add(matchWidthBlock(w, p));
            }
        } else {
            List<DashboardWidgetDescriptor> row = new ArrayList<>();
            double sum = 0;
            for (DashboardWidgetDescriptor w : widgets) {
                double f = fraction(w.width());
                if (!row.isEmpty() && sum + f > 1.0001) {
                    rows.add(row(row, p));
                    row = new ArrayList<>();
                    sum = 0;
                }
                row.add(w);
                sum += f;
                if (sum >= 0.999) {
                    rows.add(row(row, p));
                    row = new ArrayList<>();
                    sum = 0;
                }
            }
            if (!row.isEmpty()) {
                rows.add(row(row, p));
            }
        }

        Map<String, Object> stack = Div.vertical(rows);
        Div.matchWidth(stack);
        Div.gap(stack, GAP);
        return stack;
    }

    // A row of widgets sharing the main axis by their width fraction.
    private static Map<String, Object> row(List<DashboardWidgetDescriptor> widgets, Palette p) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (DashboardWidgetDescriptor w : widgets) {
            cells.add(Div.weight(block(w, p), fraction(w.width())));
        }
        Map<String, Object> row = Div.horizontal(cells);
        Div.matchWidth(row);
        Div.gap(row, GAP);
        return row;
    }

    private static Map<String, Object> matchWidthBlock(DashboardWidgetDescriptor w, Palette p) {
        return Div.matchWidth(block(w, p));
    }

    private static Map<String, Object> block(DashboardWidgetDescriptor w, Palette p) {
        return CUSTOM_TYPES.contains(w.widgetType()) ? custom(w) : card(w, p);
    }

    /** A {@code div-custom} block carrying the widget descriptor for the React renderer. */
    private static Map<String, Object> custom(DashboardWidgetDescriptor w) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", w.title());
        meta.put("widgetType", w.widgetType());
        meta.put("entityType", w.entityType());
        meta.put("entityName", w.entityName());
        meta.put("maxItems", w.maxItems());
        meta.put("dateField", w.dateField());
        meta.put("titleField", w.titleField());
        meta.put("extraConfig", w.extraConfig());
        Map<String, Object> node = Div.custom("onec-widget", Map.of("widget", meta));
        Div.matchWidth(node);
        return node;
    }

    // count / list: a native card placeholder (type label + title) until they grow
    // a data-bearing native rendering.
    private static Map<String, Object> card(DashboardWidgetDescriptor w, Palette p) {
        Map<String, Object> label = Div.color(Div.text(w.widgetType().toUpperCase(), 11, "medium"), p.faint());
        Map<String, Object> title = Div.color(Div.text(w.title(), 16, "medium"), p.text());
        Div.margins(title, 4, 0, 0, 0);
        return Components.card(List.of(label, title), p);
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
