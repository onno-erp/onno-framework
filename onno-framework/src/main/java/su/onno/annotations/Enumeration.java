package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Enumeration {

    String name();

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";
}
