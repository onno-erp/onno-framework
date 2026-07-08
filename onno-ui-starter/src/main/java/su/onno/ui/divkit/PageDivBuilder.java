package su.onno.ui.divkit;

import su.onno.metadata.PageWidgetDescriptor;
import su.onno.ui.PageComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders an authored {@link su.onno.ui.Page} to DivKit content: a header, then a recursive
 * {@link Region} tree — each region is a widget grid + freeform {@link PageComponent} blocks +
 * nested {@link Row}s of {@link Column}s, so a page can compose an arbitrary column layout (and a
 * right rail is just the common two-column case). Built from native DivKit primitives (custom blocks
 * become {@code div-custom}), so it renders on every official SDK.
 */
public final class PageDivBuilder {

    /** Fixed width (dp) of the right rail on desktop; the main column flexes to fill the rest. */
    private static final int ASIDE_WIDTH = 300;

    private PageDivBuilder() {}

    /** A page region: a widget grid ({@code gridColumns} wide), freeform components, then layout rows. */
    public record Region(List<PageWidgetDescriptor> widgets, int gridColumns,
                         List<PageComponent> components, List<Row> rows) {
        public Region {
            widgets = widgets == null ? List.of() : List.copyOf(widgets);
            components = components == null ? List.of() : List.copyOf(components);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /** A flat region with no nested rows (a widget grid + components). */
        public Region(List<PageWidgetDescriptor> widgets, int gridColumns, List<PageComponent> components) {
            this(widgets, gridColumns, components, List.of());
        }

        boolean isEmpty() {
            return widgets.isEmpty() && components.isEmpty() && rows.isEmpty();
        }
    }

    /** A horizontal band of columns (stacks vertically on mobile). */
    public record Row(List<Column> columns) {}

    /** One column of a {@link Row}: a width spec (fraction, {@code "<n>px"}, or null) + its region. */
    public record Column(String width, Region region) {}

    // ----- flat overloads (no explicit rows) -----

    public static Map<String, Object> build(String title, String subtitle,
                                            List<PageWidgetDescriptor> widgets,
                                            List<PageComponent> components, int columns,
                                            Function<PageWidgetDescriptor, String> values,
                                            Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        return build(title, subtitle, true, widgets, components, columns, values, canWrite, p);
    }

    public static Map<String, Object> build(String title, String subtitle, boolean header,
                                            List<PageWidgetDescriptor> widgets,
                                            List<PageComponent> components, int columns,
                                            Function<PageWidgetDescriptor, String> values,
                                            Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        return build(title, subtitle, header, widgets, components, List.of(), List.of(),
                columns, values, canWrite, p);
    }

    /**
     * Flat render with an optional right rail. {@code asideWidgets}/{@code asideComponents} compose a
     * narrow column beside the main content on desktop, stacked below it on mobile ({@code columns == 1}).
     */
    public static Map<String, Object> build(String title, String subtitle, boolean header,
                                            List<PageWidgetDescriptor> widgets,
                                            List<PageComponent> components,
                                            List<PageWidgetDescriptor> asideWidgets,
                                            List<PageComponent> asideComponents,
                                            int columns,
                                            Function<PageWidgetDescriptor, String> values,
                                            Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        Region main = new Region(widgets, columns, components);
        Region aside = new Region(asideWidgets, 1, asideComponents);
        return build(title, subtitle, header, main, aside.isEmpty() ? null : aside,
                columns > 1, values, canWrite, p);
    }

    // ----- region render (the general form) -----

    /**
     * Render a page from its region tree. {@code desktop} controls layout direction: rows lay their
     * columns out horizontally and grids use their column count; on mobile everything stacks. An
     * {@code aside} region, when present, renders as a fixed-width rail to the right of {@code main}.
     */
    public static Map<String, Object> build(String title, String subtitle, boolean header,
                                            Region main, Region aside, boolean desktop,
                                            Function<PageWidgetDescriptor, String> values,
                                            Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        // On desktop the shared time-range picker folds into the header's title row instead of taking
        // a widget row of its own; on mobile it keeps a full-width row. With no header, it stays a
        // normal grid widget. The fold only applies to the main region's own top-level widgets.
        PageWidgetDescriptor timeRange = header && desktop ? timeRangeWidget(main.widgets()) : null;
        Region mainBody = timeRange == null ? main
                : new Region(main.widgets().stream().filter(w -> w != timeRange).toList(),
                        main.gridColumns(), main.components(), main.rows());
        if (header) {
            items.add(Components.pageHeader(title, subtitle,
                    timeRange == null ? null : Widgets.custom(timeRange, true), p));
        }

        List<Map<String, Object>> mainItems = region(mainBody, desktop, values, canWrite, p);

        if (aside == null || aside.isEmpty()) {
            items.addAll(mainItems);
        } else if (!desktop) {
            // Mobile: no room for a rail, so it stacks beneath the main content.
            items.addAll(mainItems);
            items.addAll(region(aside, false, values, canWrite, p));
        } else {
            // Desktop: main content flexes, the rail takes a fixed width to its right.
            Map<String, Object> mainCol = Div.vertical(mainItems);
            Div.weight(mainCol, 1);
            Div.gap(mainCol, 8);
            Map<String, Object> asideCol = Div.vertical(region(aside, true, values, canWrite, p));
            Div.width(asideCol, ASIDE_WIDTH);
            Div.gap(asideCol, 8);
            Map<String, Object> row = Div.horizontal(List.of(mainCol, asideCol));
            Div.matchWidth(row);
            Div.gap(row, 12);
            items.add(row);
        }

        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onno-content");
        Div.contentPadding(root);
        Div.matchWidth(root);
        Div.gap(root, 8);
        return root;
    }

    /** The rendered blocks for one region: widget grid, then components, then nested rows. */
    private static List<Map<String, Object>> region(Region r, boolean desktop,
                                                    Function<PageWidgetDescriptor, String> values,
                                                    Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!r.widgets().isEmpty()) {
            out.add(Widgets.grid(r.widgets(), desktop ? Math.max(1, r.gridColumns()) : 1, values, canWrite, p));
        }
        for (PageComponent c : r.components()) {
            out.add(component(c, p));
        }
        for (Row row : r.rows()) {
            out.add(renderRow(row, desktop, values, canWrite, p));
        }
        return out;
    }

