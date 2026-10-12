package su.onno.ui;

import org.jdbi.v3.core.statement.SqlStatement;

import java.util.Map;

/**
 * A compiled record scope: a SQL boolean fragment plus its named bindings — the same shape as
 * {@link ListFilter.Result}/{@link WidgetFilter.Result}, ANDed into a query's {@code WHERE}.
 * {@link #ALL} (an unscoped subject) contributes nothing.
 */
public record ScopeClause(String sql, Map<String, Object> bindings) {

    /** No restriction. */
    public static final ScopeClause ALL = new ScopeClause("", Map.of());

    /** Whether this clause restricts nothing. */
    public boolean isAll() {
        return sql == null || sql.isEmpty();
    }

    /** {@code " AND (sql)"}, or {@code ""} when unrestricted — append to an existing WHERE. */
    public String and() {
        return isAll() ? "" : " AND (" + sql + ")";
    }

    /** {@code " WHERE (sql)"}, or {@code ""} when unrestricted — for a query without a WHERE. */
    public String where() {
        return isAll() ? "" : " WHERE (" + sql + ")";
    }

    /** The bare predicate, {@code "1=1"} when unrestricted. */
    public String predicate() {
        return isAll() ? "1=1" : sql;
    }

    /** Bind this clause's parameters onto {@code statement}. */
    public <S extends SqlStatement<S>> S bind(S statement) {
        bindings.forEach(statement::bind);
        return statement;
    }

    /** Fold this clause into a widget filter, so widget/aggregate builders apply it unchanged. */
    public WidgetFilter.Result andInto(WidgetFilter.Result filter) {
        if (isAll()) return filter;
        Map<String, Object> merged = new java.util.LinkedHashMap<>(filter.bindings());
        merged.putAll(bindings);
        String sql = filter.isEmpty() ? this.sql : "(" + filter.sql() + ") AND (" + this.sql + ")";
        return new WidgetFilter.Result(sql, merged);
    }

    /** Fold this clause into a declarative list filter. */
    public ListFilter.Result andInto(ListFilter.Result filter) {
        if (isAll()) return filter;
        Map<String, Object> merged = new java.util.LinkedHashMap<>(filter == null ? Map.of() : filter.bindings());
        merged.putAll(bindings);
        String sql = filter == null || filter.isEmpty() ? this.sql : "(" + filter.sql() + ") AND (" + this.sql + ")";
        return new ListFilter.Result(sql, merged);
    }
}
