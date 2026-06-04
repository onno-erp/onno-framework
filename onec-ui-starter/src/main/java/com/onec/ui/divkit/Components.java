package com.onec.ui.divkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared, theme-aware DivKit building blocks (cards, headers, badges, tables). */
final class Components {

    private Components() {}

    private static final String TRANSPARENT = "#00000000";

    /**
     * A glyph as an {@code onec-icon} custom block carrying {@code name/color/size}; the
     * client renders the matching lucide icon by name (any kebab-case lucide name works,
     * and an unknown name degrades to a fallback glyph rather than rendering blank).
     * Returns {@code null} for a blank name so callers degrade to label-only.
     */
    static Map<String, Object> icon(String name, String color, int size) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("name", name);
        props.put("color", color);
        props.put("size", size);
        Map<String, Object> node = Div.custom("onec-icon", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
    }

    /**
     * A compact action button: optional leading icon + label over an {@code onec://}
     * action. Sized small-but-tall (roomy vertical padding, tight horizontal).
     * {@code bg}/{@code border} may be null for a ghost look; {@code url} null for static.
     */
    static Map<String, Object> actionButton(String iconName, String label, String fg, String bg, String border,
                                            String url, String logId) {
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> glyph = icon(iconName, fg, 15);
        if (glyph != null) {
            parts.add(glyph);
        }
        Map<String, Object> text = Div.text(label, 13, "medium");
        Div.color(text, fg);
        Div.maxLines(text, 1);
        parts.add(text);

        Map<String, Object> btn = Div.horizontal(parts);
        // Containers default to match_parent width in DivKit, which would stretch the
        // button across its row — size it to its content instead.
        Div.wrapWidth(btn);
        Div.gap(btn, 6);
        Div.alignV(btn, "center");
        Div.pad(btn, 9, 13);
        Div.corner(btn, 9);
        if (bg != null) {
            Div.background(btn, bg);
        }
        if (border != null) {
            Div.stroke(btn, border, 1);
        }
        if (url != null) {
            Div.action(btn, logId, url);
        }
        return btn;
    }

    /**
     * Native DivKit tabs styled to match the shell: a subtle pill on the active tab,
     * muted inactive labels, a hairline under the strip.
     */
    static Map<String, Object> tabs(List<Map<String, Object>> items, Palette p) {
        Map<String, Object> node = Div.tabs(items);
        Map<String, Object> titleStyle = new java.util.LinkedHashMap<>();
        titleStyle.put("font_size", 14);
        titleStyle.put("font_weight", "medium");
        titleStyle.put("active_text_color", p.text());
        titleStyle.put("inactive_text_color", p.muted());
        titleStyle.put("active_background_color", p.primarySoft());
        titleStyle.put("inactive_background_color", TRANSPARENT);
        titleStyle.put("corner_radius", 8);
        titleStyle.put("paddings", Map.of("top", 7, "bottom", 7, "left", 14, "right", 14));
        // Switch instantly: the active pill jumps to the selected tab instead of
        // sliding (DivKit's tab_title_style defaults to animation_type "slide"/300ms).
        titleStyle.put("animation_type", "none");
        titleStyle.put("animation_duration", 0);
        node.put("tab_title_style", titleStyle);
        // No hairline under the strip, and no inset on the title row so the leftmost
        // tab's pill edge lines up with the content table below it.
        node.put("has_separator", false);
        node.put("title_paddings", Map.of("top", 0, "bottom", 0, "left", 0, "right", 0));
        // Switch instantly: disabling the content-swipe scroller drops DivKit's
        // horizontal slide between tab bodies so the content just swaps.
        node.put("switch_tabs_by_content_swipe_enabled", false);
        Div.matchWidth(node);
        return node;
    }

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

    /**
     * A status chip: a colored status dot + label on a neutral pill. The dot carries the
     * state color (success / muted) so the chip stays quiet instead of a loud solid block.
     */
    static Map<String, Object> statusBadge(boolean positive, String text, Palette p) {
        Map<String, Object> dot = Div.container("vertical", List.of());
        Div.background(dot, positive ? p.success() : p.faint());
        Div.width(dot, 6);
        Div.height(dot, 6);
        Div.corner(dot, 999);

        Map<String, Object> label = Div.text(text, 12, "medium");
        Div.color(label, p.muted());
        Div.maxLines(label, 1);

        Map<String, Object> chip = Div.horizontal(List.of(dot, label));
        // Hug the dot + label (containers default to match_parent, which stretches the
        // pill and leaves the content adrift on the left).
        Div.wrapWidth(chip);
        Div.gap(chip, 6);
        Div.alignV(chip, "center");
        Div.pad(chip, 5, 10);
        Div.corner(chip, 999);
        Div.background(chip, p.rowAlt());
        Div.stroke(chip, p.border(), 1);
        return chip;
    }

    // Columns are sized to fit their widest cell (auto-fit), clamped to a min/max so a
    // short column doesn't collapse and one very long value doesn't blow the table out —
    // past the max it ellipsizes and the whole table scrolls sideways inside {@link
    // #scrollX}. An explicit per-column width hint (from .field(...).width("260"))
    // overrides the auto-fit. Cells stay single-line so a column's width holds across the
    // header and every row, keeping them aligned.
    private static final int COL_MIN = 64;
    private static final int COL_MAX = 460;
    private static final int CHAR_PX = 8;   // ~px per glyph at the 14px row font
    private static final int CELL_PAD = 16; // slack so the widest value isn't flush-clipped
    private static final int CELL_GAP = 16;

    /** A bordered, horizontally-scrolling card: a header row + data rows. */
    static Map<String, Object> table(List<String> headers, List<Row> rows, Palette p) {
        return scrollX(tableStack(tableItems(headers, rows, p)), p);
    }

