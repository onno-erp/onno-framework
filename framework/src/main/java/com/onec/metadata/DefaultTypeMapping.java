package com.onec.metadata;

import com.onec.types.Ref;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class DefaultTypeMapping implements TypeMapping {

    @Override
    public String sqlType(Class<?> javaType, int length, int precision, int scale) {
        if (javaType == String.class) {
            return "VARCHAR(" + length + ")";
        } else if (javaType == int.class || javaType == Integer.class) {
            return "INTEGER";
        } else if (javaType == long.class || javaType == Long.class) {
            return "BIGINT";
        } else if (javaType == boolean.class || javaType == Boolean.class) {
            return "BOOLEAN";
        } else if (javaType == BigDecimal.class) {
            return "DECIMAL(" + precision + "," + scale + ")";
        } else if (javaType == UUID.class) {
            return "UUID";
        } else if (javaType == LocalDate.class) {
            return "DATE";
        } else if (javaType == LocalDateTime.class) {
            return "TIMESTAMP";
        } else if (Ref.class.isAssignableFrom(javaType)) {
            return "UUID";
        }
        throw new IllegalArgumentException("Unsupported type: " + javaType.getName());
    }
}
