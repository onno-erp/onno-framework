package su.onno.ui;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the SQL aggregate expression for a count/metric card. {@code count} needs no
 * column; {@code sum}/{@code avg}/{@code min}/{@code max} aggregate a single numeric
 * column, which must be one of the entity's known columns (so the metric field can
 * never carry arbitrary SQL). {@code sum} coalesces to zero so an empty table reads
 * as {@code 0} rather than null.
 */
public final class WidgetAggregate {

    private WidgetAggregate() {}

    private static final Pattern IDENTIFIER = Pattern.compile("^_?[a-z][a-z0-9_]*$");
    private static final Set<String> FUNCTIONS = Set.of("sum", "avg", "min", "max");

    /**
     * @param metric  one of {@code count|sum|avg|min|max} (null/blank → {@code count})
     * @param field   the column to aggregate; required for everything but {@code count}
     * @param columns the entity's valid column names
     * @throws IllegalArgumentException if the metric is unknown, or the field is missing/unknown
     */
    public static String expression(String metric, String field, Set<String> columns) {
        String m = (metric == null || metric.isBlank()) ? "count" : metric.toLowerCase();
        if (m.equals("count")) {
            return "COUNT(*)";
        }
        if (!FUNCTIONS.contains(m)) {
            throw new IllegalArgumentException("Unknown metric: " + metric);
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("metricField is required for metric '" + m + "'");
        }
        String col = field.toLowerCase();
        if (!IDENTIFIER.matcher(col).matches() || !columns.contains(col)) {
            throw new IllegalArgumentException("Unknown metric field: " + field);
        }
        return m.equals("sum")
                ? "COALESCE(SUM(" + col + "), 0)"
                : m.toUpperCase() + "(" + col + ")";
    }
}
