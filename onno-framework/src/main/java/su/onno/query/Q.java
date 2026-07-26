package su.onno.query;

import su.onno.fields.Field;
import su.onno.types.Ref;

import java.util.List;

/**
 * Static factory helpers for the fluent query DSL. They mirror the {@code col(...) /
 * ref(...) / eq(...)} shape from the issue and reuse the existing
 * {@link Field} method-reference mechanism for field resolution.
 *
 * <p>Paths produced here are left un-rooted; {@link QueryEngine#from(Class)} binds them to the
 * query root, so {@code Q.col(SalesOrder::getNumber)} works without repeating the class.
 *
 * <pre>{@code
 * import static su.onno.query.Q.*;
 *
 * query.from(SalesOrder.class)
 *      .select(col(SalesOrder::getNumber),
 *              ref(SalesOrder::getCustomer, Customer::getName),
 *              ref(SalesOrder::getCustomer, Customer::getRegion, Region::getDescription))
 *      .where(eq(SalesOrder::getStatus, "APPROVED"))
 *      .orderBy(desc(SalesOrder::getDate))
 *      .fetch();
 * }</pre>
 */
public final class Q {

    private Q() {
    }

    // --- Paths ---

    /** A single-attribute path, e.g. {@code attr(SalesOrder::getNumber)}. */
    public static <T, R> Path attr(Field<T, R> field) {
        return new Path(null, List.of(LambdaFields.name(field)));
    }

    /** A single {@code Ref} hop ending in an attribute: {@code customer -> name}. */
    public static <T, A, R> Path nav(Field<T, Ref<A>> hop, Field<A, R> terminal) {
        return new Path(null, List.of(LambdaFields.name(hop), LambdaFields.name(terminal)));
    }

    /** Two {@code Ref} hops ending in an attribute: {@code customer -> region -> name}. */
    public static <T, A, B, R> Path nav(Field<T, Ref<A>> hop1,
                                        Field<A, Ref<B>> hop2,
                                        Field<B, R> terminal) {
        return new Path(null, List.of(
                LambdaFields.name(hop1), LambdaFields.name(hop2), LambdaFields.name(terminal)));
    }

    // --- Select items ---

    /** Project a direct attribute. */
    public static <T, R> Select col(Field<T, R> field) {
        return new Select(attr(field), Select.Agg.NONE, null);
    }

    /** Project a column reached through one {@code Ref} hop (auto-join). */
    public static <T, A, R> Select ref(Field<T, Ref<A>> hop, Field<A, R> terminal) {
        return new Select(nav(hop, terminal), Select.Agg.NONE, null);
    }

    /** Project a column reached through two {@code Ref} hops (auto-join, deep navigation). */
    public static <T, A, B, R> Select ref(Field<T, Ref<A>> hop1,
                                          Field<A, Ref<B>> hop2,
                                          Field<B, R> terminal) {
        return new Select(nav(hop1, hop2, terminal), Select.Agg.NONE, null);
    }

    /** Project an arbitrary path (direct or navigation). */
    public static Select select(Path path) {
        return new Select(path, Select.Agg.NONE, null);
    }

    public static Select count() {
        return new Select(null, Select.Agg.COUNT, "count");
    }

    public static <T, R> Select count(Field<T, R> field) {
        return new Select(attr(field), Select.Agg.COUNT, null);
    }

    public static <T, R> Select sum(Field<T, R> field) {
        return new Select(attr(field), Select.Agg.SUM, null);
    }

    public static <T, R> Select avg(Field<T, R> field) {
        return new Select(attr(field), Select.Agg.AVG, null);
    }

    public static <T, R> Select min(Field<T, R> field) {
        return new Select(attr(field), Select.Agg.MIN, null);
    }

    public static <T, R> Select max(Field<T, R> field) {
        return new Select(attr(field), Select.Agg.MAX, null);
    }

    /** Give a select item an explicit output alias (result-{@code Row} key / DTO property). */
    public static Select as(Select item, String alias) {
        return new Select(item.path(), item.agg(), alias);
    }

    // --- Predicates (Field shorthands) ---

    public static <T, R> Predicate eq(Field<T, R> field, R value) {
        return eq(attr(field), value);
    }

    public static <T, R> Predicate ne(Field<T, R> field, R value) {
        return new Predicate(attr(field), Predicate.Op.NE, value, null, null);
    }

    public static <T, R> Predicate gt(Field<T, R> field, R value) {
        return new Predicate(attr(field), Predicate.Op.GT, value, null, null);
    }

    public static <T, R> Predicate gte(Field<T, R> field, R value) {
        return new Predicate(attr(field), Predicate.Op.GTE, value, null, null);
    }

    public static <T, R> Predicate lt(Field<T, R> field, R value) {
        return new Predicate(attr(field), Predicate.Op.LT, value, null, null);
    }

    public static <T, R> Predicate lte(Field<T, R> field, R value) {
        return new Predicate(attr(field), Predicate.Op.LTE, value, null, null);
    }

    public static <T, R> Predicate between(Field<T, R> field, R low, R high) {
        return new Predicate(attr(field), Predicate.Op.BETWEEN, low, high, null);
    }

    public static <T> Predicate like(Field<T, String> field, String pattern) {
        return new Predicate(attr(field), Predicate.Op.LIKE, pattern, null, null);
    }

    public static <T, R> Predicate in(Field<T, R> field, List<? extends R> values) {
        return new Predicate(attr(field), Predicate.Op.IN, null, null, values);
    }

    public static <T, R> Predicate isNull(Field<T, R> field) {
        return new Predicate(attr(field), Predicate.Op.IS_NULL, null, null, null);
    }

    public static <T, R> Predicate isNotNull(Field<T, R> field) {
        return new Predicate(attr(field), Predicate.Op.IS_NOT_NULL, null, null, null);
    }

    // --- Predicates (Path forms, for navigation filters) ---

    public static Predicate eq(Path path, Object value) {
        return new Predicate(path, Predicate.Op.EQ, value, null, null);
    }

    public static Predicate ne(Path path, Object value) {
        return new Predicate(path, Predicate.Op.NE, value, null, null);
    }

    public static Predicate between(Path path, Object low, Object high) {
        return new Predicate(path, Predicate.Op.BETWEEN, low, high, null);
    }

    // --- Ordering ---

    public static <T, R> Order asc(Field<T, R> field) {
        return new Order(attr(field), Order.Direction.ASC);
    }

    public static <T, R> Order desc(Field<T, R> field) {
        return new Order(attr(field), Order.Direction.DESC);
    }

    public static Order asc(Path path) {
        return new Order(path, Order.Direction.ASC);
    }

    public static Order desc(Path path) {
        return new Order(path, Order.Direction.DESC);
    }
}
