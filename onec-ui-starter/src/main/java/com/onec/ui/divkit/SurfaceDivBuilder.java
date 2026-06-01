package com.onec.ui.divkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-surface DivKit cards (catalog/document lists, document detail,
 * register report) from the resolved metadata view + data rows. Everything here
 * is composed from native DivKit primitives (container/text) so it renders on
 * every official SDK with no custom code — keeping a future Flutter client cheap.
 */
public final class SurfaceDivBuilder {

    private SurfaceDivBuilder() {}

    // ----- catalog list -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> catalogList(Map<String, Object> meta, List<Map<String, Object>> rows) {
        List<Map<String, Object>> visible = visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInList");

        List<String> headers = new ArrayList<>(List.of("Code", "Description"));
        for (Map<String, Object> a : visible) headers.add(str(a.get("displayName")));

        List<Map<String, Object>> body = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(str(row.get("_code")));
            cells.add(str(row.get("_description")));
            for (Map<String, Object> a : visible) cells.add(cell(a, row));
            body.add(tableRow(cells, null));
        }

        Map<String, Object> root = Div.vertical(List.of(
                header(str(meta.get("name")), rows.size() + " item(s)"),
                table(headers, body)));
        return DivCard.of("onec-catalog-list", root);
    }

    // ----- document list -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentList(Map<String, Object> meta, List<Map<String, Object>> rows,
                                                   String routeName) {
        List<Map<String, Object>> visible = visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInList");

        List<String> headers = new ArrayList<>(List.of("Number", "Date", "Posted"));
        for (Map<String, Object> a : visible) headers.add(str(a.get("displayName")));

        List<Map<String, Object>> body = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(str(row.get("_number")));
            cells.add(str(row.get("_date")));
            cells.add(Boolean.TRUE.equals(row.get("_posted")) ? "Posted" : "Draft");
            for (Map<String, Object> a : visible) cells.add(cell(a, row));
            String url = "onec://documents/" + routeName + "/" + str(row.get("_id"));
            body.add(tableRow(cells, url));
        }

        Map<String, Object> root = Div.vertical(List.of(
                header(str(meta.get("name")), rows.size() + " document(s)"),
                table(headers, body)));
        return DivCard.of("onec-document-list", root);
    }

    // ----- document detail -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row) {
        List<Map<String, Object>> items = new ArrayList<>();

        boolean posted = Boolean.TRUE.equals(row.get("_posted"));
        items.add(header(str(meta.get("name")) + " " + str(row.get("_number")),
                posted ? "Posted" : "Draft"));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        fieldRows.add(fieldRow("Date", str(row.get("_date"))));
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInDetail")) {
            fieldRows.add(fieldRow(str(a.get("displayName")), cell(a, row)));
        }
        items.add(Div.vertical(fieldRows));

        for (Map<String, Object> ts : (List<Map<String, Object>>) meta.getOrDefault("tabularSections", List.of())) {
            List<Map<String, Object>> tsAttrs = (List<Map<String, Object>>) ts.getOrDefault("attributes", List.of());
            List<Map<String, Object>> tsRows = (List<Map<String, Object>>) row.getOrDefault(str(ts.get("name")), List.of());

            List<String> headers = new ArrayList<>(List.of("#"));
            for (Map<String, Object> a : tsAttrs) headers.add(str(a.get("displayName")));

            List<Map<String, Object>> body = new ArrayList<>();
            int line = 1;
            for (Map<String, Object> tsRow : tsRows) {
                List<String> cells = new ArrayList<>();
                Object ln = tsRow.get("_line_number");
                cells.add(ln != null ? str(ln) : String.valueOf(line));
                for (Map<String, Object> a : tsAttrs) cells.add(cell(a, tsRow));
                body.add(tableRow(cells, null));
                line++;
            }
            items.add(Div.withTextColor(Div.text(str(ts.get("name")), 14, "medium"), "#6B7280"));
            items.add(table(headers, body));
        }

        return DivCard.of("onec-document-detail", Div.vertical(items));
    }

    // ----- register report -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> registerReport(Map<String, Object> meta,
                                                     List<Map<String, Object>> movements,
                                                     List<Map<String, Object>> balances) {
        String type = str(meta.get("type"));
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) meta.getOrDefault("dimensions", List.of());
        List<Map<String, Object>> resources = (List<Map<String, Object>>) meta.getOrDefault("resources", List.of());

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(header(str(meta.get("name")), type));

        if ("BALANCE".equals(type) && balances != null) {
            List<String> headers = new ArrayList<>();
            for (Map<String, Object> d : dimensions) headers.add(str(d.get("displayName")));
            for (Map<String, Object> r : resources) headers.add(str(r.get("displayName")));
            List<Map<String, Object>> body = new ArrayList<>();
            for (Map<String, Object> row : balances) {
                List<String> cells = new ArrayList<>();
                for (Map<String, Object> d : dimensions) cells.add(cell(d, row));
                for (Map<String, Object> r : resources) cells.add(cell(r, row));
                body.add(tableRow(cells, null));
            }
            items.add(Div.withTextColor(Div.text("Balance", 14, "medium"), "#6B7280"));
            items.add(table(headers, body));
        }

        List<String> headers = new ArrayList<>(List.of("Period", "Type"));
        for (Map<String, Object> d : dimensions) headers.add(str(d.get("displayName")));
        for (Map<String, Object> r : resources) headers.add(str(r.get("displayName")));
        List<Map<String, Object>> body = new ArrayList<>();
        for (Map<String, Object> row : movements) {
            List<String> cells = new ArrayList<>();
            cells.add(str(row.get("_period")));
            cells.add(str(row.get("_movement_type")));
            for (Map<String, Object> d : dimensions) cells.add(cell(d, row));
            for (Map<String, Object> r : resources) cells.add(cell(r, row));
            body.add(tableRow(cells, null));
        }
        items.add(Div.withTextColor(Div.text("Movements", 14, "medium"), "#6B7280"));
        items.add(table(headers, body));

        return DivCard.of("onec-register-report", Div.vertical(items));
    }

    // ----- shared helpers -----

    private static Map<String, Object> header(String title, String subtitle) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Div.text(title, 22, "bold"));
        if (subtitle != null && !subtitle.isBlank()) {
            items.add(Div.withTextColor(Div.text(subtitle, 13, "regular"), "#6B7280"));
        }
        return Div.vertical(items);
    }

    private static Map<String, Object> table(List<String> headers, List<Map<String, Object>> rows) {
        List<Map<String, Object>> headerCells = new ArrayList<>();
        for (String h : headers) {
            headerCells.add(Div.weight(Div.withTextColor(Div.text(h, 12, "medium"), "#6B7280"), 1));
        }
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(Div.horizontal(headerCells));
        all.add(Div.separator());
        if (rows.isEmpty()) {
            all.add(Div.withTextColor(Div.text("No data", 13, "regular"), "#9CA3AF"));
        } else {
            all.addAll(rows);
        }
        return Div.vertical(all);
    }

    private static Map<String, Object> tableRow(List<String> cells, String actionUrl) {
        List<Map<String, Object>> cellNodes = new ArrayList<>();
        for (String c : cells) {
            cellNodes.add(Div.weight(Div.text(c, 14, "regular"), 1));
        }
        Map<String, Object> rowNode = Div.horizontal(cellNodes);
        if (actionUrl != null) {
            Div.withAction(rowNode, "open", actionUrl);
        }
        return rowNode;
    }

    private static Map<String, Object> fieldRow(String label, String value) {
        return Div.horizontal(List.of(
                Div.weight(Div.withTextColor(Div.text(label, 13, "regular"), "#6B7280"), 1),
                Div.weight(Div.text(value, 14, "regular"), 2)));
    }

    private static List<Map<String, Object>> visible(List<Map<String, Object>> attrs, String slot) {
        return attrs.stream()
                .filter(a -> Boolean.TRUE.equals(a.get(slot)))
                .sorted(Comparator.comparingInt(a -> a.get("order") == null
                        ? 0 : ((Number) a.get("order")).intValue()))
                .toList();
    }

    private static String cell(Map<String, Object> attr, Map<String, Object> row) {
        String col = str(attr.get("columnName"));
        Object display = row.get(col + "_display");
        Object value = display != null ? display : row.get(col);
        return value == null ? "" : value.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
