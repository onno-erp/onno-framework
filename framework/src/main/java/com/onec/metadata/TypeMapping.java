package com.onec.metadata;

public interface TypeMapping {

    String sqlType(Class<?> javaType, int length, int precision, int scale);
}
