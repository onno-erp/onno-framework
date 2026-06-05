package com.onec.query;

import com.onec.repository.EnumerationPersistence;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Maps a {@link Row} onto a typed DTO for {@code fetchInto(...)}. Two shapes are
 * supported: a {@code record} (matched by canonical-constructor component names) and a
 * mutable POJO (no-arg constructor, fields set by name). Matching is by output-alias
 * name and is case-insensitive, mirroring {@link Row}; values are lightly coerced to the
 * target type so SQL numerics/UUID/timestamps land in the expected Java types.
 */
final class RowMapper {

    private RowMapper() {
    }

    static <D> D map(Row row, Class<D> type) {
        if (type.isRecord()) {
            return mapRecord(row, type);
        }
        return mapPojo(row, type);
    }

    @SuppressWarnings("unchecked")
    private static <D> D mapRecord(Row row, Class<D> type) {
        try {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                paramTypes[i] = components[i].getType();
                args[i] = coerce(row.get(components[i].getName()), components[i].getType());
            }
            Constructor<?> ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return (D) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to map row into record " + type.getName(), e);
        }
    }

    private static <D> D mapPojo(Row row, Class<D> type) {
        try {
            Constructor<D> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            D instance = ctor.newInstance();
            Class<?> current = type;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (!row.has(field.getName())) continue;
                    field.setAccessible(true);
                    field.set(instance, coerce(row.get(field.getName()), field.getType()));
                }
                current = current.getSuperclass();
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to map row into " + type.getName(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object coerce(Object value, Class<?> target) {
        if (value == null) {
            return defaultFor(target);
        }
        if (target.isInstance(value)) {
            return value;
        }
        if (target == String.class) {
            return value.toString();
        }
        if (target == UUID.class) {
            return value instanceof UUID u ? u : UUID.fromString(value.toString());
        }
        if (target == BigDecimal.class) {
            return value instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(value.toString());
        }
        if (target == Integer.class || target == int.class) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        }
        if (target == Long.class || target == long.class) {
            return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
        }
        if (target == Double.class || target == double.class) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
        }
        if (target == Boolean.class || target == boolean.class) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
        }
        if (target == LocalDateTime.class) {
            if (value instanceof LocalDateTime ldt) return ldt;
            if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
            // H2 renders a TIMESTAMP as "2026-06-04 08:44:44.4" (space, not 'T'), which a
            // strict ISO LocalDateTime.parse rejects at index 10 — normalise the separator.
            return LocalDateTime.parse(value.toString().replace(' ', 'T'));
        }
        if (target.isEnum()) {
            // Enum attributes are stored as their stable UUID (SchemaGenerator maps enums to a UUID
            // column; EnumerationPersistence does the enum<->UUID mapping), so resolve from the id
            // rather than treating the value as a constant name. A driver may hand the UUID back as
            // a string; only a genuinely non-UUID string falls back to by-name lookup.
            if (value instanceof UUID uuid) {
                return EnumerationPersistence.resolveValue(target, uuid);
            }
            try {
                return EnumerationPersistence.resolveValue(target, UUID.fromString(value.toString()));
            } catch (IllegalArgumentException notAStoredEnumId) {
                return Enum.valueOf((Class<? extends Enum>) target, value.toString());
            }
        }
        return value;
    }

    private static Object defaultFor(Class<?> target) {
        if (!target.isPrimitive()) return null;
        if (target == boolean.class) return false;
        if (target == int.class) return 0;
        if (target == long.class) return 0L;
        if (target == double.class) return 0d;
        if (target == float.class) return 0f;
        if (target == short.class) return (short) 0;
        if (target == byte.class) return (byte) 0;
        if (target == char.class) return '\0';
        return null;
    }
}
