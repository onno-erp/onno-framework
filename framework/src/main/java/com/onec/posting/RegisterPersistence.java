package com.onec.posting;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.AttributeDescriptor;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;
import com.onec.model.MovementType;
import com.onec.types.Ref;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class RegisterPersistence<T extends AccumulationRecord> {

    private final Jdbi jdbi;
    private final AccumulationRegisterDescriptor descriptor;

    public RegisterPersistence(Jdbi jdbi, AccumulationRegisterDescriptor descriptor) {
        this.jdbi = jdbi;
        this.descriptor = descriptor;
    }

    public void insertRecords(Handle handle, List<T> records, UUID documentRef, LocalDateTime period) {
        StringBuilder cols = new StringBuilder("_id, _period, _active, _document_ref, _movement_type");
        StringBuilder vals = new StringBuilder(":id, :period, :active, :documentRef, :movementType");

        for (AttributeDescriptor dim : descriptor.dimensions()) {
            cols.append(", ").append(dim.columnName());
            vals.append(", :").append(dim.fieldName());
        }
        for (AttributeDescriptor res : descriptor.resources()) {
            cols.append(", ").append(res.columnName());
            vals.append(", :").append(res.fieldName());
        }

        String sql = "INSERT INTO " + descriptor.tableName() +
                " (" + cols + ") VALUES (" + vals + ")";

        for (T record : records) {
            record.setId(UUID.randomUUID());
            record.setDocumentRef(documentRef);
            record.setPeriod(period);
            record.setActive(true);

            var update = handle.createUpdate(sql)
                    .bind("id", record.getId())
                    .bind("period", record.getPeriod())
                    .bind("active", record.isActive())
                    .bind("documentRef", record.getDocumentRef())
                    .bind("movementType", record.getMovementType().name());

            bindFields(update, record, descriptor.dimensions());
            bindFields(update, record, descriptor.resources());

            update.execute();
        }
    }

    public void deactivateRecords(Handle handle, UUID documentRef) {
        String sql = "UPDATE " + descriptor.tableName() +
                " SET _active = FALSE WHERE _document_ref = :ref AND _active = TRUE";
        handle.createUpdate(sql).bind("ref", documentRef).execute();
    }

    public void updateTotals(Handle handle, List<T> records) {
        if (descriptor.accumulationType() != AccumulationType.BALANCE) return;

        for (T record : records) {
            BigDecimal sign = record.getMovementType() == MovementType.RECEIPT
                    ? BigDecimal.ONE : BigDecimal.ONE.negate();

            // Build WHERE clause for dimension match
            StringBuilder where = new StringBuilder();
            boolean first = true;
            for (AttributeDescriptor dim : descriptor.dimensions()) {
                if (!first) where.append(" AND ");
                where.append(dim.columnName()).append(" = :").append(dim.fieldName());
                first = false;
            }

            // Check if row exists
            String selectSql = "SELECT COUNT(*) FROM " + descriptor.totalsTableName() +
                    " WHERE " + where;
            var selectQuery = handle.createQuery(selectSql);
            bindFields(selectQuery, record, descriptor.dimensions());
            int count = selectQuery.mapTo(Integer.class).one();

            if (count > 0) {
                // UPDATE: add resource values
                StringBuilder updateSets = new StringBuilder();
                first = true;
                for (AttributeDescriptor res : descriptor.resources()) {
                    if (!first) updateSets.append(", ");
                    updateSets.append(res.columnName()).append(" = ").append(res.columnName())
                            .append(" + :").append(res.fieldName());
                    first = false;
                }
                String updateSql = "UPDATE " + descriptor.totalsTableName() +
                        " SET " + updateSets + " WHERE " + where;
                var updateStmt = handle.createUpdate(updateSql);
                bindSignedResources(updateStmt, record, sign);
                bindFields(updateStmt, record, descriptor.dimensions());
                updateStmt.execute();
            } else {
                // INSERT new totals row
                StringBuilder insertCols = new StringBuilder();
                StringBuilder insertVals = new StringBuilder();
                first = true;
                for (AttributeDescriptor dim : descriptor.dimensions()) {
                    if (!first) { insertCols.append(", "); insertVals.append(", "); }
                    insertCols.append(dim.columnName());
                    insertVals.append(":").append(dim.fieldName());
                    first = false;
                }
                for (AttributeDescriptor res : descriptor.resources()) {
                    insertCols.append(", ").append(res.columnName());
                    insertVals.append(", :").append(res.fieldName());
                }
                String insertSql = "INSERT INTO " + descriptor.totalsTableName() +
                        " (" + insertCols + ") VALUES (" + insertVals + ")";
                var insertStmt = handle.createUpdate(insertSql);
                bindFields(insertStmt, record, descriptor.dimensions());
                bindSignedResources(insertStmt, record, sign);
                insertStmt.execute();
            }
        }
    }

    public void reverseTotals(Handle handle, UUID documentRef) {
        if (descriptor.accumulationType() != AccumulationType.BALANCE) return;

        // Read active records for this document, then subtract from totals
        String selectSql = "SELECT * FROM " + descriptor.tableName() +
                " WHERE _document_ref = :ref AND _active = TRUE";
        List<Map<String, Object>> rows = handle.createQuery(selectSql)
                .bind("ref", documentRef)
                .mapToMap()
                .list();

        for (Map<String, Object> row : rows) {
            MovementType mt = MovementType.valueOf((String) row.get("_movement_type"));
            // Reverse: RECEIPT becomes subtract, EXPENSE becomes add
            BigDecimal sign = mt == MovementType.RECEIPT
                    ? BigDecimal.ONE.negate() : BigDecimal.ONE;

            StringBuilder where = new StringBuilder();
            boolean first = true;
            for (AttributeDescriptor dim : descriptor.dimensions()) {
                if (!first) where.append(" AND ");
                where.append(dim.columnName()).append(" = :").append(dim.fieldName());
                first = false;
            }

            StringBuilder updateSets = new StringBuilder();
            first = true;
            for (AttributeDescriptor res : descriptor.resources()) {
                if (!first) updateSets.append(", ");
                updateSets.append(res.columnName()).append(" = ").append(res.columnName())
                        .append(" + :").append(res.fieldName());
                first = false;
            }

            String updateSql = "UPDATE " + descriptor.totalsTableName() +
                    " SET " + updateSets + " WHERE " + where;
            var stmt = handle.createUpdate(updateSql);
            for (AttributeDescriptor dim : descriptor.dimensions()) {
                stmt.bind(dim.fieldName(), row.get(dim.columnName()));
            }
            for (AttributeDescriptor res : descriptor.resources()) {
                BigDecimal val = toBigDecimal(row.get(res.columnName()));
                stmt.bind(res.fieldName(), val.multiply(sign));
            }
            stmt.execute();
        }
    }

    // --- Query methods ---

    public List<Map<String, Object>> getBalance(Map<String, Object> filters) {
        if (descriptor.accumulationType() != AccumulationType.BALANCE) {
            throw new IllegalStateException("getBalance is only available for BALANCE registers");
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM " + descriptor.totalsTableName());
        if (filters != null && !filters.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(filters.keySet().stream()
                    .map(k -> k + " = :" + k)
                    .collect(Collectors.joining(" AND ")));
        }

        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql.toString());
            if (filters != null) {
                filters.forEach(query::bind);
            }
            return query.mapToMap().list();
        });
    }

    public List<Map<String, Object>> getTurnover(LocalDateTime from, LocalDateTime to,
                                                   Map<String, Object> filters) {
        String dimCols = descriptor.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));

        String resSums = descriptor.resources().stream()
                .map(r -> "SUM(CASE WHEN _movement_type = 'RECEIPT' THEN " + r.columnName() +
                        " ELSE -" + r.columnName() + " END) AS " + r.columnName())
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("SELECT " + dimCols + ", " + resSums +
                " FROM " + descriptor.tableName() +
                " WHERE _active = TRUE AND _period >= :from AND _period <= :to");

        if (filters != null) {
            for (String key : filters.keySet()) {
                sql.append(" AND ").append(key).append(" = :").append(key);
            }
        }
        sql.append(" GROUP BY ").append(dimCols);

        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql.toString())
                    .bind("from", from)
                    .bind("to", to);
            if (filters != null) {
                filters.forEach(query::bind);
            }
            return query.mapToMap().list();
        });
    }

    @SuppressWarnings("unchecked")
    public List<T> getRecordsByDocument(UUID documentRef) {
        String sql = "SELECT * FROM " + descriptor.tableName() + " WHERE _document_ref = :ref";
        return jdbi.withHandle(handle ->
                handle.createQuery(sql)
                        .bind("ref", documentRef)
                        .map((rs, ctx) -> {
                            try {
                                T record = (T) descriptor.javaClass().getDeclaredConstructor().newInstance();
                                record.setId(rs.getObject("_id", UUID.class));
                                record.setPeriod(rs.getObject("_period", LocalDateTime.class));
                                record.setActive(rs.getBoolean("_active"));
                                record.setDocumentRef(rs.getObject("_document_ref", UUID.class));
                                record.setMovementType(MovementType.valueOf(rs.getString("_movement_type")));

                                for (AttributeDescriptor dim : descriptor.dimensions()) {
                                    Field field = findField(descriptor.javaClass(), dim.fieldName());
                                    field.setAccessible(true);
                                    field.set(record, rs.getObject(dim.columnName(), field.getType()));
                                }
                                for (AttributeDescriptor res : descriptor.resources()) {
                                    Field field = findField(descriptor.javaClass(), res.fieldName());
                                    field.setAccessible(true);
                                    field.set(record, rs.getObject(res.columnName(), BigDecimal.class));
                                }
                                return record;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to map register record", e);
                            }
                        })
                        .list()
        );
    }

    // --- Helpers ---

    private <S> void bindFields(org.jdbi.v3.core.statement.SqlStatement<?> stmt,
                                 Object record, List<AttributeDescriptor> attrs) {
        for (AttributeDescriptor attr : attrs) {
            try {
                Field field = findField(record.getClass(), attr.fieldName());
                field.setAccessible(true);
                Object value = field.get(record);
                if (attr.isRef() && value instanceof Ref<?> ref) {
                    stmt.bind(attr.fieldName(), ref.id());
                } else {
                    stmt.bind(attr.fieldName(), value);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to read field: " + attr.fieldName(), e);
            }
        }
    }

    private void bindSignedResources(org.jdbi.v3.core.statement.SqlStatement<?> stmt,
                                      T record, BigDecimal sign) {
        for (AttributeDescriptor res : descriptor.resources()) {
            try {
                Field field = findField(record.getClass(), res.fieldName());
                field.setAccessible(true);
                BigDecimal value = (BigDecimal) field.get(record);
                stmt.bind(res.fieldName(), value != null ? value.multiply(sign) : BigDecimal.ZERO);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to read field: " + res.fieldName(), e);
            }
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + name);
    }
}
