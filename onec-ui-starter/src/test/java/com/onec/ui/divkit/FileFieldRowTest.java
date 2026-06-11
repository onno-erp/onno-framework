package com.onec.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file attribute ({@code .widget("file")}) renders in a detail surface as a tappable chip that
 * opens the stored file ({@code onec://open/...}) — not as a raw URL string. An app-relative media
 * path is re-rooted by the client (leading slash dropped here); an absolute URL travels verbatim.
 * A blank file value renders no chip and no action.
 */
class FileFieldRowTest {

    private static Map<String, Object> fileAttr() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("columnName", "contract");
        a.put("displayName", "Contract");
        a.put("visibleInDetail", true);
        a.put("order", 1);
        a.put("widget", "file");
        return a;
    }

    private static Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "employee");
        meta.put("title", "Employee");
        meta.put("attributes", List.of(fileAttr()));
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object action = map.get("action");
            if (action instanceof Map<?, ?> am && am.get("url") != null) {
                out.add(am.get("url").toString());
            }
            map.values().forEach(v -> collect(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collect(v, out));
        }
    }

    private static List<String> actionUrls(Map<String, Object> content) {
        List<String> out = new ArrayList<>();
        collect(content, out);
        return out;
    }

    @Test
    void mediaPathOpensViaReRootedAction() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("contract", "/api/media/2026/06/abc.pdf");
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta(), row, List.of(), Palette.of(null));
        // Leading slash dropped so the client re-roots it; opens the file, not navigates the SPA.
        assertThat(actionUrls(content)).contains("onec://open/api/media/2026/06/abc.pdf");
    }

    @Test
    void absoluteUrlOpensVerbatim() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("contract", "https://cdn.example.com/files/x.pdf");
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta(), row, List.of(), Palette.of(null));
        assertThat(actionUrls(content)).contains("onec://open/https://cdn.example.com/files/x.pdf");
    }

    @Test
    void blankFileRendersNoAction() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("contract", "");
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta(), row, List.of(), Palette.of(null));
        assertThat(actionUrls(content)).noneMatch(u -> u.startsWith("onec://open/"));
    }
}
