package com.onec.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Attribute {

    String name() default "";

    int length() default 255;

    boolean required() default false;

    int precision() default 15;

    int scale() default 2;
}
