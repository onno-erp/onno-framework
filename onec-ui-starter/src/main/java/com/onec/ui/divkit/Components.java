package com.onec.ui.divkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared, theme-aware DivKit building blocks (cards, headers, badges, tables). */
final class Components {

    private Components() {}

    static Map<String, Object> pageHeader(String title, String subtitle, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Div.color(Div.text(title, 22, "bold"), p.text()));
        if (subtitle != null && !subtitle.isBlank()) {
            Map<String, Object> sub = Div.color(Div.text(subtitle, 13, "regular"), p.muted());
            Div.margins(sub, 2, 0, 0, 0);
            items.add(sub);
        }
        Map<String, Object> header = Div.vertical(items);
        Div.margins(header, 0, 0, 16, 0);
        return header;
    }

    static Map<String, Object> card(List<Map<String, Object>> items, Palette p) {
        Map<String, Object> card = Div.vertical(items);
        Div.matchWidth(card);
        Div.background(card, p.surface());
        Div.pad(card, 16, 16);
        Div.corner(card, 12);
        Div.stroke(card, p.border(), 1);
        Div.gap(card, 8);
        return card;
    }

    static Map<String, Object> badge(String text, String fg, String bg) {
        Map<String, Object> badge = Div.text(text, 12, "medium");
        Div.color(badge, fg);
        Div.background(badge, bg);
        Div.pad(badge, 3, 9);
        Div.corner(badge, 999);
        return badge;
    }

    static Map<String, Object> statusBadge(boolean positive, String text, Palette p) {
        return positive
                ? badge(text, p.success(), p.successSoft())
                : badge(text, p.muted(), p.rowAlt());
    }

    // Each column is a fixed width with single-line cells, so a wide table keeps its
    // intrinsic width and scrolls horizontally inside {@link #scrollX} rather than
    // squeezing columns until the text wraps.
    private static final int COL_WIDTH = 150;
    private static final int CELL_GAP = 16;

    /** A bordered, horizontally-scrolling card: a header row + data rows. */
    static Map<String, Object> table(List<String> headers, List<Row> rows, Palette p) {
        return scrollX(tableStack(tableItems(headers, rows, p)), p);
    }

    /**
     * The table's children (header row + separator + data rows) on their own — the
     * payload a {@code div-patch} replaces when only the rows change. Wrap with
     * {@link #tableStack} + {@link #scrollX} for a full render.
     */
    static List<Map<String, Object>> tableItems(List<String> headers, List<Row> rows, Palette p) {
        List<Map<String, Object>> stack = new ArrayList<>();

        List<Map<String, Object>> headerCells = new ArrayList<>();
        for (String h : headers) {
            headerCells.add(cell(Div.color(Div.text(h, 12, "medium"), p.faint())));
        }
        Map<String, Object> headerRow = Div.horizontal(headerCells);
        Div.wrapWidth(headerRow);
        Div.gap(headerRow, CELL_GAP);
        Div.pad(headerRow, 10, 14);
        stack.add(headerRow);
        stack.add(Div.separator(p.border()));

        if (rows.isEmpty()) {
            Map<String, Object> empty = Div.color(Div.text("No records", 13, "regular"), p.faint());
            Div.pad(empty, 16, 14);
            stack.add(empty);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            List<Map<String, Object>> cells = new ArrayList<>();
            for (String value : row.cells()) {
                cells.add(cell(Div.color(Div.text(value, 14, "regular"), p.text())));
            }
            Map<String, Object> rowNode = Div.horizontal(cells);
            Div.wrapWidth(rowNode);
            Div.gap(rowNode, CELL_GAP);
            Div.pad(rowNode, 11, 14);
            if (i % 2 == 1) {
                Div.background(rowNode, p.rowAlt());
            }
            if (row.actionUrl() != null) {
                Div.action(rowNode, "open", row.actionUrl());
            }
            stack.add(rowNode);
        }
        return stack;
    }

    /** A fixed-width, single-line table cell — columns stay aligned and never wrap. */
    private static Map<String, Object> cell(Map<String, Object> textNode) {
        return Div.maxLines(Div.width(textNode, COL_WIDTH), 1);
    }

    /** The header+rows as a width-to-content vertical stack — the {@code div-patch} target. */
    static Map<String, Object> tableStack(List<Map<String, Object>> items) {
        Map<String, Object> stack = Div.vertical(items);
        Div.wrapWidth(stack);
        return stack;
    }

    /** Wrap a (potentially wide) node in a bordered surface card that scrolls sideways. */
    static Map<String, Object> scrollX(Map<String, Object> inner, Palette p) {
        Map<String, Object> g = Div.gallery("horizontal", List.of(inner));
        Div.matchWidth(g);
        Div.background(g, p.surface());
        Div.corner(g, 12);
        Div.stroke(g, p.border(), 1);
        return g;
    }

    static Map<String, Object> fieldRow(String label, String value, Palette p) {
        Map<String, Object> row = Div.horizontal(List.of(
                Div.weight(Div.color(Div.text(label, 13, "regular"), p.muted()), 2),
                Div.weight(Div.color(Div.text(value, 14, "regular"), p.text()), 3)));
        Div.pad(row, 7, 0);
        return row;
    }

    record Row(List<String> cells, String actionUrl) {}
}
