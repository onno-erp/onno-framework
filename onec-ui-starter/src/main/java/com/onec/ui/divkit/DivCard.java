package com.onec.ui.divkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a root div in the DivKit document envelope
 * ({@code { templates, card: { log_id, states: [{ state_id, div }] } }})
 * that every official DivKit renderer (Web, iOS, Android, Flutter) consumes.
 * Runtime variables aren't declared on the card — they're seeded client-side from
 * the {@code vars} map (see {@link #ofVars}) into a global controller so they can
 * be streamed via {@code setValue}.
 */
public final class DivCard {

    private DivCard() {}

    public static Map<String, Object> of(String logId, Map<String, Object> root) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state_id", 0);
        state.put("div", root);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("log_id", logId);
        card.put("states", List.of(state));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("templates", Map.of());
        document.put("card", card);
        return document;
    }

    /**
     * Full card plus a {@code vars} seed map the client loads into its DivKit
     * variables controller, so {@code @{name}}-bound text (e.g. the list count)
     * renders on first paint and can later be streamed via {@code setValue}.
     */
    public static Map<String, Object> ofVars(String logId, Map<String, Object> root, Map<String, Object> vars) {
        Map<String, Object> document = of(logId, root);
        if (vars != null && !vars.isEmpty()) {
            document.put("vars", vars);
        }
        return document;
    }

    /**
     * A delta payload for a live update: a {@code div-patch} ({@code changes}, each
     * replacing the children of a node by {@code id}) plus optional streamed
     * {@code vars}. The client calls {@code applyPatch} + variable {@code setValue}.
     */
    public static Map<String, Object> delta(List<Map<String, Object>> changes, Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changes", changes == null ? List.of() : changes);
        if (vars != null && !vars.isEmpty()) {
            out.put("vars", vars);
        }
        return out;
    }

    /** One patch change: replace the children ({@code items}) of the node with {@code id}. */
    public static Map<String, Object> change(String id, List<Map<String, Object>> items) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("items", items);
        return c;
    }
}
