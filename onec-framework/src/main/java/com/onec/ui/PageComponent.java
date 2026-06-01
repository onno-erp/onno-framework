package com.onec.ui;

import java.util.Map;

/**
 * A freeform block a {@link Page} composes beyond the widget grid: a text block
 * or a {@code div-custom} extension. Renderer-agnostic; the DivKit emitter
 * compiles it (text → {@code div-text}, custom → {@code div-custom}).
 */
public record PageComponent(Kind kind, String text, String customType, Map<String, Object> payload) {

    public enum Kind { TEXT, CUSTOM }

    public static PageComponent text(String text) {
        return new PageComponent(Kind.TEXT, text, null, Map.of());
    }

    public static PageComponent custom(String customType, Map<String, Object> payload) {
        return new PageComponent(Kind.CUSTOM, null, customType,
                payload == null ? Map.of() : Map.copyOf(payload));
    }
}
