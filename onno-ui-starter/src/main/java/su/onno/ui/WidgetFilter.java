package su.onno.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an authored count/metric-card {@code filter} predicate into a safe SQL
 * fragment with bound parameters. The grammar is deliberately small — a chain of
 * {@code field op value} comparisons joined by {@code AND} — so a dashboard author
 * can write {@code config("filter", "status != cancelled")} without the framework
 * ever interpolating user text into SQL.
 *
 * <p>Injection safety rests on two rules: the left-hand side must be a known column
 * (validated against the entity's columns plus a small system allowlist) matching a
 * strict identifier pattern, and the right-hand value is <em>always</em> a bound
 * parameter. An unrecognised column is skipped with a warning rather than failing the
 * whole card, so a typo degrades to "no filter" instead of an error surface.</p>
 */
public final class WidgetFilter {

    private WidgetFilter() {}

    private static final Logger log = LoggerFactory.getLogger(WidgetFilter.class);

    /** System columns a filter may reference even though they aren't business attributes. */
    private static final Set<String> SYSTEM_COLUMNS = Set.of(
            "_posted", "_deletion_mark", "_active", "_date", "_period",
            "_number", "_code", "_description", "_is_folder");

    private static final Pattern IDENTIFIER = Pattern.compile("^_?[a-z][a-z0-9_]*$");
    // field op value — op is the longest-match-first alternation so "<=" wins over "<".
    private static final Pattern CLAUSE = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(<=|>=|!=|<>|=|<|>)\\s*(.+?)\\s*$");
    private static final Pattern AND = Pattern.compile("(?i)\\s+AND\\s+");

    /** A compiled predicate: a {@code WHERE}-ready fragment (no leading AND) and its bindings. */
    public record Result(String sql, Map<String, Object> bindings) {
        public boolean isEmpty() {
            return sql == null || sql.isBlank();
        }
    }

    /**
     * @param filter         the authored predicate, e.g. {@code "status != cancelled AND _posted = true"}
     * @param allowedColumns the entity's column names; the system allowlist is added automatically
     */
    public static Result parse(String filter, Set<String> allowedColumns) {
        return parse(filter, allowedColumns, Set.of());
    }

    /**
     * Like {@link #parse(String, Set)}, but values compared against a column in {@code uuidColumns}
     * (ref / enum columns, whose SQL type is {@code UUID}) are bound as typed {@code java.util.UUID}s.
     * PostgreSQL rejects a varchar parameter against a uuid column (H2 coerces silently, same class
     * of problem as #163) — the ref picker's cascading {@code refFilter} compares record ids, so it
     * passes the ref/enum column set here. A malformed uuid value degrades to a varchar bind (the
     * clause then matches nothing on H2 and errors on PG only for a hand-crafted request).
     */
    public static Result parse(String filter, Set<String> allowedColumns, Set<String> uuidColumns) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        if (filter == null || filter.isBlank()) {
            return new Result("", bindings);
        }
        StringBuilder sql = new StringBuilder();
        int i = 0;
        for (String clause : AND.split(filter.trim())) {
            Matcher m = CLAUSE.matcher(clause);
            if (!m.matches()) {
                log.warn("Ignoring unparseable widget filter clause: {}", clause);
                continue;
            }
            String column = m.group(1).toLowerCase();
            if (!IDENTIFIER.matcher(column).matches()
                    || !(allowedColumns.contains(column) || SYSTEM_COLUMNS.contains(column))) {
                log.warn("Ignoring widget filter on unknown column: {}", column);
                continue;
            }
            String op = m.group(2).equals("!=") ? "<>" : m.group(2);
            Object value = literal(m.group(3));
            if (value instanceof String s && uuidColumns.contains(column)) {
                try {
                    value = java.util.UUID.fromString(s);
                } catch (IllegalArgumentException notAUuid) {
                    // fall through to the varchar bind; see the javadoc
                }
            }
            if (sql.length() > 0) {
                sql.append(" AND ");
            }
            if (value == null) {
                // "x = null" / "x != null" → proper null semantics.
                sql.append(column).append(op.equals("<>") ? " IS NOT NULL" : " IS NULL");
            } else {
                String param = "wf" + (i++);
                sql.append(column).append(' ').append(op).append(" :").append(param);
                bindings.put(param, value);
            }
        }
        return new Result(sql.toString(), bindings);
    }

    /** Coerce a raw RHS token to a typed bound value: null / boolean / number / (unquoted) string. */
    private static Object literal(String raw) {
        String v = raw.trim();
        if ((v.startsWith("'") && v.endsWith("'") && v.length() >= 2)
                || (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)) {
            return v.substring(1, v.length() - 1);
        }
        if (v.equalsIgnoreCase("null")) return null;
        if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
        try {
            if (v.matches("-?\\d+")) return Long.parseLong(v);
            return new java.math.BigDecimal(v);
        } catch (NumberFormatException notANumber) {
            return v;
        }
    }
}
