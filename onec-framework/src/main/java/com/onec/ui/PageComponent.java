package com.onec.ui;

import java.util.Map;

/**
 * A freeform block a {@link Page} composes beyond the widget grid: a text block, a {@code
 * div-custom} extension, or a full interactive entity list. Renderer-agnostic; the DivKit emitter
 * compiles it (text → {@code div-text}, custom → {@code div-custom}, list → the {@code onec-list}
 * surface built from the entity's metadata).
 */
public record PageComponent(Kind kind, String text, String customType, Map<String, Object> payload,
                            Class<?> entity) {

    public enum Kind { TEXT, CUSTOM, LIST }

    public static PageComponent text(String text) {
        return new PageComponent(Kind.TEXT, text, null, Map.of(), null);
    }

    public static PageComponent custom(String customType, Map<String, Object> payload) {
        return new PageComponent(Kind.CUSTOM, null, customType,
                payload == null ? Map.of() : Map.copyOf(payload), null);
    }

    /** The full interactive list surface for a catalog/document, embedded in the page. */
    public static PageComponent list(Class<?> entity) {
        return new PageComponent(Kind.LIST, null, null, Map.of(), entity);
    }
}
