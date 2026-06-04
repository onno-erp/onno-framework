package com.onec.annotations;

import com.onec.model.AccumulationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AccumulationRegister {

    String name();

    /**
     * Human-facing display label, distinct from the URL-safe {@link #name()} identity.
     * Use it for localized or multi-word titles while keeping {@code name} ASCII and
     * space-free so routes stay clean. Surfaces in the UI metadata and is used for nav
     * items and report headings. When empty, falls back to {@link #name()}.
     */
    String title() default "";

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";

    AccumulationType type() default AccumulationType.BALANCE;

    String context() default "";
}
