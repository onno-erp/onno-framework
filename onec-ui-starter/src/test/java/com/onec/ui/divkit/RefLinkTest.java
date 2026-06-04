package com.onec.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ref attribute in a detail surface renders as a hyperlink: the resolved display name
 * carries an {@code onec://} action that opens the referenced record. A non-ref attribute,
 * or a ref whose value wasn't resolved, stays plain text (no action).
 */
class RefLinkTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static Map<String, Object> attr(String column, String display, boolean isRef,
                                            String refTarget, String refKind) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("columnName", column);
        a.put("displayName", display);
        a.put("visibleInDetail", true);
        a.put("order", 1);
        a.put("isRef", isRef);
        if (refTarget != null) a.put("refTarget", refTarget);
        if (refKind != null) a.put("refKind", refKind);
        return a;
    }

    private static Map<String, Object> docMeta(Map<String, Object>... attrs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "invoice");
        meta.put("title", "Invoice");
        meta.put("attributes", List.of(attrs));
        return meta;
    }

    /** Walk the nested DivKit map, collecting every {@code action.url}. */
    @SuppressWarnings("unchecked")
    private static void collectActionUrls(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object action = map.get("action");
            if (action instanceof Map<?, ?> am && am.get("url") != null) {
                out.add(am.get("url").toString());
            }
            map.values().forEach(v -> collectActionUrls(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectActionUrls(v, out));
        }
    }

    private static List<String> actionUrls(Map<String, Object> content) {
        List<String> out = new java.util.ArrayList<>();
        collectActionUrls(content, out);
        return out;
    }

    @Test
    void resolvedCatalogRefRendersAsLink() {
        Map<String, Object> meta = docMeta(attr("customer", "Customer", true, "Customers", "catalog"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "INV-1");
        row.put("customer", TARGET_ID);
        row.put("customer_display", "Acme Corp");
        row.put("customer_ref", Map.of("id", TARGET_ID.toString(), "type", "Customers", "display", "Acme Corp"));

        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta, row, List.of(), Palette.of(null));

        assertThat(actionUrls(content)).contains("onec://catalogs/customers/" + TARGET_ID);
    }

    @Test
    void resolvedDocumentRefUsesDocumentsRoute() {
        Map<String, Object> meta = docMeta(attr("order", "Order", true, "SalesOrder", "document"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "INV-1");
        row.put("order", TARGET_ID);
        row.put("order_display", "SO-42");
        row.put("order_ref", Map.of("id", TARGET_ID.toString(), "type", "SalesOrder", "display", "SO-42"));

        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta, row, List.of(), Palette.of(null));

        assertThat(actionUrls(content)).contains("onec://documents/sales_order/" + TARGET_ID);
    }

    @Test
    void unresolvedRefStaysPlain() {
        // Ref attribute but no {column}_ref on the row (e.g. value was null / dangling) — no link.
        Map<String, Object> meta = docMeta(attr("customer", "Customer", true, "Customers", "catalog"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "INV-1");

        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta, row, List.of(), Palette.of(null));

        assertThat(actionUrls(content)).noneMatch(u -> u.startsWith("onec://catalogs/customers"));
    }

    @Test
    void nonRefAttributeStaysPlain() {
        Map<String, Object> meta = docMeta(attr("note", "Note", false, null, null));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "INV-1");
        row.put("note", "hello");

        Map<String, Object> content = SurfaceDivBuilder.documentDetail(meta, row, List.of(), Palette.of(null));

        assertThat(actionUrls(content)).isEmpty();
    }
}