    /** Auto-fit every column to its content (no authored width overrides). */
    static List<Map<String, Object>> tableItems(List<String> headers, List<Row> rows, Palette p) {
        return tableItems(headers, rows, null, p);
    }

    /**
     * The table's children (header row + separator + data rows) on their own — the
     * payload a {@code div-patch} replaces when only the rows change. Wrap with
     * {@link #tableStack} + {@link #scrollX} for a full render.
     *
     * <p>{@code widthHints} is an optional per-column list of authored widths (e.g.
     * {@code "260"}); a null/blank/unparseable entry (or a null list) auto-fits that
     * column to its widest cell instead.</p>
     */
    static List<Map<String, Object>> tableItems(List<String> headers, List<Row> rows,
                                                List<String> widthHints, Palette p) {
        List<Integer> widths = columnWidths(headers, rows, widthHints);
        List<Map<String, Object>> stack = new ArrayList<>();

        List<Map<String, Object>> headerCells = new ArrayList<>();
        for (int c = 0; c < headers.size(); c++) {
            headerCells.add(cell(Div.color(Div.text(headers.get(c), 12, "medium"), p.faint()), widths.get(c)));
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
            List<String> values = row.cells();
            for (int c = 0; c < values.size(); c++) {
                cells.add(cell(Div.color(Div.text(values.get(c), 14, "regular"), p.text()), widths.get(c)));
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
                // Stamp the row's action url on the DOM (see frontend "row" extension) so
                // the client can offer a right-click Open/Edit menu and a hover highlight.
                Div.extension(rowNode, "row", Map.of("url", row.actionUrl()));
            }
            stack.add(rowNode);
        }
        return stack;
    }

    /**
     * The effective pixel width of each column: an authored hint when present and
     * parseable, otherwise the widest cell (header + every row) estimated from its
     * character count and clamped to {@code [COL_MIN, COL_MAX]}.
     */
    private static List<Integer> columnWidths(List<String> headers, List<Row> rows, List<String> hints) {
        List<Integer> widths = new ArrayList<>(headers.size());
        for (int c = 0; c < headers.size(); c++) {
            int authored = parseWidth(hints != null && c < hints.size() ? hints.get(c) : null);
            if (authored > 0) {
                widths.add(authored);
                continue;
            }
            int maxChars = headers.get(c) == null ? 0 : headers.get(c).length();
            for (Row row : rows) {
                List<String> cells = row.cells();
                if (c < cells.size() && cells.get(c) != null) {
                    maxChars = Math.max(maxChars, cells.get(c).length());
                }
            }
            widths.add(Math.max(COL_MIN, Math.min(COL_MAX, maxChars * CHAR_PX + CELL_PAD)));
        }
        return widths;
    }

    /** Leading-integer pixel width of a hint ({@code "260"}, {@code "260px"}); -1 if none. */
    private static int parseWidth(String hint) {
        if (hint == null) {
            return -1;
        }
        int end = 0;
        while (end < hint.length() && Character.isDigit(hint.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Integer.parseInt(hint.substring(0, end));
    }

    /** A fixed-width, single-line table cell — columns stay aligned and never wrap. */
    private static Map<String, Object> cell(Map<String, Object> textNode, int width) {
        return Div.maxLines(Div.width(textNode, width), 1);
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

    /**
     * A detail field row whose value is an image (an image-widget attribute): the label on the
     * left, a bordered thumbnail on the right. {@code url} is a {@code data:} URL from the image
     * picker or a plain {@code http(s)} URL. An {@code avatar} renders smaller and circular.
     */
    static Map<String, Object> imageFieldRow(String label, String url, boolean avatar, Palette p) {
        int size = avatar ? 64 : 140;
        Map<String, Object> img = Div.image(url);
        Div.width(img, size);
        Div.height(img, size);
        Div.corner(img, avatar ? 999 : 10);
        Div.stroke(img, p.border(), 1);
        // Cover the box (cropping) for avatars; fit the whole image otherwise.
        img.put("scale", avatar ? "fill" : "fit");

        Map<String, Object> right = Div.horizontal(List.of(img));
        Div.weight(right, 3);

        Map<String, Object> row = Div.horizontal(List.of(
                Div.weight(Div.color(Div.text(label, 13, "regular"), p.muted()), 2),
                right));
        Div.alignV(row, "center");
        Div.pad(row, 7, 0);
        return row;
    }

    /**
     * A detail field row for a multi-image (gallery) attribute: the label on the left, a
     * horizontally-scrolling strip of square thumbnails on the right. Each {@code url} is a
     * {@code data:} or {@code http(s)} URL (see GalleryPicker).
     */
    static Map<String, Object> imageGalleryRow(String label, List<String> urls, Palette p) {
        List<Map<String, Object>> thumbs = new ArrayList<>();
        for (String url : urls) {
            Map<String, Object> img = Div.image(url);
            Div.width(img, 72);
            Div.height(img, 72);
            Div.corner(img, 8);
            Div.stroke(img, p.border(), 1);
            img.put("scale", "fill");
            thumbs.add(img);
        }
        // Many thumbnails would overflow the card — a gallery scrolls them sideways.
        Map<String, Object> right = Div.gallery("horizontal", thumbs);
        right.put("item_spacing", 8);
        Div.weight(right, 3);

        Map<String, Object> row = Div.horizontal(List.of(
                Div.weight(Div.color(Div.text(label, 13, "regular"), p.muted()), 2),
                right));
        Div.alignV(row, "center");
        Div.pad(row, 7, 0);
        return row;
    }

    record Row(List<String> cells, String actionUrl) {}
}
