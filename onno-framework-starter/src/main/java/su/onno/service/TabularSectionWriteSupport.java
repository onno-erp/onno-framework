package su.onno.service;

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

/** Request-body checks for the tabular sections of a generic catalog/document write. */
final class TabularSectionWriteSupport {
    private TabularSectionWriteSupport() {}

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
}
