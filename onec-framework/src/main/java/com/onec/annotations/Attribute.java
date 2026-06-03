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

    int length() default 255;

    boolean required() default false;

    int precision() default 15;

    int scale() default 2;

    /**
     * Marks a String attribute as sensitive (a credential, API key, password, …). A secret
     * attribute is encrypted at rest, never returned in the clear by the generic API (read
     * responses carry a "set / not set" sentinel instead of the value), and rendered as a
     * write-only password control in the UI. Only meaningful for {@code String} attributes;
     * requires {@code onec.security.secret-key} to be configured.
     */
    boolean secret() default false;
}
