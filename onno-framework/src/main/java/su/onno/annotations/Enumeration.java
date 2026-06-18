package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Enumeration {

    String name();

    /**
     * Human-facing display label for the enumeration type, distinct from the URL-safe
     * {@link #name()} identity. Use it for a localized or multi-word title (e.g.
     * {@code "Статусы заказов"}) while keeping {@code name} ASCII so it stays a stable contract
     * key. Surfaces in the UI metadata. When empty, falls back to {@link #name()}. To label the
     * individual values, annotate the constants with {@link EnumLabel}.
     */
    String title() default "";

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";
}
