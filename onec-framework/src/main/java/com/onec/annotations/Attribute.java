package com.onec.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Attribute {

    String name() default "";

    String displayName() default "";

    /**
     * Former names of this attribute, so the schema upgrader can rename the existing column
     * (keeping its data) instead of adding a fresh empty one. List the old field/attribute
     * name as it was written in Java (e.g. {@code "phone"} after renaming the field to
     * {@code phoneNumber}); it is mapped to a column name through the same naming strategy.
     * Keep at least the most recent former name until every deployment has migrated.
     */
    String[] previousNames() default {};

    int length() default 255;

    boolean required() default false;

    int precision() default 15;

    int scale() default 2;

    // ----- validation constraints (enforced before write, mirrored to the form for inline errors) -----

    /** Minimum allowed value for a numeric attribute. {@code NaN} (default) means no bound. */
    double min() default Double.NaN;

    /** Maximum allowed value for a numeric attribute. {@code NaN} (default) means no bound. */
    double max() default Double.NaN;

    /** Minimum length for a String attribute. {@code 0} (default) means no minimum. ({@link #length} is the max.) */
    int minLength() default 0;

    /** A regex the String value must fully match. Empty (default) means no pattern. */
    String pattern() default "";

    /** Shortcut: the String value must be a valid email address. */
    boolean email() default false;

    /**
     * Marks a String attribute as sensitive (a credential, API key, password, …). A secret
     * attribute is encrypted at rest, never returned in the clear by the generic API (read
     * responses carry a "set / not set" sentinel instead of the value), and rendered as a
     * write-only password control in the UI. Only meaningful for {@code String} attributes;
     * requires {@code onec.security.secret-key} to be configured.
     */
    boolean secret() default false;
}
