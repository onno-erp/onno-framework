package su.onno.fields;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A compiler-checked reference to one Java bean property.
 *
 * <p>Use an ordinary getter reference such as {@code Order::getCustomer}. The framework resolves
 * the Java property name once and maps it to the appropriate metadata/API column at the boundary.
 * The declaring entity and value type remain available to Java's type checker.</p>
 */
@FunctionalInterface
public interface Field<E, V> extends Function<E, V>, Serializable {
}
