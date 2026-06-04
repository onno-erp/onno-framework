package com.onec.query;

import com.onec.metadata.MetadataRegistry;
import com.onec.types.Ref;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link QuerySpec} to SQL via the {@link JoinWalker} (ref-navigation joins)
 * and the shared {@link SqlRenderer}, then runs it through JDBI. This is the unified,
 * type-safe query entry point over catalogs, documents, and registers; {@link #from}
 * starts a fluent {@link QueryBuilder}.
 */
public final class QueryEngine {

    private final Jdbi jdbi;
    private final EntityResolver resolver;

    public QueryEngine(Jdbi jdbi, MetadataRegistry registry) {
        this.jdbi = jdbi;
        this.resolver = new EntityResolver(registry);
    }

    /** Start building a query over {@code entity} (catalog, document, or register class). */
    public <T> QueryBuilder<T> from(Class<T> entity) {
        return new QueryBuilder<>(this, entity);
    }

    /** Run a spec and return untyped projection rows. */
    public List<Row> fetch(QuerySpec spec) {
        Compiled compiled = compile(spec);
        List<Map<String, Object>> raw = jdbi.withHandle(handle -> {
            Query query = handle.createQuery(compiled.sql());
            compiled.bindings().forEach(query::bind);
            return query.mapToMap().list();
        });
        List<Row> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> map : raw) {
            rows.add(new Row(map));
        }
        return rows;
    }

    /** Run a spec and map each row onto {@code dtoType} (record or POJO). */
    public <D> List<D> fetchInto(QuerySpec spec, Class<D> dtoType) {
        List<D> result = new ArrayList<>();
        for (Row row : fetch(spec)) {
            result.add(RowMapper.map(row, dtoType));
        }
        return result;
    }

    /** Compiled SQL plus its ordered parameter bindings. Package-visible for tests. */
    record Compiled(String sql, Map<String, Object> bindings) {
    }

    Compiled compile(QuerySpec spec) {
        EntityMeta root = resolver.forClass(spec.from());
        JoinWalker walker = new JoinWalker(resolver, root);

        List<String> selectItems = new ArrayList<>();
        if (spec.select().isEmpty()) {
            selectItems.add(walker.rootAlias() + ".*");
        } else {
            for (Select select : spec.select()) {
                String expr = select.path() == null ? "*" : walker.column(select.path());
                selectItems.add(aggregate(select.agg(), expr) + " AS " + select.outputName());
            }
        }

        Map<String, Object> bindings = new LinkedHashMap<>();
        List<String> whereClauses = new ArrayList<>();
        int param = 0;
        for (Predicate predicate : spec.where()) {
            String column = walker.column(predicate.path());
            param = renderPredicate(predicate, column, param, whereClauses, bindings);
        }

        List<String> groupBy = new ArrayList<>();
        for (Path path : spec.groupBy()) {
            groupBy.add(walker.column(path));
        }

        List<String> orderBy = new ArrayList<>();
        for (Order order : spec.orderBy()) {
            orderBy.add(walker.column(order.path()) + " " + order.direction().name());
        }

        SqlRenderer.RenderModel model = new SqlRenderer.RenderModel(
                selectItems, root.table(), walker.rootAlias(), walker.joins(),
                whereClauses, groupBy, orderBy, spec.limit(), spec.offset());
        return new Compiled(SqlRenderer.render(model), bindings);
    }

    private static String aggregate(Select.Agg agg, String expr) {
        return agg == Select.Agg.NONE ? expr : agg.name() + "(" + expr + ")";
    }

    private static int renderPredicate(Predicate predicate, String column, int param,
                                       List<String> whereClauses, Map<String, Object> bindings) {
        switch (predicate.op()) {
            case EQ -> { whereClauses.add(column + " = :p" + param); bindings.put("p" + param++, unwrap(predicate.value())); }
            case NE -> { whereClauses.add(column + " <> :p" + param); bindings.put("p" + param++, unwrap(predicate.value())); }
            case GT -> { whereClauses.add(column + " > :p" + param); bindings.put("p" + param++, unwrap(predicate.value())); }
            case GTE -> { whereClauses.add(column + " >= :p" + param); bindings.put("p" + param++, unwrap(predicate.value())); }
            case LT -> { whereClauses.add(column + " < :p" + param); bindings.put("p" + param++, unwrap(predicate.value())); }
            case LTE -> { whereClauses.add(column + " <= :p" + param); bindings.put("p" + param++, unwrap(predicate.value())); }
            case LIKE -> { whereClauses.add(column + " LIKE :p" + param); bindings.put("p" + param++, predicate.value()); }
            case BETWEEN -> {
                whereClauses.add(column + " BETWEEN :p" + param + " AND :p" + (param + 1));
                bindings.put("p" + param++, unwrap(predicate.value()));
                bindings.put("p" + param++, unwrap(predicate.value2()));
            }
            case IN -> {
                List<?> values = predicate.values();
                if (values == null || values.isEmpty()) {
                    whereClauses.add("1 = 0"); // empty IN matches nothing
                    break;
                }
                List<String> placeholders = new ArrayList<>();
                for (Object value : values) {
                    placeholders.add(":p" + param);
                    bindings.put("p" + param++, unwrap(value));
                }
                whereClauses.add(column + " IN (" + String.join(", ", placeholders) + ")");
            }
            case IS_NULL -> whereClauses.add(column + " IS NULL");
            case IS_NOT_NULL -> whereClauses.add(column + " IS NOT NULL");
        }
        return param;
    }

    /** A {@code Ref} operand binds as its UUID, mirroring how Ref columns are stored. */
    private static Object unwrap(Object value) {
        return value instanceof Ref<?> ref ? ref.id() : value;
    }
}
