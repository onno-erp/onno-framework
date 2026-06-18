package su.onno.annotations;

import su.onno.model.Periodicity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InformationRegister {

    String name();

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";

    Periodicity periodicity() default Periodicity.NONE;

    String context() default "";
}
