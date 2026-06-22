package su.onno.ui;

import org.jdbi.v3.core.statement.SqlStatement;

import java.sql.Types;

/**
 * Null-safe JDBI parameter binding shared by the generic catalog/document write paths and the
 * framework-owned {@code onno_comments} writer.
 */
public final class SqlBind {

    private SqlBind() {
    }

    /**
     * Bind a value that may be {@code null}. A null is bound as an explicit unspecified-type SQL
     * NULL ({@link Types#OTHER}) so PostgreSQL infers the target column type — e.g. {@code uuid}
     * for a null {@code _parent} self-ref, any other null {@code Ref<T>}, or a null comment
     * {@code _author_id} (an unlinked principal) — instead of defaulting the parameter to
     * {@code character varying} and rejecting the implicit {@code varchar → uuid} cast. JDBI binds
     * an untyped null as varchar, which H2 silently coerces but PostgreSQL refuses, so every
     * top-level catalog/document insert (null {@code _parent}) and every comment from an unlinked
     * author would otherwise fail on Postgres. (#163, #171)
     *
     * @param stmt   the statement to bind on (an {@code Update}/{@code Query})
     * @param column the bound parameter name
     * @param value  the already type-coerced value (UUID, BigDecimal, String, …) or {@code null}
     */
    public static void nullable(SqlStatement<?> stmt, String column, Object value) {
        if (value == null) {
            stmt.bindNull(column, Types.OTHER);
        } else {
            stmt.bind(column, value);
        }
    }
}
