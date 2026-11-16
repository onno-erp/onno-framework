package su.onno.print;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Placeholder bindings shared by a {@link TemplateModel} and each of its table rows. {@code text}
 * accepts any value and renders it the way the UI displays it (a {@code Ref} as the referenced
 * record's name, an {@code @Enumeration} constant as its {@code @EnumLabel}); {@code number} and
 * {@code date} keep the value typed so spreadsheet cells receive real numbers and dates.
 */
public abstract class TemplateValues<S extends TemplateValues<S>> {

    private final Map<String, TemplateValue> values = new LinkedHashMap<>();
    final Function<Object, String> textOf;

    TemplateValues(Function<Object, String> textOf) {
        this.textOf = Objects.requireNonNull(textOf, "textOf");
    }

    /** Binds {@code {{key}}} to {@code value} rendered as display text ({@code null} renders empty). */
    public S text(String key, Object value) {
        return put(key, new TemplateValue.Text(value == null ? "" : textOf.apply(value)));
    }

    /** Binds {@code {{key}}} to a number. */
    public S number(String key, Number value) {
        return number(key, value, null);
    }

    /**
     * Binds {@code {{key}}} to a number. In a Word template it is rendered with the
     * {@link java.text.DecimalFormat} {@code pattern} (e.g. {@code "#,##0.00"}); in an Excel template
     * the cell's own number format applies instead.
     */
    public S number(String key, Number value, String pattern) {
        return put(key, new TemplateValue.Number(decimal(value), pattern));
    }

    /** Binds {@code {{key}}} to a date or date-time. */
    public S date(String key, TemporalAccessor value) {
        return date(key, value, null);
    }

    /**
     * Binds {@code {{key}}} to a date or date-time. In a Word template it is rendered with the
     * {@link java.time.format.DateTimeFormatter} {@code pattern} (e.g. {@code "dd.MM.yyyy"}); in an
     * Excel template the cell's own date format applies (a General cell gets an ISO date format).
     */
    public S date(String key, TemporalAccessor value, String pattern) {
        return put(key, new TemplateValue.Date(value, pattern));
    }

    /** The bound values keyed by placeholder name, in binding order. */
    public Map<String, TemplateValue> values() {
        return Collections.unmodifiableMap(values);
    }

    private S put(String key, TemplateValue value) {
        values.put(PlaceholderSyntax.requireKey(key), value);
        return self();
    }

    @SuppressWarnings("unchecked")
    private S self() {
        return (S) this;
    }

    private static BigDecimal decimal(Number value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Double || value instanceof Float) return BigDecimal.valueOf(value.doubleValue());
        return new BigDecimal(value.toString());
    }
}
