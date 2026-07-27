package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a grouping/filtering key on an accumulation or information register.
 *
 * <p>An {@link Enumeration}-typed dimension is stored as the enumeration constant's deterministic
 * UUID, just like an enum {@link Attribute}; accumulation-register posting, totals, filters, and
 * typed reads perform that conversion automatically.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Dimension {

    String name() default "";

    String displayName() default "";
}
