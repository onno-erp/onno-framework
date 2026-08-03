package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.TabularSectionDescriptor;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Accepts the legacy storage vocabulary at write boundaries without weakening conflict handling. */
final class EntityWriteAliases {

    private EntityWriteAliases() {
    }

    static Map<String, Object> catalog(CatalogDescriptor descriptor, Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        alias(body, "code", "_code");
        alias(body, "description", "_description");
        alias(body, "folder", "_is_folder");
        alias(body, "parent", "_parent");
        alias(body, "version", "_version");
        attributes(body, descriptor.attributes());
        return body;
    }

    static Map<String, Object> document(DocumentDescriptor descriptor, Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        alias(body, "number", "_number");
        alias(body, "date", "_date");
        alias(body, "version", "_version");
        attributes(body, descriptor.attributes());
        for (TabularSectionDescriptor section : descriptor.tabularSections()) {
            if (!(body.get(section.name()) instanceof List<?> rows)) {
                continue;
            }
            List<Object> normalizedRows = new ArrayList<>(rows.size());
            for (Object value : rows) {
                if (value instanceof Map<?, ?> row) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    row.forEach((key, rowValue) -> normalized.put(String.valueOf(key), rowValue));
                    attributes(normalized, section.attributes());
                    normalizedRows.add(normalized);
                } else {
                    normalizedRows.add(value);
                }
            }
            body.put(section.name(), normalizedRows);
        }
        return body;
    }

    private static void attributes(Map<String, Object> body, List<AttributeDescriptor> attributes) {
        for (AttributeDescriptor attribute : attributes) {
            alias(body, attribute.fieldName(), attribute.columnName());
        }
    }

    private static void alias(Map<String, Object> body, String canonical, String legacy) {
        if (canonical.equals(legacy) || !body.containsKey(legacy)) {
            return;
        }
        if (body.containsKey(canonical) && !Objects.deepEquals(body.get(canonical), body.get(legacy))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Conflicting values for '" + canonical + "' and storage alias '" + legacy + "'");
        }
        body.putIfAbsent(canonical, body.get(legacy));
        body.remove(legacy);
    }
}
