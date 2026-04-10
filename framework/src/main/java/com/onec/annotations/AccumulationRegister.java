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

    AccumulationType type() default AccumulationType.BALANCE;
}
