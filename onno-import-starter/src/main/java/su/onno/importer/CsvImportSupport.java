package su.onno.importer;

import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.TabularSectionDescriptor;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CsvImportSupport {

    private CsvImportSupport() {
    }

    static CSVParser parse(byte[] csv, String charsetName) throws Exception {
        Charset charset = charsetName == null || charsetName.isBlank()
                ? StandardCharsets.UTF_8
                : Charset.forName(charsetName);
        InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(csv), charset);
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(reader);
    }

    static void validateMapping(List<String> headers, Map<String, String> mapping) {
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String header = entry.getValue();
            if (header == null || header.isBlank()) {
                continue;
            }
            if (!headers.contains(header)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Mapped CSV column not found: " + header);
            }
        }
    }

    static Map<String, Object> body(List<String> rawHeaders, Map<String, String> mapping,
                                    org.apache.commons.csv.CSVRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String field = entry.getKey();
            String header = entry.getValue();
            if (field == null || field.isBlank() || header == null || header.isBlank()) {
                continue;
            }
            String value = record.get(rawHeader(rawHeaders, header));
            body.put(field, value == null || value.isBlank() ? null : value);
        }
        return body;
    }

    /**
     * The subset of a mapping that targets document header attributes: keys without a {@code .}
     * separator, e.g. {@code "number"} or {@code "customer"}.
     */
    static Map<String, String> headerMapping(Map<String, String> mapping) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().contains(".")) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * The subset of a mapping that targets tabular-section line fields, keyed by section name.
     * A key {@code "lines.product"} maps field {@code product} of section {@code lines} to its CSV
     * header, producing {@code {"lines": {"product": "<header>"}}}.
     */
    static Map<String, Map<String, String>> tabularMapping(Map<String, String> mapping) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.contains(".")) {
                continue;
            }
            int dot = key.indexOf('.');
            String section = key.substring(0, dot);
            String field = key.substring(dot + 1);
            if (section.isBlank() || field.isBlank()) {
                continue;
            }
            out.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(field, entry.getValue());
        }
        return out;
    }

    /**
     * Builds one tabular-section line from a CSV record using a {@code field -> header} mapping.
     * Returns {@code null} when every mapped cell is blank, so callers can skip empty trailing
     * lines that share a parent's group key.
     */
    static Map<String, Object> tabularRow(List<String> rawHeaders, Map<String, String> fieldMapping,
                                          CSVRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        boolean anyValue = false;
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            String field = entry.getKey();
            String header = entry.getValue();
            if (field == null || field.isBlank() || header == null || header.isBlank()) {
                continue;
            }
            String value = record.get(rawHeader(rawHeaders, header));
            Object coerced = value == null || value.isBlank() ? null : value;
            if (coerced != null) {
                anyValue = true;
            }
            row.put(field, coerced);
        }
        return anyValue ? row : null;
    }

    /**
     * Validates a document mapping, including dotted tabular keys: every CSV column must exist, and
     * every {@code section.field} key must name a real tabular section and attribute on the document.
     */
    static void validateDocumentMapping(List<String> headers, Map<String, String> mapping,
                                        DocumentDescriptor desc) {
        validateMapping(headers, mapping);
        Map<String, Map<String, String>> tabular = tabularMapping(mapping);
        for (Map.Entry<String, Map<String, String>> section : tabular.entrySet()) {
            TabularSectionDescriptor ts = desc.tabularSections().stream()
                    .filter(t -> t.name().equals(section.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown tabular section: " + section.getKey()));
            for (String field : section.getValue().keySet()) {
                boolean known = ts.attributes().stream().anyMatch(a -> a.fieldName().equals(field));
                if (!known) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown field '" + field + "' in tabular section '" + section.getKey() + "'");
                }
            }
        }
    }

    static Map<String, String> rowMap(List<String> rawHeaders, org.apache.commons.csv.CSVRecord record) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String header : rawHeaders) {
            row.put(displayHeader(header), record.get(header));
        }
        return row;
    }

    static List<String> displayHeaders(List<String> rawHeaders) {
        return rawHeaders.stream().map(CsvImportSupport::displayHeader).toList();
    }

    static String displayHeader(String header) {
        return header == null ? null : header.replace("\uFEFF", "");
    }

    static String rawHeader(List<String> rawHeaders, String requested) {
        return rawHeaders.stream()
                .filter(h -> displayHeader(h).equals(requested))
                .findFirst()
                .orElse(requested);
    }

    static String message(Exception e) {
        if (e instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
