package su.onno.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent declaration of a repeatable row group, from {@link CommandBuilder#inputRows}.
 * {@link #required()} demands at least one row; each {@link #column} is a typed cell configured
 * with the scalar {@link InputBuilder} (its {@code required()} applies to every row).
 *
 * <pre>
 * .inputRows("lines", g -&gt; g.title("Lines").required()
 *     .column("book", c -&gt; c.title("Book").reference(Book.class).required())
 *     .column("qty", c -&gt; c.title("Qty").number().required()))
 * </pre>
 */
public final class InputRowsBuilder {

    private final String key;
    private String title;
    private boolean required;
    private final List<InputBuilder> columns = new ArrayList<>();

    InputRowsBuilder(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Row group key must not be blank");
        }
        this.key = key;
    }

    /** The human title; defaults to the key. */
    public InputRowsBuilder title(String title) {
        this.title = title;
        return this;
    }

    /** Require at least one row. */
    public InputRowsBuilder required() {
        this.required = true;
        return this;
    }

    /** Declare a typed column of the row. */
    public InputRowsBuilder column(String key, Consumer<InputBuilder> column) {
        InputBuilder b = new InputBuilder(key);
        column.accept(b);
        columns.add(b);
        return this;
    }

    CommandInputGroup build() {
        return new CommandInputGroup(key, title != null && !title.isBlank() ? title : key, required,
                columns.stream().map(InputBuilder::build).toList());
    }
}
