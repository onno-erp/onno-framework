package com.onec.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Catalog {

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

    int codeLength() default 9;

    boolean hierarchical() default false;

    boolean autoNumber() default true;

    String codePrefix() default "";

    String context() default "";
}
