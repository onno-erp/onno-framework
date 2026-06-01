package com.onec.ui.divkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factories for real DivKit div nodes, emitted as plain maps so Jackson
 * serializes them to the DivKit JSON schema ({@code "type": "container" | "text"
 * | "separator" | ...}). Kept deliberately thin; richer nodes are added as the
 * server-side emitters grow.
 */
public final class Div {

    private Div() {}

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

    public static Map<String, Object> separator() {
        return node("separator");
    }

    /** Custom extension node — clients render it via the registered {@code custom_type}. */
    public static Map<String, Object> custom(String customType, Map<String, Object> payload) {
        Map<String, Object> node = node("custom");
        node.put("custom_type", customType);
        if (payload != null) {
            node.putAll(payload);
        }
        return node;
    }

    public static Map<String, Object> withAction(Map<String, Object> node, String logId, String url) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("log_id", logId);
        action.put("url", url);
        node.put("action", action);
        return node;
    }

    public static Map<String, Object> withTextColor(Map<String, Object> node, String color) {
        node.put("text_color", color);
        return node;
    }

    /** Make a node share horizontal space with siblings (DivKit match_parent + weight). */
    public static Map<String, Object> weight(Map<String, Object> node, double weight) {
        Map<String, Object> width = new LinkedHashMap<>();
        width.put("type", "match_parent");
        width.put("weight", weight);
        node.put("width", width);
        return node;
    }

    private static Map<String, Object> node(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        return node;
    }
}