    /** A layout row: columns side by side on desktop, stacked on mobile. */
    private static Map<String, Object> renderRow(Row row, boolean desktop,
                                                 Function<PageWidgetDescriptor, String> values,
                                                 Function<PageWidgetDescriptor, Boolean> canWrite, Palette p) {
        if (!desktop) {
            List<Map<String, Object>> stacked = new ArrayList<>();
            for (Column c : row.columns()) {
                stacked.addAll(region(c.region(), false, values, canWrite, p));
            }
            Map<String, Object> col = Div.vertical(stacked);
            Div.matchWidth(col);
            Div.gap(col, 8);
            return col;
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        for (Column c : row.columns()) {
            Map<String, Object> cell = Div.vertical(region(c.region(), true, values, canWrite, p));
            Div.gap(cell, 8);
            applyWidth(cell, c.width());
            cells.add(cell);
        }
        Map<String, Object> band = Div.horizontal(cells);
        Div.matchWidth(band);
        Div.gap(band, 12);
        return band;
    }

    /** Size a column: {@code "<n>px"} → fixed dp; a fraction → flex weight (its numerator); else equal weight. */
    private static void applyWidth(Map<String, Object> node, String width) {
        if (width == null || width.isBlank() || "full".equalsIgnoreCase(width)) {
            Div.weight(node, 1);
            return;
        }
        String w = width.trim();
        if (w.endsWith("px")) {
            try {
                Div.width(node, Integer.parseInt(w.substring(0, w.length() - 2).trim()));
                return;
            } catch (NumberFormatException ignored) {
                // fall through to an equal share
            }
        }
        int slash = w.indexOf('/');
        if (slash > 0) {
            try {
                Div.weight(node, Math.max(1, Integer.parseInt(w.substring(0, slash).trim())));
                return;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        Div.weight(node, 1);
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
