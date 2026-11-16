package su.onno.web;

import org.jdbi.v3.core.Handle;
import su.onno.access.AccessSubject;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.model.TabularSectionRow;
import su.onno.security.SecretCipher;
import su.onno.validation.AttributeValidator;
import su.onno.validation.ValidationErrors;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared materialization and persistence of owned catalog/document rows. */
final class TabularSectionWriteSupport {
    private TabularSectionWriteSupport() {}

    @SuppressWarnings("unchecked")
    static void overlay(Object doc, List<TabularSectionDescriptor> sections, WriteLifecycle lifecycle,
                        Map<String, Object> body, ValidationErrors errors) {
        for (TabularSectionDescriptor ts : sections) {
            if (!(body.get(ts.name()) instanceof List<?> rows)) {
                continue;
            }
            List<TabularSectionRow> built = new ArrayList<>();
            for (Object rawRow : rows) {
                if (!(rawRow instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                try {
                    var constructor = ts.rowClass().getDeclaredConstructor();
                    constructor.setAccessible(true);
                    TabularSectionRow rowObj = (TabularSectionRow) constructor.newInstance();
                    lifecycle.applyBody(rowObj, ts.attributes(), (Map<String, Object>) rowMap, errors);
                    built.add(rowObj);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot construct row for tabular section '" + ts.name() + "'", e);
                }
            }
            Field listField = findField(doc.getClass(), ts.fieldName());
            if (listField != null) {
                listField.setAccessible(true);
                try {
                    listField.set(doc, built);
                } catch (IllegalAccessException unreachable) {
                    throw new IllegalStateException(unreachable);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void writeBack(Object doc, List<TabularSectionDescriptor> sections, WriteLifecycle lifecycle,
                          Map<String, Object> body) {
        for (TabularSectionDescriptor ts : sections) {
            if (!(body.get(ts.name()) instanceof List<?> submitted)) {
                continue;
            }
            Field listField = findField(doc.getClass(), ts.fieldName());
            if (listField == null) {
                continue;
            }
            listField.setAccessible(true);
            try {
                if (!(listField.get(doc) instanceof List<?> materialized)) {
                    continue;
                }
                List<Map<String, Object>> rewritten = new ArrayList<>();
                for (int i = 0; i < materialized.size(); i++) {
                    Object rowObject = materialized.get(i);
                    Map<String, Object> rowBody = i < submitted.size()
                            && submitted.get(i) instanceof Map<?, ?> original
                            ? new LinkedHashMap<>((Map<String, Object>) original)
                            : new LinkedHashMap<>();
                    lifecycle.writeBackAll(rowObject, ts.attributes(), rowBody);
                    rewritten.add(rowBody);
                }
                body.put(ts.name(), rewritten);
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException(unreachable);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void validate(List<TabularSectionDescriptor> sections, Map<String, Object> body,
                         ValidationErrors errors) {
        AttributeValidator validator = new AttributeValidator();
        for (var section : sections) {
            if (!body.containsKey(section.name())) continue;
            if (!(body.get(section.name()) instanceof List<?> rows)) {
                errors.field(section.name(), "Expected a list of rows");
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                String path = section.name() + "[" + i + "]";
                if (!(rows.get(i) instanceof Map<?, ?> row)) {
                    errors.field(path, "Expected a row object");
                    continue;
                }
                ValidationErrors rowErrors = new ValidationErrors();
                validator.validate((Map<String, Object>) row, section.attributes(), rowErrors);
                rowErrors.fieldErrors().forEach((field, messages) ->
                        messages.forEach(message -> errors.field(path + "." + field, message)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void requireReadableRefs(List<TabularSectionDescriptor> sections, Map<String, Object> body,
                                    RecordWriteGuard guard, AccessSubject subject) {
        for (var section : sections) {
            if (!(body.get(section.name()) instanceof List<?> rows)) continue;
            for (Object row : rows) {
                if (row instanceof Map<?, ?> values) {
                    guard.requireReadableRefs(section.attributes(), (Map<String, Object>) values, subject);
                }
            }
        }
    }

    static void load(Object entity, List<TabularSectionDescriptor> sections, Handle handle,
                     UUID id, WriteLifecycle lifecycle) {
        for (var section : sections) {
            List<Map<String, Object>> stored = handle.createQuery("SELECT * FROM " + section.tableName()
                            + " WHERE _parent_id = :id ORDER BY _line_number")
                    .bind("id", id).mapToMap().list();
            List<TabularSectionRow> rows = new ArrayList<>();
            try {
                var constructor = section.rowClass().getDeclaredConstructor();
                constructor.setAccessible(true);
                for (var values : stored) {
                    TabularSectionRow row = (TabularSectionRow) constructor.newInstance();
                    row.setId(UUID.fromString(values.get("_id").toString()));
                    row.setLineNumber(((Number) values.get("_line_number")).intValue());
                    for (var attr : section.attributes()) {
                        lifecycle.setFromStored(row, attr, values.get(attr.columnName()));
                    }
                    rows.add(row);
                }
                Field field = findField(entity.getClass(), section.fieldName());
                if (field == null) throw new IllegalStateException("Missing section field " + section.fieldName());
                field.setAccessible(true);
                field.set(entity, rows);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot load section " + section.name(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void replace(Handle handle, List<TabularSectionDescriptor> sections, UUID id,
                        Map<String, Object> body, SecretCipher cipher) {
        for (var section : sections) {
            if (!(body.get(section.name()) instanceof List<?> rows)) continue;
            handle.createUpdate("DELETE FROM " + section.tableName() + " WHERE _parent_id = :id")
                    .bind("id", id).execute();
            List<String> columns = new ArrayList<>(List.of("_id", "_parent_id", "_line_number"));
            for (var attr : section.attributes()) columns.add(attr.columnName());
            String sql = "INSERT INTO " + section.tableName() + " (" + String.join(", ", columns)
                    + ") VALUES (" + columns.stream().map(c -> ":" + c)
                    .collect(java.util.stream.Collectors.joining(", ")) + ")";
            int line = 1;
            for (Object value : rows) {
                Map<String, Object> row = (Map<String, Object>) value;
                var insert = handle.createUpdate(sql).bind("_id", UUID.randomUUID())
                        .bind("_parent_id", id).bind("_line_number", line++);
                for (var attr : section.attributes()) {
                    EntityWriteSupport.bindAttribute(insert, attr, row.get(attr.fieldName()), cipher);
                }
                insert.execute();
            }
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited business fields.
            }
        }
        return null;
    }
}
