package su.onno.ui;

import java.util.List;
import java.util.Map;

/**
 * A freeform block a {@link Page} composes beyond the widget grid: a text block, a {@code
 * div-custom} extension, a full interactive entity list, or a section of action buttons.
 * Renderer-agnostic; the DivKit emitter compiles it (text → {@code div-text}, custom → {@code
 * div-custom}, list → the {@code onno-list} surface built from the entity's metadata, actions →
 * the {@code onno-actions} button section).
 */
public record PageComponent(Kind kind, String text, String customType, Map<String, Object> payload,
                            Class<?> entity) {

    public enum Kind { TEXT, CUSTOM, LIST, ACTIONS }

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

    /**
     * A section of action buttons under an optional heading. {@code buttons} are the rendered
     * descriptors ({@code key/label/icon/server/url}); the server handlers live on the {@link
     * PageBuilder} and are resolved by key when the button posts back. The renderer fills in the
     * page route the buttons post to.
     */
    public static PageComponent actions(String heading, List<Map<String, Object>> buttons) {
        return new PageComponent(Kind.ACTIONS, null, null,
                Map.of("heading", heading == null ? "" : heading,
                        "buttons", buttons == null ? List.of() : List.copyOf(buttons)),
                null);
    }
}
