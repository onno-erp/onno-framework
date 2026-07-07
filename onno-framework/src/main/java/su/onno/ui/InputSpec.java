package su.onno.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares custom input fields for an entity's list toolbar, from {@link EntityView#inputs(InputSpec)}.
 *
 * <p>An input sits in the toolbar next to the custom action buttons. It doesn't filter the list on
 * its own — instead its current value is handed to the {@link ActionSpec} handlers via
 * {@link ActionContext#input(String)} when a button is clicked. So you might add an "As of" date and
 * a "Reason" text field, then a "Generate report" button whose handler reads both.</p>
 *
 * <pre>
 * public void inputs(InputSpec in) {
 *     in.input("asOf").label("As of").type(InputType.DATE);
 *     in.input("currency").label("Currency").type(InputType.SELECT).options("USD", "EUR").value("USD");
 *     in.input("reason").label("Reason").type(InputType.TEXT).placeholder("optional");
 * }
 * </pre>
 */
public final class InputSpec {

    private final List<InputBuilder> builders = new ArrayList<>();

    /** Start declaring an input with the given unique key (how the handler reads its value). */
    public InputBuilder input(String key) {
        InputBuilder b = new InputBuilder(key);
        builders.add(b);
        return b;
    }

    public List<InputField> inputs() {
        return builders.stream().map(InputBuilder::build).toList();
    }

    /** A resolved toolbar (or action-form) input field. */
    public record InputField(String key, String label, InputType type, String placeholder,
                             List<String> options, String defaultValue, boolean required) {
    }

    /** Fluent builder for one input; setters may be called in any order. */
    public static final class InputBuilder {
        private final String key;
        private String label;
        private InputType type = InputType.TEXT;
        private String placeholder = "";
        private List<String> options = List.of();
        private String defaultValue = "";
        private boolean required;

        InputBuilder(String key) {
            this.key = key;
        }

        public InputBuilder label(String label) {
            this.label = label;
            return this;
        }

        public InputBuilder type(InputType type) {
            this.type = type;
            return this;
        }

        public InputBuilder placeholder(String placeholder) {
            this.placeholder = placeholder;
            return this;
        }

        /** The choices for a {@link InputType#SELECT} input. */
        public InputBuilder options(String... options) {
            this.options = List.of(options);
            return this;
        }

        /** The initial value the field carries before the user changes it. */
        public InputBuilder value(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        /**
         * Require a non-blank value before an action-form dialog submits (see
         * {@link ActionSpec.ActionBuilder#form}). Toolbar inputs ignore it — they're ambient values,
         * not a gated form.
         */
        public InputBuilder required() {
            this.required = true;
            return this;
        }

        InputField build() {
            return new InputField(key, label != null ? label : key, type, placeholder, options,
                    defaultValue, required);
        }
    }
}
