package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A catalog detail surface renders each declared related-list panel read-only: a heading plus a
 * row per preloaded join row, with refs hyperlinked through to the target — the catalog-side
 * analogue of a document's tabular section. A panel that opts out of the detail view
 * ({@code showInDetail} false) or that the caller omits (e.g. the user can't read its join
 * catalog) doesn't render, and no add/remove affordances ever appear.
 */
class CatalogDetailRelatedListTest {

    private static final UUID DOCTOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** A clinic meta carrying one related-list panel ("doctors") with a ref column + a plain column. */
    private static Map<String, Object> clinicMeta(boolean showInDetail) {
        Map<String, Object> doctorCol = new LinkedHashMap<>();
        doctorCol.put("fieldName", "doctor");
        doctorCol.put("columnName", "doctor");
        doctorCol.put("displayName", "Doctor");
        doctorCol.put("isRef", true);
        doctorCol.put("refTarget", "Doctors");
        doctorCol.put("refKind", "catalog");

        Map<String, Object> roleCol = new LinkedHashMap<>();
        roleCol.put("fieldName", "role");
        roleCol.put("columnName", "role");
        roleCol.put("displayName", "Role");
        roleCol.put("isRef", false);

        Map<String, Object> rl = new LinkedHashMap<>();
        rl.put("name", "doctors");
        rl.put("label", "Doctors");
        rl.put("joinCatalog", "ClinicDoctor");
        rl.put("viaField", "clinic");
        rl.put("displayField", "doctor");
        rl.put("showInDetail", showInDetail);
        rl.put("columns", List.of(doctorCol, roleCol));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "clinic");
        meta.put("title", "Clinic");
        meta.put("attributes", List.of());
        meta.put("relatedLists", List.of(rl));
        return meta;
    }

    /** One resolved join row: a doctor ref (resolved to a label + a *_ref id) and a role string. */
    private static Map<String, Object> doctorRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("doctor", DOCTOR_ID);
        row.put("doctor_display", "Dr. House");
        row.put("doctor_ref", Map.of("id", DOCTOR_ID.toString(), "type", "Doctors", "display", "Dr. House"));
        row.put("role", "Cardiology");
        return row;
    }

    private static Map<String, Object> clinicRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_description", "Downtown Clinic");
        row.put("_code", "C-001");
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
        Map<String, Object> content = SurfaceDivBuilder.catalogDetail(
                clinicMeta(true), clinicRow(), Map.of("doctors", List.of(doctorRow())),
                List.of(), Palette.of(null));

        // Heading, the resolved doctor label and the plain role cell all render.
        assertThat(texts(content)).contains("Doctors", "Dr. House", "Cardiology");
        // The doctor ref is clickable through to its record; no add/remove controls exist here.
        assertThat(actionUrls(content)).contains("onno://catalogs/doctors/" + DOCTOR_ID);
    }

    @Test
    void emptyPanelRendersHeadingWithNoRows() {
        Map<String, Object> content = SurfaceDivBuilder.catalogDetail(
                clinicMeta(true), clinicRow(), Map.of("doctors", List.of()),
                List.of(), Palette.of(null));

        assertThat(texts(content)).contains("Doctors");
        assertThat(texts(content)).doesNotContain("Dr. House");
    }

    @Test
    void hiddenPanelIsNotRendered() {
        Map<String, Object> content = SurfaceDivBuilder.catalogDetail(
                clinicMeta(false), clinicRow(), Map.of("doctors", List.of(doctorRow())),
                List.of(), Palette.of(null));

        assertThat(texts(content)).doesNotContain("Doctors", "Dr. House");
    }

    @Test
    void panelAbsentFromRowsMapIsNotRendered() {
        // showInDetail is true, but the caller omitted "doctors" (e.g. no read access on the join).
        Map<String, Object> content = SurfaceDivBuilder.catalogDetail(
                clinicMeta(true), clinicRow(), Map.of(), List.of(), Palette.of(null));

        assertThat(texts(content)).doesNotContain("Doctors", "Dr. House");
    }
}
