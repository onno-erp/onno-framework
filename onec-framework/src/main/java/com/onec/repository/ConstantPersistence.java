package com.onec.repository;

import com.onec.metadata.ConstantDescriptor;
import com.onec.schema.SqlDialect;

import org.jdbi.v3.core.Jdbi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ConstantPersistence {

    private final Jdbi jdbi;

    public ConstantPersistence(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public String getRaw(ConstantDescriptor descriptor) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT _value FROM constants WHERE _name = :name")
                        .bind("name", descriptor.logicalName())
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null));
    }

    public void setRaw(ConstantDescriptor descriptor, String value) {
        jdbi.useHandle(handle -> {
            String sql = SqlDialect.detect(handle.getConnection()).upsert(
                    "constants",
                    List.of("_name", "_value"),
                    List.of("_name"),
                    List.of(":name", ":value"));
            handle.createUpdate(sql)
                    .bind("name", descriptor.logicalName())
                    .bind("value", value)
                    .execute();
        });
    }

    public Object get(ConstantDescriptor descriptor) {
        String raw = getRaw(descriptor);
        if (raw == null) return null;
        return deserialize(raw, descriptor.valueType());
    }

    public void set(ConstantDescriptor descriptor, Object value) {
        setRaw(descriptor, value != null ? serialize(value) : null);
    }

    private static String serialize(Object value) {
        return value.toString();
    }

    private static Object deserialize(String raw, Class<?> type) {
        if (type == String.class) return raw;
        if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
        if (type == long.class || type == Long.class) return Long.parseLong(raw);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == BigDecimal.class) return new BigDecimal(raw);
        if (type == UUID.class) return UUID.fromString(raw);
        if (type == LocalDate.class) return LocalDate.parse(raw);
        if (type == LocalDateTime.class) return LocalDateTime.parse(raw);
        throw new IllegalArgumentException("Unsupported constant type: " + type.getName());
    }
}
