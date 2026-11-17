package su.onno.service;

import java.util.Map;

/**
 * A compiled SQL predicate: a {@code WHERE}-ready fragment (no leading {@code AND}) and its named
 * bindings. {@link Where} compiles a structured filter into one, record scopes ({@link ScopeClause})
 * fold into one, and the query builders AND it into their statements.
 */
public record SqlPredicate(String sql, Map<String, Object> bindings) {

    /** Matches everything. */
    public static final SqlPredicate NONE = new SqlPredicate("", Map.of());

    public boolean isEmpty() {
        return sql == null || sql.isBlank();
    }
}
