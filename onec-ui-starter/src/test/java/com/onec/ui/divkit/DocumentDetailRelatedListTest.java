package com.onec.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A document detail surface renders each declared related-list panel read-only — the document-side
 * parity with the catalog detail (#110). A booking can surface its clients (the reverse side of a
 * Booking↔Client junction) without entering edit mode, hyperlinked through to each record. A panel
 * that opts out of detail ({@code showInDetail} false) or that the caller omits (e.g. the user
 * can't read the junction) doesn't render, and no add/remove affordances ever appear.
 */
class DocumentDetailRelatedListTest {

    private static final UUID CLIENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** A booking meta carrying one related-list panel ("clients") with a ref column + a plain column. */
    private static Map<String, Object> bookingMeta(boolean showInDetail) {
        Map<String, Object> clientCol = new LinkedHashMap<>();
        clientCol.put("fieldName", "client");
        clientCol.put("columnName", "client");
        clientCol.put("displayName", "Client");
        clientCol.put("isRef", true);
        clientCol.put("refTarget", "Clients");
        clientCol.put("refKind", "catalog");

        Map<String, Object> relationCol = new LinkedHashMap<>();
        relationCol.put("fieldName", "relation");
        relationCol.put("columnName", "relation");
        relationCol.put("displayName", "Relation");
        relationCol.put("isRef", false);

        Map<String, Object> rl = new LinkedHashMap<>();
        rl.put("name", "clients");
        rl.put("label", "Related clients");
        rl.put("joinCatalog", "BookingClient");
        rl.put("sourceKind", "catalog");
        rl.put("viaField", "booking");
        rl.put("displayField", "client");
        rl.put("showInDetail", showInDetail);
        rl.put("columns", List.of(clientCol, relationCol));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "Bookings");
        meta.put("title", "Booking");
        meta.put("attributes", List.of());
        meta.put("tabularSections", List.of());
        meta.put("relatedLists", List.of(rl));
        return meta;
    }

    /** One resolved junction row: a client ref (resolved to a label + a *_ref id) and a relation. */
    private static Map<String, Object> clientRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("client", CLIENT_ID);
        row.put("client_display", "Ada Lovelace");
        row.put("client_ref", Map.of("id", CLIENT_ID.toString(), "type", "Clients", "display", "Ada Lovelace"));
        row.put("relation", "Primary guest");
        return row;
    }

    private static Map<String, Object> bookingRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_number", "B-000001");
        row.put("_date", "2026-06-16");
        row.put("_posted", false);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, String key, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object v = map.get(key);
            if (v != null) out.add(v.toString());
            map.values().forEach(c -> collect(c, key, out));
        } else if (node instanceof List<?> list) {
            list.forEach(c -> collect(c, key, out));
        }
    }

    private static List<String> texts(Map<String, Object> content) {
        List<String> out = new java.util.ArrayList<>();
        collect(content, "text", out);
        return out;
    }

    private static List<String> actionUrls(Map<String, Object> content) {
        List<String> out = new java.util.ArrayList<>();
        collectActionUrls(content, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectActionUrls(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object action = map.get("action");
            if (action instanceof Map<?, ?> am && am.get("url") != null) out.add(am.get("url").toString());
            map.values().forEach(v -> collectActionUrls(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectActionUrls(v, out));
        }
    }

    @Test
    void rendersPanelHeadingAndRowsReadOnly() {
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                bookingMeta(true), bookingRow(), Map.of("clients", List.of(clientRow())),
                List.of(), Palette.of(null));

        // Heading, the resolved client label and the plain relation cell all render.
        assertThat(texts(content)).contains("Related clients", "Ada Lovelace", "Primary guest");
        // The client ref is clickable through to its record; no add/remove controls exist here.
        assertThat(actionUrls(content)).contains("onec://catalogs/clients/" + CLIENT_ID);
    }

    @Test
    void emptyPanelRendersHeadingWithNoRows() {
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                bookingMeta(true), bookingRow(), Map.of("clients", List.of()),
                List.of(), Palette.of(null));

        assertThat(texts(content)).contains("Related clients");
        assertThat(texts(content)).doesNotContain("Ada Lovelace");
    }

    @Test
    void hiddenPanelIsNotRendered() {
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                bookingMeta(false), bookingRow(), Map.of("clients", List.of(clientRow())),
                List.of(), Palette.of(null));

        assertThat(texts(content)).doesNotContain("Related clients", "Ada Lovelace");
    }

    @Test
    void panelAbsentFromRowsMapIsNotRendered() {
        // showInDetail is true, but the caller omitted "clients" (e.g. no read access on the junction).
        Map<String, Object> content = SurfaceDivBuilder.documentDetail(
                bookingMeta(true), bookingRow(), Map.of(), List.of(), Palette.of(null));

        assertThat(texts(content)).doesNotContain("Related clients", "Ada Lovelace");
    }
}
