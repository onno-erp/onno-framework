package su.onno.print;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The data a {@link PrintForm} binds into its template.
 *
 * <pre>{@code
 * model.text("customer", order.getCustomer())
 *      .date("date", order.getDate(), "dd.MM.yyyy")
 *      .table("items", order.getItems(), (row, line) -> row
 *          .text("book", line.getBook())
 *          .number("qty", line.getQuantity()));
 * }</pre>
 *
 * A template references {@code {{customer}}}; a table row containing {@code {{items.book}}} is
 * repeated once per item (and removed when the collection is empty in Word, blanked in Excel).
 */
public final class TemplateModel extends TemplateValues<TemplateModel> {

    private final Locale locale;
    private final Map<String, List<Row>> tables = new LinkedHashMap<>();

    /**
     * @param textOf renders a {@code text(...)} value as display text
     * @param locale locale for Word number/date patterns
     */
    public TemplateModel(Function<Object, String> textOf, Locale locale) {
        super(textOf);
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    /** Repeats every template row that references {@code {{key.<column>}}} once per item. */
    public <R> TemplateModel table(String key, Iterable<R> items, BiConsumer<Row, R> columns) {
        List<Row> rows = new ArrayList<>();
        if (items != null) {
            for (R item : items) {
                Row row = new Row(textOf);
                columns.accept(row, item);
                rows.add(row);
            }
        }
        tables.put(PlaceholderSyntax.requireKey(key), rows);
        return this;
    }

    /** The bound tables keyed by name. */
    public Map<String, List<Row>> tables() {
        return Collections.unmodifiableMap(tables);
    }

    /** Locale for rendering Word number/date patterns. */
    public Locale locale() {
        return locale;
    }

    /** The column bindings of one repeated table row; {@code {{items.qty}}} reads column {@code qty}. */
    public static final class Row extends TemplateValues<Row> {
        Row(Function<Object, String> textOf) {
            super(textOf);
        }
    }
}
