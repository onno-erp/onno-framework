package su.onno.query;

import java.util.List;

/**
 * A column reference within a query, expressed as a chain of bean-property hops on
 * the query's {@code from} entity.
 *
 * <ul>
 *   <li>A single segment is a direct attribute &mdash; {@code SalesOrder::getNumber}
 *       &rarr; {@code ["number"]}.</li>
 *   <li>Multiple segments are <em>reference navigation</em>: every segment but the
 *       last must be a {@code Ref<T>} attribute, and the join-walker emits a
 *       {@code LEFT JOIN} per hop &mdash;
 *       {@code SalesOrder -> customer -> Customer::getName} &rarr;
 *       {@code ["customer", "name"]}.</li>
 * </ul>
 *
 * <p>{@code root} is the entity the path is rooted on. The fluent builder fills it in
 * from {@code Query.from(...)}, so DSL helpers can leave it {@code null}; a
 * hand-authored {@link QuerySpec} should set it explicitly.
 */
public record Path(Class<?> root, List<String> segments) {

    public Path {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("A path needs at least one segment");
        }
        segments = List.copyOf(segments);
    }

    public static Path of(Class<?> root, String... segments) {
        return new Path(root, List.of(segments));
    }

    /** Terminal segment &mdash; the attribute the path ultimately reads. */
    public String tail() {
        return segments.get(segments.size() - 1);
    }

    /** True when this path crosses at least one {@code Ref} hop. */
    public boolean isNavigation() {
        return segments.size() > 1;
    }

    /** Returns a copy rooted at {@code newRoot}; used by the builder to bind DSL paths. */
    Path withRoot(Class<?> newRoot) {
        return root == newRoot ? this : new Path(newRoot, segments);
    }
}
