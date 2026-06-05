package com.onec.repository;

import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.InformationRegisterDescriptor;
import com.onec.model.InformationRecord;
import com.onec.model.Periodicity;
import com.onec.types.Ref;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

public class InformationRegisterPersistence<T extends InformationRecord> {

    private final Jdbi jdbi;
    private final InformationRegisterDescriptor descriptor;
    private final Class<T> recordClass;

    @SuppressWarnings("unchecked")
    public InformationRegisterPersistence(Jdbi jdbi, InformationRegisterDescriptor descriptor) {
        this.jdbi = jdbi;
        this.descriptor = descriptor;
        this.recordClass = (Class<T>) descriptor.javaClass();
    }

    public void write(T record) {
        record.setId(UUID.randomUUID());
        if (descriptor.periodicity() != Periodicity.NONE && record.getPeriod() != null) {
            record.setPeriod(truncatePeriod(record.getPeriod(), descriptor.periodicity()));
        }

        // Build dimension key columns for ON CONFLICT
        List<String> keyCols = new ArrayList<>();
        if (descriptor.periodicity() != Periodicity.NONE) {
            keyCols.add("_period");
        }
        for (AttributeDescriptor dim : descriptor.dimensions()) {
            keyCols.add(dim.columnName());
        }

        // Build column lists
        StringBuilder cols = new StringBuilder("_id");
        StringBuilder vals = new StringBuilder(":id");

        if (descriptor.periodicity() != Periodicity.NONE) {
            cols.append(", _period");
            vals.append(", :period");
        }

        List<AttributeDescriptor> allFields = new ArrayList<>();
        allFields.addAll(descriptor.dimensions());
        allFields.addAll(descriptor.resources());
        allFields.addAll(descriptor.attributes());

        for (AttributeDescriptor attr : allFields) {
            cols.append(", ").append(attr.columnName());
            vals.append(", :").append(attr.fieldName());
        }

        // Build update clause for resources + attributes
        List<AttributeDescriptor> updateFields = new ArrayList<>();
        updateFields.addAll(descriptor.resources());
        updateFields.addAll(descriptor.attributes());

        if (keyCols.isEmpty()) {
            // No key — just insert
            String sql = "INSERT INTO " + descriptor.tableName() +
                    " (" + cols + ") VALUES (" + vals + ")";
            jdbi.useHandle(handle -> {
                Update update = handle.createUpdate(sql)
                        .bind("id", record.getId());
                if (descriptor.periodicity() != Periodicity.NONE) {
                    update.bind("period", record.getPeriod());
                }
                bindFields(update, record, allFields);
                update.execute();
            });
        } else {
            // MERGE for upsert
            String updateClause = updateFields.stream()
                    .map(a -> a.columnName() + " = :" + a.fieldName())
                    .collect(Collectors.joining(", "));

            // Use MERGE INTO for H2 compatibility
            String keyStr = String.join(", ", keyCols);

            // Delete existing + insert (portable upsert)
            jdbi.useTransaction(handle -> {
                // Delete by key
                StringBuilder where = new StringBuilder();
                for (int i = 0; i < keyCols.size(); i++) {
                    if (i > 0) where.append(" AND ");
                    String col = keyCols.get(i);
                    if (col.equals("_period")) {
                        where.append("_period = :period");
                    } else {
                        // find field name for this column
                        String fieldName = descriptor.dimensions().stream()
                                .filter(d -> d.columnName().equals(col))
                                .map(AttributeDescriptor::fieldName)
                                .findFirst().orElse(col);
                        where.append(col).append(" = :").append(fieldName);
                    }
                }

                String deleteSql = "DELETE FROM " + descriptor.tableName() + " WHERE " + where;
                Update deleteUpdate = handle.createUpdate(deleteSql);
                if (descriptor.periodicity() != Periodicity.NONE) {
                    deleteUpdate.bind("period", record.getPeriod());
                }
                bindFields(deleteUpdate, record, descriptor.dimensions());
                deleteUpdate.execute();

                // Insert
                String insertSql = "INSERT INTO " + descriptor.tableName() +
                        " (" + cols + ") VALUES (" + vals + ")";
                Update insertUpdate = handle.createUpdate(insertSql)
                        .bind("id", record.getId());
                if (descriptor.periodicity() != Periodicity.NONE) {
                    insertUpdate.bind("period", record.getPeriod());
                }
                bindFields(insertUpdate, record, allFields);
                insertUpdate.execute();
            });
        }
    }

