package com.onec.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An untyped projection row &mdash; the default result of a join/aggregate query, since a
 * join result is not an entity. Keys are the select items' output aliases; lookups are
 * case-insensitive so callers needn't care that some databases upper-case unquoted
 * column labels. Use {@code QueryBuilder.fetchInto(dto)} when a typed shape is wanted.
 */
public final class Row {

    private final Map<String, Object> values;

    Row(Map<String, Object> source) {
        Map<String, Object> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(source);
        this.values = ci;
    }

    public Object get(String column) {
        return values.get(column);
    }

    public String getString(String column) {
        Object v = values.get(column);
        return v == null ? null : v.toString();
    }

    public UUID getUuid(String column) {
        Object v = values.get(column);
        if (v == null) return null;
        if (v instanceof UUID uuid) return uuid;
        return UUID.fromString(v.toString());
    }

    public BigDecimal getBigDecimal(String column) {
        Object v = values.get(column);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

    public Long getLong(String column) {
        Object v = values.get(column);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    public Integer getInt(String column) {
        Long v = getLong(column);
        return v == null ? null : v.intValue();
    }

    public Boolean getBoolean(String column) {
        Object v = values.get(column);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    public LocalDateTime getDateTime(String column) {
        Object v = values.get(column);
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return LocalDateTime.parse(v.toString());
    }

    public boolean has(String column) {
        return values.containsKey(column);
    }

    public Set<String> columns() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public String toString() {
        return "Row" + values;
    }
}
