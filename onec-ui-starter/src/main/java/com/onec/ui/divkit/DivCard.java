package com.onec.ui.divkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a root div in the DivKit document envelope
 * ({@code { templates, card: { log_id, variables?, states: [{ state_id, div }] } }})
 * that every official DivKit renderer (Web, iOS, Android, Flutter) consumes.
 */
public final class DivCard {

    private DivCard() {}

    public static Map<String, Object> of(String logId, Map<String, Object> root) {
        return of(logId, root, List.of());
    }

    public static Map<String, Object> of(String logId, Map<String, Object> root,
                                         List<Map<String, Object>> variables) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state_id", 0);
        state.put("div", root);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("log_id", logId);
        if (variables != null && !variables.isEmpty()) {
            card.put("variables", variables);
        }
        card.put("states", List.of(state));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("templates", Map.of());
        document.put("card", card);
        return document;
    }

    public static Map<String, Object> stringVar(String name, String value) {
        Map<String, Object> var = new LinkedHashMap<>();
        var.put("type", "string");
        var.put("name", name);
        var.put("value", value == null ? "" : value);
        return var;
    }
}
