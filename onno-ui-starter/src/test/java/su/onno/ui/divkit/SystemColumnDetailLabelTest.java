package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document detail surface labels its {@code _date} row from the resolved system-column metadata
 * (which folds in a {@code .field("date").label(...)} hint), not a hardcoded English string — so a
 * localized app's detail view reads "Дата" rather than "Date" (#154). With no hint it falls back to
 * the English default.
 */
class SystemColumnDetailLabelTest {

    private static Map<String, Object> systemColumn(String fieldName, String displayName, String columnName) {
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("fieldName", fieldName);
        sc.put("displayName", displayName);
        sc.put("columnName", columnName);
        sc.put("format", "");
        return sc;
    }

    private static Map<String, Object> meta(String dateLabel) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "order");
        meta.put("title", "Order");
        meta.put("attributes", List.of());
        meta.put("systemColumns", List.of(
                systemColumn("number", "Number", "_number"),
                systemColumn("date", dateLabel, "_date"),
                systemColumn("posted", "Status", "_posted")));
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static void collectText(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text instanceof String s) {
                out.add(s);
            }
            map.values().forEach(v -> collectText(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectText(v, out));
        }
    }

    private static List<String> texts(Map<String, Object> content) {
        List<String> out = new ArrayList<>();
        collectText(content, out);
        return out;
    }

    @Test
    void dateRowUsesLocalizedSystemColumnLabel() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "SO-001");
        row.put("_date", "2026-06-18");

        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta("Дата"), row, List.of(), Palette.of(null));

        List<String> texts = texts(content);
        assertThat(texts).contains("Дата");
        assertThat(texts).doesNotContain("Date");
    }

    @Test
    void dateRowFallsBackToEnglishWhenUnset() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "SO-001");
        row.put("_date", "2026-06-18");

        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta("Date"), row, List.of(), Palette.of(null));

        assertThat(texts(content)).contains("Date");
    }
}
