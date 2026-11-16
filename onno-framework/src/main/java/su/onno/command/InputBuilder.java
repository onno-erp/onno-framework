package su.onno.command;

import su.onno.annotations.Catalog;
import su.onno.annotations.Document;

import java.util.List;

/**
 * Fluent declaration of one command input, from {@link CommandBuilder#input} or a
 * {@link InputRowsBuilder#column row-group column}. Defaults to a single-line text input.
 *
 * <pre>
 * .input("reason", i -&gt; i.title("Reason").longText().required())
 * .input("asOf", i -&gt; i.title("As of").date())
 * .input("supplier", i -&gt; i.title("Supplier").reference(Supplier.class))
 * </pre>
 */
public final class InputBuilder {

    private final String key;
    private String title;
    private InputType type = InputType.TEXT;
    private List<String> options = List.of();
    private String defaultValue = "";
    private boolean required;
    private Class<?> referenceTarget;

    InputBuilder(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Input key must not be blank");
        }
        this.key = key;
    }

    /** The human title; defaults to the key. */
    public InputBuilder title(String title) {
        this.title = title;
        return this;
    }

    /** Single-line free text (the default). */
    public InputBuilder text() {
        return type(InputType.TEXT);
    }

    /** Free text that may run to several lines (a reason, a note). */
    public InputBuilder longText() {
        return type(InputType.LONG_TEXT);
    }

    /** An ISO {@code yyyy-MM-dd} date. */
    public InputBuilder date() {
        return type(InputType.DATE);
    }

    /** A number (delivered to the handler as its decimal string). */
    public InputBuilder number() {
        return type(InputType.NUMBER);
    }

    /** One of a fixed set of values. */
    public InputBuilder oneOf(String... options) {
        this.options = List.of(options);
        return type(InputType.SELECT);
    }

    /**
     * The id of a record of another catalog or document; {@code target} must be a
     * {@code @Catalog}- or {@code @Document}-annotated class.
     */
    public InputBuilder reference(Class<?> target) {
        this.referenceTarget = target;
        return type(InputType.REFERENCE);
    }

    /** Set the kind of value explicitly. */
    public InputBuilder type(InputType type) {
        this.type = type == null ? InputType.TEXT : type;
        return this;
    }

    /** The value the input starts with. */
    public InputBuilder defaultValue(String value) {
        this.defaultValue = value;
        return this;
    }

    /** Require a non-blank value. */
    public InputBuilder required() {
        this.required = true;
        return this;
    }

    CommandInput build() {
        String reference = null;
        String refKind = null;
        if (type == InputType.REFERENCE) {
            if (referenceTarget == null) {
                throw new IllegalStateException(
                        "Reference input '" + key + "' must declare a target via .reference(Class)");
            }
            Catalog cat = referenceTarget.getAnnotation(Catalog.class);
            Document doc = referenceTarget.getAnnotation(Document.class);
            if (cat != null) {
                reference = cat.name();
                refKind = "catalog";
            } else if (doc != null) {
                reference = doc.name();
                refKind = "document";
            } else {
                throw new IllegalStateException("Reference target " + referenceTarget.getName()
                        + " is neither a @Catalog nor a @Document");
            }
        }
        return new CommandInput(key, title != null && !title.isBlank() ? title : key, type,
                options, defaultValue, required, reference, refKind);
    }
}
