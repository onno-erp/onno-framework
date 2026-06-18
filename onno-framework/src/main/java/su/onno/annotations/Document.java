package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Document {

    String name();

    /**
     * Human-facing display label, distinct from the URL-safe {@link #name()} identity.
     * Use it for localized or multi-word titles (e.g. {@code "Заказы поставщикам"}) while
     * keeping {@code name} ASCII and space-free so routes stay clean. Surfaces in the UI
     * metadata and is used for nav items, list/detail headings and tab titles. When empty,
     * falls back to {@link #name()}.
     */
    String title() default "";

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";

    /**
     * Former names of this document (previous {@link #name()} or {@link #tableName()} values),
     * so the schema upgrader can rename the existing tables — the document table and its
     * tabular-section tables, keeping their data — instead of creating fresh empty ones. Each
     * value is tried both through the naming strategy and verbatim as a table name. Keep at
     * least the most recent former name until every deployment has migrated.
     */
    String[] previousNames() default {};

    int numberLength() default 11;

    boolean autoNumber() default true;

    String numberPrefix() default "";

    String context() default "";
}