    public List<T> getSliceLast(LocalDateTime date, Map<String, Object> filters) {
        if (descriptor.periodicity() == Periodicity.NONE) {
            return getRecords(filters);
        }

        String dimCols = descriptor.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));

        String sql = "SELECT t.* FROM " + descriptor.tableName() + " t " +
                "INNER JOIN (SELECT " + dimCols + ", MAX(_period) as max_period " +
                "FROM " + descriptor.tableName() + " WHERE _period <= :date " +
                buildWhereClause(filters, "AND ") +
                "GROUP BY " + dimCols + ") sub ON " +
                descriptor.dimensions().stream()
                        .map(d -> "t." + d.columnName() + " = sub." + d.columnName())
                        .collect(Collectors.joining(" AND ")) +
                " AND t._period = sub.max_period";

        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql).bind("date", date);
            bindFilterParams(query, filters);
            return query.map((rs, ctx) -> mapRecord(rs)).list();
        });
    }

    public List<T> getSliceFirst(LocalDateTime date, Map<String, Object> filters) {
        if (descriptor.periodicity() == Periodicity.NONE) {
            return getRecords(filters);
        }

        String dimCols = descriptor.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));

        String sql = "SELECT t.* FROM " + descriptor.tableName() + " t " +
                "INNER JOIN (SELECT " + dimCols + ", MIN(_period) as min_period " +
                "FROM " + descriptor.tableName() + " WHERE _period >= :date " +
                buildWhereClause(filters, "AND ") +
                "GROUP BY " + dimCols + ") sub ON " +
                descriptor.dimensions().stream()
                        .map(d -> "t." + d.columnName() + " = sub." + d.columnName())
                        .collect(Collectors.joining(" AND ")) +
                " AND t._period = sub.min_period";

        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql).bind("date", date);
            bindFilterParams(query, filters);
            return query.map((rs, ctx) -> mapRecord(rs)).list();
        });
    }

    public List<T> getRecords(Map<String, Object> filters) {
        String sql = "SELECT * FROM " + descriptor.tableName();
        String where = buildWhereClause(filters, "WHERE ");
        sql += where;

        String finalSql = sql;
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(finalSql);
            bindFilterParams(query, filters);
            return query.map((rs, ctx) -> mapRecord(rs)).list();
        });
    }

    public void delete(T record) {
        List<String> conditions = new ArrayList<>();
        if (descriptor.periodicity() != Periodicity.NONE) {
            conditions.add("_period = :period");
        }
        for (AttributeDescriptor dim : descriptor.dimensions()) {
            conditions.add(dim.columnName() + " = :" + dim.fieldName());
        }

        String sql = "DELETE FROM " + descriptor.tableName() + " WHERE " +
                String.join(" AND ", conditions);

        jdbi.useHandle(handle -> {
            Update update = handle.createUpdate(sql);
            if (descriptor.periodicity() != Periodicity.NONE) {
                update.bind("period", record.getPeriod());
            }
            bindFields(update, record, descriptor.dimensions());
            update.execute();
        });
    }

    private String buildWhereClause(Map<String, Object> filters, String prefix) {
        if (filters == null || filters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(prefix);
        boolean first = true;
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            if (!first) sb.append(" AND ");
            sb.append(entry.getKey()).append(" = :filter_").append(entry.getKey());
            first = false;
        }
        return sb.toString();
    }

    private void bindFilterParams(org.jdbi.v3.core.statement.SqlStatement<?> stmt, Map<String, Object> filters) {
        if (filters == null) return;
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Ref<?> ref) {
                stmt.bind("filter_" + entry.getKey(), ref.id());
            } else {
                stmt.bind("filter_" + entry.getKey(), value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private T mapRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            T record = recordClass.getDeclaredConstructor().newInstance();
            record.setId(UUID.fromString(rs.getString("_id")));
            if (descriptor.periodicity() != Periodicity.NONE) {
                var ts = rs.getTimestamp("_period");
                if (ts != null) record.setPeriod(ts.toLocalDateTime());
            }

            List<AttributeDescriptor> allFields = new ArrayList<>();
            allFields.addAll(descriptor.dimensions());
            allFields.addAll(descriptor.resources());
            allFields.addAll(descriptor.attributes());

            for (AttributeDescriptor attr : allFields) {
                Field field = findField(recordClass, attr.fieldName());
                field.setAccessible(true);
                Object value = getFieldValue(rs, attr);
                if (value != null) {
                    field.set(record, value);
                }
            }

            return record;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to map information register record", e);
        }
    }

    private Object getFieldValue(java.sql.ResultSet rs, AttributeDescriptor attr) throws java.sql.SQLException {
        Class<?> type = attr.javaType();
        String col = attr.columnName();

        if (type == UUID.class) {
            String val = rs.getString(col);
            return val != null ? UUID.fromString(val) : null;
        } else if (type == String.class) {
            return rs.getString(col);
        } else if (type == java.math.BigDecimal.class) {
            return rs.getBigDecimal(col);
        } else if (type == int.class || type == Integer.class) {
            return rs.getInt(col);
        } else if (type == long.class || type == Long.class) {
            return rs.getLong(col);
        } else if (type == boolean.class || type == Boolean.class) {
            return rs.getBoolean(col);
        } else if (type.isEnum()) {
            // Enum attributes are stored as their stable UUID — resolve back to the constant
            // rather than leaking the raw UUID through the getObject(...) fallback.
            String val = rs.getString(col);
            return val != null ? EnumerationPersistence.resolveValue(type, UUID.fromString(val)) : null;
        } else if (Ref.class.isAssignableFrom(type)) {
            String val = rs.getString(col);
            return val != null ? new Ref<>(Object.class, UUID.fromString(val)) : null;
        }
        return rs.getObject(col);
    }

    private void bindFields(org.jdbi.v3.core.statement.SqlStatement<?> stmt, T record,
                            List<AttributeDescriptor> fields) {
        for (AttributeDescriptor attr : fields) {
            try {
                Field field = findField(recordClass, attr.fieldName());
                field.setAccessible(true);
                Object value = field.get(record);

                if (value instanceof Ref<?> ref) {
                    stmt.bind(attr.fieldName(), ref.id());
                } else {
                    stmt.bind(attr.fieldName(), value);
                }
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to bind field " + attr.fieldName(), e);
            }
        }
    }

    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }

    static LocalDateTime truncatePeriod(LocalDateTime dt, Periodicity periodicity) {
        return switch (periodicity) {
            case DAY -> dt.toLocalDate().atStartOfDay();
            case MONTH -> dt.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case QUARTER -> {
                int quarter = dt.get(IsoFields.QUARTER_OF_YEAR);
                int month = (quarter - 1) * 3 + 1;
                yield dt.toLocalDate().withMonth(month).withDayOfMonth(1).atStartOfDay();
            }
            case YEAR -> dt.toLocalDate().withDayOfYear(1).atStartOfDay();
            case NONE -> dt;
        };
    }
}
