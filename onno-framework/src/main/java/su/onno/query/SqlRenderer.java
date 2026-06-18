package su.onno.query;

import java.util.List;

/**
 * The shared {@code SELECT … FROM … JOIN … WHERE … GROUP BY … ORDER BY} assembler. Both
 * the general query engine and the register virtual tables build a {@link RenderModel}
 * of already-resolved SQL fragments and hand it here, so there is exactly one place that
 * stitches a query string together. Parameter binding stays with the caller (it owns the
 * value map); this class only concerns itself with the SQL text.
 *
 * <p>It deliberately knows nothing about descriptors, refs, or register semantics &mdash;
 * those live in {@link JoinWalker} / {@code QueryEngine} and {@code RegisterPersistence}
 * respectively. Fragments arrive pre-rendered (e.g. a select item is already
 * {@code "SUM(qty) AS qty"}, a where clause already {@code "_active = TRUE"}).
 */
public final class SqlRenderer {

    private SqlRenderer() {
    }

    /**
     * Pre-rendered SQL fragments for one statement.
     *
     * @param select   select-list items (e.g. {@code "t0._code AS code"}); never empty
     * @param fromTable the FROM table name
     * @param fromAlias optional table alias, or {@code null}
     * @param joins    full JOIN clauses, or {@code null}
     * @param where    AND-combined predicate fragments, or {@code null}/empty
     * @param groupBy  GROUP BY expressions, or {@code null}/empty
     * @param orderBy  ORDER BY terms (e.g. {@code "t0._date DESC"}), or {@code null}/empty
     * @param limit    optional LIMIT
     * @param offset   optional OFFSET
     */
    public record RenderModel(List<String> select,
                              String fromTable,
                              String fromAlias,
                              List<String> joins,
                              List<String> where,
                              List<String> groupBy,
                              List<String> orderBy,
                              Integer limit,
                              Integer offset) {
    }

    public static String render(RenderModel model) {
        if (model.select() == null || model.select().isEmpty()) {
            throw new IllegalArgumentException("A query needs at least one select item");
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", model.select()));
        sql.append(" FROM ").append(model.fromTable());
        if (notBlank(model.fromAlias())) {
            sql.append(" ").append(model.fromAlias());
        }
        if (notEmpty(model.joins())) {
            for (String join : model.joins()) {
                sql.append(" ").append(join);
            }
        }
        if (notEmpty(model.where())) {
            sql.append(" WHERE ").append(String.join(" AND ", model.where()));
        }
        if (notEmpty(model.groupBy())) {
            sql.append(" GROUP BY ").append(String.join(", ", model.groupBy()));
        }
        if (notEmpty(model.orderBy())) {
            sql.append(" ORDER BY ").append(String.join(", ", model.orderBy()));
        }
        if (model.limit() != null) {
            sql.append(" LIMIT ").append(model.limit());
        }
        if (model.offset() != null) {
            sql.append(" OFFSET ").append(model.offset());
        }
        return sql.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
