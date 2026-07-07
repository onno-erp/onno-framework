package su.onno.ui.divkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factories + fluent styling helpers for real DivKit div nodes, emitted as plain
 * maps so Jackson serializes them to the DivKit JSON schema ({@code "type":
 * "container" | "text" | "gallery" | "image" | "separator" | ...}). Every styling
 * helper mutates and returns the node so calls chain.
 */
public final class Div {

    private Div() {}

    // ----- node factories -----

    public static Map<String, Object> text(String text) {
        Map<String, Object> node = node("text");
        node.put("text", text == null ? "" : text);
        return node;
    }

    public static Map<String, Object> text(String text, int fontSize, String fontWeight) {
        Map<String, Object> node = text(text);
        node.put("font_size", fontSize);
        if (fontWeight != null) {
            node.put("font_weight", fontWeight);
        }
        return node;
    }

    public static Map<String, Object> maxLines(Map<String, Object> node, int lines) {
        node.put("max_lines", lines);
        return node;
    }

    public static Map<String, Object> container(String orientation, List<Map<String, Object>> items) {
        Map<String, Object> node = node("container");
        node.put("orientation", orientation);
        node.put("items", items);
        return node;
    }

    public static Map<String, Object> vertical(List<Map<String, Object>> items) {
        return container("vertical", items);
    }

    public static Map<String, Object> horizontal(List<Map<String, Object>> items) {
        return container("horizontal", items);
    }

    /** Scrollable list along its orientation. */
    public static Map<String, Object> gallery(String orientation, List<Map<String, Object>> items) {
        Map<String, Object> node = node("gallery");
        node.put("orientation", orientation);
        node.put("items", items);
        return node;
    }

    /**
     * Native DivKit tabs. {@code items} are {@link #tab} entries ({@code {title, div}}).
     * Renders as a tab strip over the selected tab's content on every official SDK.
     */
    public static Map<String, Object> tabs(List<Map<String, Object>> items) {
        Map<String, Object> node = node("tabs");
        node.put("items", items);
        return node;
    }

