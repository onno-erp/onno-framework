package com.onec.query;

import java.util.List;

/**
 * The declarative AST for a query over any catalog, document, or register &mdash; the
 * single source of truth that the fluent builder produces and that the engine compiles
 * to SQL. Being a plain value, the same query is expressible without the fluent API
 * (and, later, as JSON for UI/agents or parsed from a 1C-style text syntax).
 *
 * @param from    the root entity class (catalog/document/register)
 * @param select  projected columns / ref-navigations / aggregates
 * @param where   AND-combined filter predicates
 * @param groupBy grouping paths
 * @param orderBy ordering terms
 * @param totals  1C {@code ИТОГИ} hierarchy paths (carried in the AST; tree folding is a follow-up)
 * @param limit   optional row cap
 * @param offset  optional row offset
 */
public record QuerySpec(Class<?> from,
                        List<Select> select,
                        List<Predicate> where,
                        List<Path> groupBy,
                        List<Order> orderBy,
                        List<Path> totals,
                        Integer limit,
                        Integer offset) {

    public QuerySpec {
        if (from == null) throw new IllegalArgumentException("A query needs a 'from' entity");
        select = copy(select);
        where = copy(where);
        groupBy = copy(groupBy);
        orderBy = copy(orderBy);
        totals = copy(totals);
    }

    private static <E> List<E> copy(List<E> in) {
        return in == null ? List.of() : List.copyOf(in);
    }
}
