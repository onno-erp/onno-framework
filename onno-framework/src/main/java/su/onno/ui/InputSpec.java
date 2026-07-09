package su.onno.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Declares custom input fields for an entity's list toolbar, from {@link EntityView#inputs(InputSpec)},
 * and the fields of an action-form modal ({@link ActionSpec.ActionBuilder#form}).
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
 *
 * <p>Inside an action form (not a toolbar), an input may also be a repeatable <b>row group</b> — a
 * small tabular grid the user adds/removes rows in, each row a set of typed columns (a transient
 * analogue of a document {@code @TabularSection}). The handler reads the collected rows via
 * {@link ActionContext#inputRows(String)}:</p>
 *
 * <pre>
 * a.action("receive").label("Receive shipment").scope(ActionScope.TOOLBAR)
 *  .form(f -> f
 *      .input("note").label("Note")
 *      .group("lines", g -> g.label("Lines").required()
 *          .column("product", c -> c.label("Product").required())
 *          .column("qty", c -> c.label("Qty").type(InputType.NUMBER).required())))
 *  .handler(ctx -> {
 *      for (var row : ctx.inputRows("lines")) receive(row.get("product"), row.get("qty"));
 *      return ActionResult.refresh("Received");
 *  });
 * </pre>
 *
 * <p>Groups are an action-form feature only; a list toolbar renders scalar inputs and ignores any
 * declared groups. In the modal, scalar fields render first, then the row groups.</p>
 */
public final class InputSpec {

    private final List<InputBuilder> builders = new ArrayList<>();
    private final List<GroupBuilder> groupBuilders = new ArrayList<>();

    /** Start declaring an input with the given unique key (how the handler reads its value). */
    public InputBuilder input(String key) {
        InputBuilder b = new InputBuilder(key);
        builders.add(b);
        return b;
    }

    /**
     * Declare a repeatable row group (action forms only) with the given unique key — the modal
     * renders an add/remove grid of the columns configured in {@code body}, and the handler reads
     * the rows via {@link ActionContext#inputRows(String)}. Ignored by a list toolbar, which has no
     * grid widget.
     */
    public InputSpec group(String key, Consumer<GroupBuilder> body) {
        GroupBuilder g = new GroupBuilder(key);
        body.accept(g);
        groupBuilders.add(g);
        return this;
    }

    public List<InputField> inputs() {
        return builders.stream().map(InputBuilder::build).toList();
    }

    /** The declared row groups (action forms only), in declaration order. */
    public List<InputGroup> groups() {
        return groupBuilders.stream().map(GroupBuilder::build).toList();
    }

    /**
     * A resolved toolbar (or action-form) input field. For a {@link InputType#REFERENCE} field,
     * {@code reference} is the target catalog/document's logical name and {@code refKind} is
     * {@code "catalog"} or {@code "document"} (both {@code null} otherwise).
     */
    public record InputField(String key, String label, InputType type, String placeholder,
                             List<String> options, String defaultValue, boolean required,
                             String reference, String refKind) {
    }

    /** A resolved action-form row group: a repeatable row of {@code columns}. */
    public record InputGroup(String key, String label, boolean required, List<InputField> columns) {
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
        private Class<?> referenceTarget;

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

        /**
         * Make this a {@link InputType#REFERENCE reference picker} of another entity's records: the
         * modal renders the same searchable ref widget an entity form uses, and the submitted value
         * is the picked record's id. {@code target} must be a {@code @Catalog}- or
         * {@code @Document}-annotated class. Action forms only.
         */
        public InputBuilder reference(Class<?> target) {
            this.type = InputType.REFERENCE;
            this.referenceTarget = target;
            return this;
        }

        InputField build() {
            String reference = null;
            String refKind = null;
            if (type == InputType.REFERENCE) {
                if (referenceTarget == null) {
                    throw new IllegalStateException(
                            "Reference input '" + key + "' must declare a target via .reference(Class)");
                }
                su.onno.annotations.Catalog cat = referenceTarget.getAnnotation(su.onno.annotations.Catalog.class);
                su.onno.annotations.Document doc = referenceTarget.getAnnotation(su.onno.annotations.Document.class);
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
            return new InputField(key, label != null ? label : key, type, placeholder, options,
                    defaultValue, required, reference, refKind);
        }
    }

    /**
     * Fluent builder for a repeatable {@link #group(String, Consumer) row group}. {@code .required()}
     * gates submit on at least one row; each {@code .column(...)} is a typed cell reusing the scalar
     * {@link InputBuilder} (its {@code .required()} gates that column in every row).
     */
    public static final class GroupBuilder {
        private final String key;
        private String label;
        private boolean required;
        private final List<InputBuilder> columns = new ArrayList<>();

        GroupBuilder(String key) {
            this.key = key;
        }

        public GroupBuilder label(String label) {
            this.label = label;
            return this;
        }

        /** Require at least one row before the action-form dialog submits. */
        public GroupBuilder required() {
            this.required = true;
            return this;
        }

        /** Declare a typed column of the row; configure it with the same builder as a scalar input. */
        public GroupBuilder column(String key, Consumer<InputBuilder> body) {
            InputBuilder b = new InputBuilder(key);
            body.accept(b);
            columns.add(b);
            return this;
        }

        InputGroup build() {
            return new InputGroup(key, label != null ? label : key, required,
                    columns.stream().map(InputBuilder::build).toList());
        }
    }
}