    /** One {@link #tabs} entry: a title and the content shown when it's selected. */
    public static Map<String, Object> tab(String title, Map<String, Object> content) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("title", title);
        t.put("div", content);
        return t;
    }

    public static Map<String, Object> grid(int columnCount, List<Map<String, Object>> items) {
        Map<String, Object> node = node("grid");
        node.put("column_count", columnCount);
        node.put("items", items);
        return node;
    }

    public static Map<String, Object> image(String url) {
        Map<String, Object> node = node("image");
        node.put("image_url", url);
        return node;
    }

    public static Map<String, Object> separator() {
        Map<String, Object> node = node("separator");
        return node;
    }

    public static Map<String, Object> separator(String color) {
        Map<String, Object> node = separator();
        Map<String, Object> delimiter = new LinkedHashMap<>();
        delimiter.put("color", color);
        node.put("delimiter_style", delimiter);
        return node;
    }

    /** Text input bound to {@code textVariable}; the field's value lives in that variable. */
    public static Map<String, Object> input(String textVariable, String keyboardType) {
        Map<String, Object> node = node("input");
        node.put("text_variable", textVariable);
        if (keyboardType != null) {
            node.put("keyboard_type", keyboardType);
        }
        return node;
    }

    /** Dropdown: {@code options} of {@code {value,text}}, the chosen value in {@code valueVariable}. */
    public static Map<String, Object> select(String valueVariable, List<Map<String, Object>> options) {
        Map<String, Object> node = node("select");
        node.put("value_variable", valueVariable);
        node.put("options", options);
        return node;
    }

    /** One {@link #select} option. */
    public static Map<String, Object> option(String value, String text) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("text", text == null || text.isBlank() ? value : text);
        return o;
    }

    /**
     * Custom extension node — clients render it via the registered {@code custom_type}.
     * {@code props} are passed through DivKit's {@code custom_props}, reaching the
     * web client's custom element as its element properties.
     */
    public static Map<String, Object> custom(String customType, Map<String, Object> props) {
        Map<String, Object> node = node("custom");
        node.put("custom_type", customType);
        if (props != null && !props.isEmpty()) {
            node.put("custom_props", props);
        }
        return node;
    }

    // ----- styling helpers (mutate + return) -----

    /** Stable node id — the anchor a {@code div-patch} ({@code applyPatch}) targets. */
    public static Map<String, Object> id(Map<String, Object> node, String id) {
        node.put("id", id);
        return node;
    }

    /**
     * Attach a client extension to a node. The web client looks up {@code id} in the
     * registered extensions map and calls its {@code mountView} with the rendered
     * element + {@code params} — used to stamp DOM hooks (e.g. a row's action url for
     * right-click menus and hover) that DivKit JSON can't express.
     */
    public static Map<String, Object> extension(Map<String, Object> node, String id, Map<String, Object> params) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("id", id);
        if (params != null && !params.isEmpty()) {
            ext.put("params", params);
        }
        node.put("extensions", List.of(ext));
        return node;
    }

    public static Map<String, Object> action(Map<String, Object> node, String logId, String url) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("log_id", logId);
        action.put("url", url);
        node.put("action", action);
        return node;
    }

    public static Map<String, Object> color(Map<String, Object> node, String color) {
        node.put("text_color", color);
        return node;
    }

    public static Map<String, Object> background(Map<String, Object> node, String color) {
        Map<String, Object> solid = new LinkedHashMap<>();
        solid.put("type", "solid");
        solid.put("color", color);
        node.put("background", List.of(solid));
        return node;
    }

    public static Map<String, Object> corner(Map<String, Object> node, int radius) {
        border(node).put("corner_radius", radius);
        return node;
    }

    public static Map<String, Object> stroke(Map<String, Object> node, String color, int width) {
        Map<String, Object> stroke = new LinkedHashMap<>();
        stroke.put("color", color);
        stroke.put("width", width);
        border(node).put("stroke", stroke);
        return node;
    }

    public static Map<String, Object> pad(Map<String, Object> node, int v, int h) {
        return pad(node, v, h, v, h);
    }

    /**
     * Outer breathing room for a content-surface root. The web shell used to add this
     * padding in React around the DivKit content; owning it in the document keeps it
     * self-contained and consistent across every renderer and device. Single source of
     * truth, so it can later vary by viewport in one place.
     */
    public static Map<String, Object> contentPadding(Map<String, Object> node) {
        return pad(node, 16, 16);
    }

    public static Map<String, Object> pad(Map<String, Object> node, int top, int right, int bottom, int left) {
        node.put("paddings", edges(top, right, bottom, left));
        return node;
    }

    public static Map<String, Object> margins(Map<String, Object> node, int top, int right, int bottom, int left) {
        node.put("margins", edges(top, right, bottom, left));
        return node;
    }

    public static Map<String, Object> gap(Map<String, Object> node, int dp) {
        node.put("item_spacing", dp);
        return node;
    }

    public static Map<String, Object> width(Map<String, Object> node, int dp) {
        node.put("width", fixed(dp));
        return node;
    }

    public static Map<String, Object> height(Map<String, Object> node, int dp) {
        node.put("height", fixed(dp));
        return node;
    }

    public static Map<String, Object> matchWidth(Map<String, Object> node) {
        node.put("width", Map.of("type", "match_parent"));
        return node;
    }

    /** Size to content (not stretched to the parent) so children keep natural width. */
    public static Map<String, Object> wrapWidth(Map<String, Object> node) {
        node.put("width", Map.of("type", "wrap_content"));
        return node;
    }

    public static Map<String, Object> matchHeight(Map<String, Object> node) {
        node.put("height", Map.of("type", "match_parent"));
        return node;
    }

    /** Share main-axis space with siblings (match_parent + weight). */
    public static Map<String, Object> weight(Map<String, Object> node, double weight) {
        Map<String, Object> width = new LinkedHashMap<>();
        width.put("type", "match_parent");
        width.put("weight", weight);
        node.put("width", width);
        return node;
    }

    public static Map<String, Object> weightHeight(Map<String, Object> node, double weight) {
        Map<String, Object> height = new LinkedHashMap<>();
        height.put("type", "match_parent");
        height.put("weight", weight);
        node.put("height", height);
        return node;
    }

    public static Map<String, Object> alignH(Map<String, Object> node, String alignment) {
        node.put("content_alignment_horizontal", alignment);
        return node;
    }

    public static Map<String, Object> alignV(Map<String, Object> node, String alignment) {
        node.put("content_alignment_vertical", alignment);
        return node;
    }

    public static Map<String, Object> textAlign(Map<String, Object> node, String alignment) {
        node.put("text_alignment_horizontal", alignment);
        return node;
    }

    /** Tracking for small-caps labels (dp, fractional allowed). */
    public static Map<String, Object> letterSpacing(Map<String, Object> node, double dp) {
        node.put("letter_spacing", dp);
        return node;
    }

    // ----- internals -----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> border(Map<String, Object> node) {
        return (Map<String, Object>) node.computeIfAbsent("border", k -> new LinkedHashMap<String, Object>());
    }

    private static Map<String, Object> edges(int top, int right, int bottom, int left) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("top", top);
        e.put("right", right);
        e.put("bottom", bottom);
        e.put("left", left);
        return e;
    }

    private static Map<String, Object> fixed(int dp) {
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("type", "fixed");
        size.put("value", dp);
        return size;
    }

    private static Map<String, Object> node(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        return node;
    }
}
