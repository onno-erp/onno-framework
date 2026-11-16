package su.onno.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles the {@code onno-list} grid's declarative filter values (sent by the React island as
 * {@code eq}/{@code in}/{@code like}/{@code prefix}/{@code ge}/{@code le} query params) into a safe
 * SQL fragment with bound parameters. It is the list-query counterpart of {@link WidgetFilter}: a
 * SELECT-options filter sends one {@code eq}, a multi-select sends one {@code in} per picked value,
 * a contains/starts-with typeahead sends a {@code like}/{@code prefix}, and a date-range or
 * number-range sends a {@code ge} and/or {@code le}.
 *
 * <p>Each param is a {@code "column,value"} pair. Injection safety rests on the same two rules as
 * {@link WidgetFilter}: the column (left of the first comma) must be a known column — validated
 * against the entity's columns plus a small system allowlist and a strict identifier pattern — and
 * the value (everything after it) is <em>always</em> a bound parameter, never interpolated. An
 * unrecognised column is skipped with a warning rather than failing the whole list, so a stale
 * filter degrades to "no filter" instead of an error surface.</p>
 *
 * <p>Fragments combine with {@code AND}, so several declared filters narrow the list jointly. A
 * multi-select is the one exception that is internally {@code OR}: its values fold into a single
 * {@code column IN (…)} that the row matches if any value hits; across different filters it is still
 * {@code AND}-ed with the rest.</p>
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

    /** Back-compat overload for callers that only use the equality + date-range channels. */
    public static Result parse(List<String> eq, List<String> ge, List<String> le,
                               Set<String> allowedColumns) {
        return parse(eq, null, null, null, ge, le, allowedColumns);
    }

    /**
     * @param eq             equality pairs {@code "column,value"} → {@code CAST(column AS VARCHAR) = value}
     * @param in             multi-select pairs {@code "column,value"} → folded per column into
     *                       {@code CAST(column AS VARCHAR) IN (value, …)}
     * @param like           contains pairs {@code "column,value"} → {@code LOWER(CAST(column AS VARCHAR)) LIKE %value%}
     * @param prefix         starts-with pairs {@code "column,value"} → {@code LOWER(CAST(column AS VARCHAR)) LIKE value%}
     * @param ge             lower-bound pairs {@code "column,value"} → {@code column >= value}
     * @param le             upper-bound pairs {@code "column,value"} → {@code column <= value}
     * @param allowedColumns the entity's column names; the system allowlist is added automatically
     */
    public static Result parse(List<String> eq, List<String> in, List<String> like, List<String> prefix,
                               List<String> ge, List<String> le, Set<String> allowedColumns) {
        return parse(eq, in, like, prefix, ge, le, allowedColumns, Set.of());
    }

    /**
     * As {@link #parse(List, List, List, List, List, List, Set)}, additionally told which columns
     * are numeric so a {@code ge}/{@code le} bound on one binds as a number (see
     * {@link #appendBound}).
     *
     * @param numericColumns lowercased names of the columns whose SQL type is numeric
     */
    public static Result parse(List<String> eq, List<String> in, List<String> like, List<String> prefix,
                               List<String> ge, List<String> le, Set<String> allowedColumns,
                               Set<String> numericColumns) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder();
        int[] i = {0};
        // Equality compares as text so a SELECT-options value (always a string) matches any column
        // type without coercion; the range bounds compare natively so dates/numbers order correctly.
        append(sql, bindings, i, eq, allowedColumns, "CAST(%s AS VARCHAR) = :%s");
        // Multi-select folds repeated values for one column into a single IN list (OR within the
        // column); different columns stay AND-ed via appendIn's own joiner.
        appendIn(sql, bindings, i, in, allowedColumns);
        // Contains/starts-with compare lowercased text via LIKE — the portable, DB-agnostic form of
        // a case-insensitive match (matching the global search box) rather than Postgres-only ILIKE.
        appendLike(sql, bindings, i, like, allowedColumns, false);
        appendLike(sql, bindings, i, prefix, allowedColumns, true);
        appendBound(sql, bindings, i, ge, allowedColumns, numericColumns, false);
        appendBound(sql, bindings, i, le, allowedColumns, numericColumns, true);
        return new Result(sql.toString(), bindings);
    }

    /** A calendar-date value as the island's date-range picker sends it ({@code yyyy-MM-dd}). */
    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** An absolute moment with an explicit offset, as an {@code Instant} bound is sent. */
    private static final Pattern OFFSET_DATE_TIME = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:\\d{2})$");

    /** A bare time of day, as a {@code LocalTime} bound is sent. */
    private static final Pattern TIME_ONLY = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?$");

    /**
     * A range bound ({@code ge}/{@code le}). A bare calendar date gets special treatment on both
     * counts a plain {@code column >= :value} binding would miss:
     * <ul>
     *   <li>the bound string is CAST to TIMESTAMP in SQL — Postgres refuses to compare a timestamp
     *       column against a varchar parameter (H2 coerces silently, so the break only showed in
     *       production), and the explicit cast works on both;</li>
     *   <li>the upper bound is extended to the end of its day. {@code _date} carries a time-of-day,
     *       so {@code <= '2024-12-31'} (midnight) would silently drop every row dated inside the
     *       range's last day — the classic "the date filter doesn't work" report.</li>
     * </ul>
     * A bound on a known numeric column (the number-range filter) binds as a {@link BigDecimal}:
     * Postgres has no {@code numeric >= varchar} operator, so a string binding would fail there
     * (H2 coerces silently). A value that is not a number is skipped — like a stale column, a
     * malformed bound degrades to "no constraint" instead of failing the list.
     *
     * <p>An offset-bearing ISO date-time (an {@code Instant} bound) and a bare time of day (a
     * {@code LocalTime} bound) are likewise CAST to their column type, for the same PostgreSQL
     * reason. Any other value (a full timestamp, a bound on a text column) keeps the plain native
     * comparison.</p>
     */
    private static void appendBound(StringBuilder sql, Map<String, Object> bindings, int[] i,
                                    List<String> pairs, Set<String> allowedColumns,
                                    Set<String> numericColumns, boolean upper) {
        if (pairs == null) {
            return;
        }
        for (String pair : pairs) {
            String[] cv = split(pair, allowedColumns);
            if (cv == null) {
                continue;
            }
            String op = upper ? "<=" : ">=";
            if (numericColumns.contains(cv[0])) {
                BigDecimal number = number(cv[1]);
                if (number == null) {
                    log.warn("Ignoring non-numeric list filter bound on column: {}", cv[0]);
                    continue;
                }
                String numeric = "lf" + (i[0]++);
                and(sql).append(cv[0]).append(' ').append(op).append(" :").append(numeric);
                bindings.put(numeric, number);
                continue;
            }
            String param = "lf" + (i[0]++);
            if (DATE_ONLY.matcher(cv[1]).matches()) {
                and(sql).append(cv[0]).append(' ').append(op)
                        .append(" CAST(:").append(param).append(" AS TIMESTAMP)");
                bindings.put(param, upper ? cv[1] + " 23:59:59.999999" : cv[1] + " 00:00:00");
            } else if (OFFSET_DATE_TIME.matcher(cv[1]).matches()) {
                and(sql).append(cv[0]).append(' ').append(op)
                        .append(" CAST(:").append(param).append(" AS TIMESTAMP WITH TIME ZONE)");
                bindings.put(param, cv[1]);
            } else if (TIME_ONLY.matcher(cv[1]).matches()) {
                and(sql).append(cv[0]).append(' ').append(op)
                        .append(" CAST(:").append(param).append(" AS TIME)");
                bindings.put(param, cv[1]);
            } else {
                and(sql).append(cv[0]).append(' ').append(op).append(" :").append(param);
                bindings.put(param, cv[1]);
            }
        }
    }

    /** A plain decimal ({@code -12.50}; no exponent, no grouping), or {@code null} when it isn't one. */
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$");

    /** Whether a field of this Java type persists as a numeric column (boxed or primitive). */
    public static boolean isNumeric(Class<?> type) {
        return type != null && (Number.class.isAssignableFrom(type)
                || (type.isPrimitive() && type != boolean.class && type != char.class && type != void.class));
    }

    private static BigDecimal number(String value) {
        return NUMBER.matcher(value).matches() && value.length() <= 64 ? new BigDecimal(value) : null;
    }

    private static void append(StringBuilder sql, Map<String, Object> bindings, int[] i,
                               List<String> pairs, Set<String> allowedColumns, String template) {
        if (pairs == null) {
            return;
        }
        for (String pair : pairs) {
            String[] cv = split(pair, allowedColumns);
            if (cv == null) {
                continue;
            }
            String param = "lf" + (i[0]++);
            and(sql).append(String.format(template, cv[0], param));
            bindings.put(param, cv[1]);
        }
    }

    /** Multi-select: group all values for the same column into one {@code column IN (…)} fragment. */
    private static void appendIn(StringBuilder sql, Map<String, Object> bindings, int[] i,
                                 List<String> pairs, Set<String> allowedColumns) {
        if (pairs == null) {
            return;
        }
        Map<String, List<String>> byColumn = new LinkedHashMap<>();
        for (String pair : pairs) {
            String[] cv = split(pair, allowedColumns);
            if (cv == null) {
                continue;
            }
            byColumn.computeIfAbsent(cv[0], k -> new ArrayList<>()).add(cv[1]);
        }
        for (Map.Entry<String, List<String>> e : byColumn.entrySet()) {
            List<String> placeholders = new ArrayList<>();
            for (String value : e.getValue()) {
                String param = "lf" + (i[0]++);
                placeholders.add(":" + param);
                bindings.put(param, value);
            }
            and(sql).append("CAST(").append(e.getKey()).append(" AS VARCHAR) IN (")
                    .append(String.join(", ", placeholders)).append(")");
        }
    }

    /** Contains/starts-with: a case-insensitive {@code LIKE} with the value wrapped at bind time. */
    private static void appendLike(StringBuilder sql, Map<String, Object> bindings, int[] i,
                                   List<String> pairs, Set<String> allowedColumns, boolean prefix) {
        if (pairs == null) {
            return;
        }
        for (String pair : pairs) {
            String[] cv = split(pair, allowedColumns);
            if (cv == null) {
                continue;
            }
            String param = "lf" + (i[0]++);
            and(sql).append("LOWER(CAST(").append(cv[0]).append(" AS VARCHAR)) LIKE :").append(param);
            String value = cv[1].toLowerCase();
            bindings.put(param, prefix ? value + "%" : "%" + value + "%");
        }
    }

    /**
     * Validate one {@code "column,value"} pair: returns {@code [column, value]} (column lowercased,
     * value trimmed) when the column is a known, identifier-safe column and the value is non-empty,
     * else {@code null} (a cleared control or a stale/unknown column degrades to no constraint).
     */
    private static String[] split(String pair, Set<String> allowedColumns) {
        if (pair == null) {
            return null;
        }
        int comma = pair.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String column = pair.substring(0, comma).trim().toLowerCase();
        String value = pair.substring(comma + 1).trim();
        if (value.isEmpty()) {
            return null; // a cleared control sends no constraint
        }
        if (!IDENTIFIER.matcher(column).matches()
                || !(allowedColumns.contains(column) || SYSTEM_COLUMNS.contains(column))) {
            log.warn("Ignoring list filter on unknown column: {}", column);
            return null;
        }
        return new String[] {column, value};
    }

    private static StringBuilder and(StringBuilder sql) {
        if (sql.length() > 0) {
            sql.append(" AND ");
        }
        return sql;
    }
}
