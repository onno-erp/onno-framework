package com.onec.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles the {@code onec-list} grid's declarative filter values (sent by the React island as
 * {@code eq}/{@code ge}/{@code le} query params) into a safe SQL fragment with bound parameters.
 * It is the list-query counterpart of {@link WidgetFilter}: a SELECT-options filter sends one
 * {@code eq}, a date-range filter sends a {@code ge} and/or {@code le}.
 *
 * <p>Each param is a {@code "column,value"} pair. Injection safety rests on the same two rules as
 * {@link WidgetFilter}: the column (left of the first comma) must be a known column — validated
 * against the entity's columns plus a small system allowlist and a strict identifier pattern — and
 * the value (everything after it) is <em>always</em> a bound parameter, never interpolated. An
 * unrecognised column is skipped with a warning rather than failing the whole list, so a stale
 * filter degrades to "no filter" instead of an error surface.</p>
 */
public final class ListFilter {

    private ListFilter() {}

    private static final Logger log = LoggerFactory.getLogger(ListFilter.class);

    /** System columns a filter may reference even though they aren't business attributes. */
    private static final Set<String> SYSTEM_COLUMNS = Set.of(
            "_posted", "_deletion_mark", "_active", "_date", "_period",
            "_number", "_code", "_description", "_is_folder");

    private static final Pattern IDENTIFIER = Pattern.compile("^_?[a-z][a-z0-9_]*$");

    /** A compiled predicate: a {@code WHERE}-ready fragment (no leading AND) and its bindings. */
    public record Result(String sql, Map<String, Object> bindings) {
        public boolean isEmpty() {
            return sql == null || sql.isBlank();
        }
    }

    /**
     * @param eq             equality pairs {@code "column,value"} → {@code CAST(column AS VARCHAR) = value}
     * @param ge             lower-bound pairs {@code "column,value"} → {@code column >= value}
     * @param le             upper-bound pairs {@code "column,value"} → {@code column <= value}
     * @param allowedColumns the entity's column names; the system allowlist is added automatically
     */
    public static Result parse(List<String> eq, List<String> ge, List<String> le,
                               Set<String> allowedColumns) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder();
        int[] i = {0};
        // Equality compares as text so a SELECT-options value (always a string) matches any column
        // type without coercion; the range bounds compare natively so dates/numbers order correctly.
        append(sql, bindings, i, eq, allowedColumns, "CAST(%s AS VARCHAR) = :%s");
        append(sql, bindings, i, ge, allowedColumns, "%s >= :%s");
        append(sql, bindings, i, le, allowedColumns, "%s <= :%s");
        return new Result(sql.toString(), bindings);
    }

    private static void append(StringBuilder sql, Map<String, Object> bindings, int[] i,
                               List<String> pairs, Set<String> allowedColumns, String template) {
        if (pairs == null) {
            return;
        }
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            int comma = pair.indexOf(',');
            if (comma < 0) {
                continue;
            }
            String column = pair.substring(0, comma).trim().toLowerCase();
            String value = pair.substring(comma + 1).trim();
            if (value.isEmpty()) {
                continue; // a cleared control sends no constraint
            }
            if (!IDENTIFIER.matcher(column).matches()
                    || !(allowedColumns.contains(column) || SYSTEM_COLUMNS.contains(column))) {
                log.warn("Ignoring list filter on unknown column: {}", column);
                continue;
            }
            String param = "lf" + (i[0]++);
            if (sql.length() > 0) {
                sql.append(" AND ");
            }
            sql.append(String.format(template, column, param));
            bindings.put(param, value);
        }
    }
}
