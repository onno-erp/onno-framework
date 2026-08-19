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

    /**
     * Human-readable display label, localizable and free of the ASCII/URL-safe constraint that
     * binds {@link #name()}. Falls back to {@code name()} when empty, exactly like the sibling
     * {@code @Catalog}, {@code @Document}, {@code @AccumulationRegister} and {@code @Enumeration}
     * annotations.
     */
    String title() default "";

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";

    /**
     * Period bucket for the register's rows. Each write is floored to this bucket and upserted on
     * {@code (_period, dimensions…)}, so a later write in the same bucket with the same dimensions
     * replaces the earlier one. Choose a bucket at least as fine as the rate at which the modelled
     * fact actually changes — see {@link Periodicity} for the trade-off, and use
     * {@link Periodicity#SECOND} for event logs, audit trails, and status histories.
     */
    Periodicity periodicity() default Periodicity.NONE;

    String context() default "";
}
