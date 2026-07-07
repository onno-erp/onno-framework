package su.onno.ui.divkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared, theme-aware DivKit building blocks (icons, hints, page headers, cards). */
final class Components {

    private Components() {}

    /**
     * A glyph as an {@code onno-icon} custom block carrying {@code name/color/size}; the
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
        Map<String, Object> node = Div.custom("onno-icon", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
    }

    /**
     * A help "?" glyph as an {@code onno-hint} custom block carrying {@code text/color/size}; the
     * client renders a hoverable/focusable icon that reveals {@code text} in a tooltip. Returns
     * {@code null} for blank help text so callers degrade to label-only.
     */
    static Map<String, Object> hint(String text, String color, int size) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("text", text);
        props.put("color", color);
        props.put("size", size);
        Map<String, Object> node = Div.custom("onno-hint", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
    }

    static Map<String, Object> pageHeader(String title, String subtitle, Palette p) {
        return pageHeader(title, subtitle, null, p);
    }

    /**
     * The page header card. {@code trailing} (optional) is a block laid out on the title row's
     * right edge — the dashboard's shared time-range picker rides there on desktop so the picker
     * belongs to the header instead of floating in its own widget row.
     */
    static Map<String, Object> pageHeader(String title, String subtitle, Map<String, Object> trailing, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Div.color(Div.text(title, 22, "bold"), p.text()));
        if (subtitle != null && !subtitle.isBlank()) {
            Map<String, Object> sub = Div.color(Div.text(subtitle, 13, "regular"), p.muted());
            Div.margins(sub, 2, 0, 0, 0);
            items.add(sub);
        }
        Map<String, Object> titles = Div.vertical(items);
        Map<String, Object> header;
        if (trailing == null) {
            header = titles;
        } else {
            // Titles and the control split the row; the React widget right-aligns itself
            // within its half, so the control hugs the header's right edge.
            Div.weight(titles, 1);
            Div.weight(trailing, 1);
            header = Div.horizontal(List.of(titles, trailing));
            Div.alignV(header, "center");
            Div.gap(header, 12);
        }
        Div.matchWidth(header);
        Div.background(header, p.surface());
        Div.pad(header, 12, 16);
        Div.corner(header, Radii.CARD);
        Div.stroke(header, p.border(), 1);
        // 4 + the root's 8dp item spacing = 12dp under the header — the same gutter the widget
        // grid uses between rows, so the header doesn't sit visibly farther from the content.
        Div.margins(header, 0, 0, 4, 0);
        return header;
    }

    static Map<String, Object> card(List<Map<String, Object>> items, Palette p) {
        Map<String, Object> card = Div.vertical(items);
        Div.matchWidth(card);
        Div.background(card, p.surface());
        Div.pad(card, 16, 16);
        Div.corner(card, Radii.CARD);
        Div.stroke(card, p.border(), 1);
        Div.gap(card, 8);
        return card;
    }

}
