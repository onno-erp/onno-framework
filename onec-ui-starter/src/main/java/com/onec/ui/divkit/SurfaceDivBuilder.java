package com.onec.ui.divkit;

import com.onec.ui.ResolvedListView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-surface DivKit <em>content</em> (catalog/document lists, document
 * detail, register report) from the resolved metadata view + data rows. Returns a
 * bare content div — {@link com.onec.ui.DivKitController} wraps it in the app shell.
 * Composed only from native DivKit primitives so it renders on every official SDK
 * with no custom code, keeping a future Flutter client cheap.
 */
public final class SurfaceDivBuilder {

    private SurfaceDivBuilder() {}

    // ----- catalog list -----

    public static Map<String, Object> catalogList(ResolvedListView view, List<Map<String, Object>> rows, Palette p) {
        return listContent(view.title(), "items", null, headerLabels(view), catalogBody(view, rows), p);
    }

    /** The table rows on their own — the payload for a {@code div-patch} of {@code onec-rows}. */
    public static List<Map<String, Object>> catalogRows(ResolvedListView view, List<Map<String, Object>> rows,
                                                        Palette p) {
        return Components.tableItems(headerLabels(view), catalogBody(view, rows), p);
    }

    private static List<Components.Row> catalogBody(ResolvedListView view, List<Map<String, Object>> rows) {
        List<Components.Row> body = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            body.add(new Components.Row(rowCells(view, row), null));
        }
        return body;
    }

    // ----- document list -----

    public static Map<String, Object> documentList(ResolvedListView view, List<Map<String, Object>> rows,
                                                   String routeName, String newUrl, Palette p) {
        return listContent(view.title(), "documents", newUrl, headerLabels(view),
                documentBody(view, rows, routeName), p);
    }

    /** The table rows on their own — the payload for a {@code div-patch} of {@code onec-rows}. */
    public static List<Map<String, Object>> documentRows(ResolvedListView view, List<Map<String, Object>> rows,
                                                         String routeName, Palette p) {
        return Components.tableItems(headerLabels(view), documentBody(view, rows, routeName), p);
    }

    private static List<Components.Row> documentBody(ResolvedListView view, List<Map<String, Object>> rows,
                                                    String routeName) {
        List<Components.Row> body = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String url = "onec://documents/" + routeName + "/" + str(row.get("_id"));
            body.add(new Components.Row(rowCells(view, row), url));
        }
        return body;
    }

    // A list surface: title + a count subtitle bound to the @{onec_count} variable
    // (streamed on data changes) over a table whose rows are patched in place.
    private static Map<String, Object> listContent(String title, String nounPlural, String newUrl,
                                                   List<String> headers, List<Components.Row> body, Palette p) {
        Map<String, Object> titleNode = Div.color(Div.text(title, 22, "bold"), p.text());
        List<Map<String, Object>> topRow = new ArrayList<>(List.of(
                titleNode, Div.weight(Div.horizontal(List.of()), 1)));
        if (newUrl != null) {
            topRow.add(actionPill("New", p.primary(), p.primarySoft(), newUrl));
        }
        Map<String, Object> top = Div.horizontal(topRow);
        Div.matchWidth(top);
        Div.alignV(top, "center");

        Map<String, Object> subtitle = Div.color(Div.text("@{onec_count} " + nounPlural, 13, "regular"), p.muted());
        Div.margins(subtitle, 2, 0, 0, 0);
        Map<String, Object> header = Div.vertical(List.of(top, subtitle));
        Div.margins(header, 0, 0, 16, 0);

        Map<String, Object> rowsStack = Div.id(
                Components.tableStack(Components.tableItems(headers, body, p)), "onec-rows");
        Map<String, Object> table = Components.scrollX(rowsStack, p);
        return content(List.of(header, table));
    }

    /** A small pill button: label, colors, and an {@code onec://} action. */
    private static Map<String, Object> actionPill(String label, String fg, String bg, String url) {
        Map<String, Object> b = Div.text(label, 13, "medium");
        Div.color(b, fg);
        if (bg != null) {
            Div.background(b, bg);
        }
        Div.pad(b, 7, 14);
        Div.corner(b, 8);
        Div.margins(b, 0, 0, 0, 8);
        Div.action(b, "act", url);
        return b;
    }

    // ----- document detail -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row,
                                                     String editUrl, String deleteUrl, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        boolean posted = Boolean.TRUE.equals(row.get("_posted"));
        items.add(detailHeader(str(meta.get("name")) + " " + str(row.get("_number")), posted, editUrl, deleteUrl, p));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        fieldRows.add(Components.fieldRow("Date", str(row.get("_date")), p));
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInDetail")) {
            fieldRows.add(Components.fieldRow(str(a.get("displayName")), cell(a, row), p));
        }
        items.add(Components.card(fieldRows, p));

        for (Map<String, Object> ts : (List<Map<String, Object>>) meta.getOrDefault("tabularSections", List.of())) {
            List<Map<String, Object>> tsAttrs = (List<Map<String, Object>>) ts.getOrDefault("attributes", List.of());
            List<Map<String, Object>> tsRows = (List<Map<String, Object>>) row.getOrDefault(str(ts.get("name")), List.of());

            List<String> headers = new ArrayList<>(List.of("#"));
            for (Map<String, Object> a : tsAttrs) headers.add(str(a.get("displayName")));

            List<Components.Row> body = new ArrayList<>();
            int line = 1;
            for (Map<String, Object> tsRow : tsRows) {
                List<String> cells = new ArrayList<>();
                Object ln = tsRow.get("_line_number");
                cells.add(ln != null ? str(ln) : String.valueOf(line));
                for (Map<String, Object> a : tsAttrs) cells.add(cell(a, tsRow));
                body.add(new Components.Row(cells, null));
                line++;
            }
            items.add(sectionLabel(str(ts.get("name")), p));
            items.add(Components.table(headers, body, p));
        }

        return content(items);
    }

    // ----- document form (create / edit) -----

    /**
     * A create/edit form: one labelled control per {@code visibleInForm} attribute,
     * each bound to an {@code f_<field>} variable, over a submit button whose action
     * the client turns into a POST/PUT. Refs and enums render as {@code div-select};
     * everything else as {@code div-input}. Initial values are seeded via
     * {@link #formVars} into the variables controller.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentForm(Map<String, Object> meta,
                                                   Map<String, List<Map<String, Object>>> refOptions,
                                                   String submitUrl, String submitLabel, String title, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> heading = Div.color(Div.text(title, 22, "bold"), p.text());
        Div.margins(heading, 0, 0, 16, 0);
        items.add(heading);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInForm")) {
            fields.add(formField(a, refOptions, p));
        }
        items.add(Components.card(fields, p));

        Map<String, Object> submit = Div.text(submitLabel, 14, "medium");
        Div.color(submit, p.primary());
        Div.background(submit, p.primarySoft());
        Div.pad(submit, 11, 18);
        Div.corner(submit, 10);
        Div.action(submit, "submit", submitUrl);
        Map<String, Object> submitRow = Div.horizontal(List.of(submit));
        Div.alignH(submitRow, "right");
        Div.margins(submitRow, 8, 0, 0, 0);
        items.add(submitRow);

        return content(items);
    }

    /** Seed values for a form's {@code f_<field>} variables: raw field values, or "" for create. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> formVars(Map<String, Object> meta, Map<String, Object> row) {
        Map<String, Object> vars = new java.util.LinkedHashMap<>();
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInForm")) {
            String value = row == null ? "" : str(row.get(str(a.get("columnName"))));
            vars.put("f_" + str(a.get("fieldName")), value);
        }
        return vars;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formField(Map<String, Object> a,
                                                 Map<String, List<Map<String, Object>>> refOptions, Palette p) {
        String field = str(a.get("fieldName"));
        String var = "f_" + field;
        String javaType = str(a.get("javaType"));
        Map<String, Object> control;

        if (Boolean.TRUE.equals(a.get("isRef"))) {
            List<Map<String, Object>> opts = new ArrayList<>();
            opts.add(Div.option("", "—"));
            opts.addAll(refOptions.getOrDefault(field, List.of()));
            control = Div.select(var, opts);
        } else if (Boolean.TRUE.equals(a.get("isEnum"))) {
            List<Map<String, Object>> opts = new ArrayList<>();
            opts.add(Div.option("", "—"));
            // Enum columns store the value's UUID, so the option value is the id (the
            // server coerces it with UUID.fromString); the label is the readable name.
            for (Map<String, Object> v : (List<Map<String, Object>>) a.getOrDefault("enumValues", List.of())) {
                opts.add(Div.option(str(v.get("id")), str(v.get("name"))));
            }
            control = Div.select(var, opts);
        } else if ("Boolean".equals(javaType)) {
            control = Div.select(var, List.of(Div.option("true", "Yes"), Div.option("false", "No")));
        } else {
            control = Div.input(var, isNumeric(javaType) ? "number" : "single_line_text");
        }

        Div.matchWidth(control);
        Div.background(control, p.page());
        Div.stroke(control, p.border(), 1);
        Div.corner(control, 8);
        Div.pad(control, 10, 12);
        control.put("font_size", 14);
        control.put("text_color", p.text());
        control.put("hint_color", p.faint());

        Map<String, Object> label = Div.color(Div.text(str(a.get("displayName")), 12, "medium"), p.muted());
        Div.margins(label, 0, 0, 4, 0);
        Map<String, Object> wrap = Div.vertical(List.of(label, control));
        Div.matchWidth(wrap);
        Div.margins(wrap, 0, 0, 12, 0);
        return wrap;
    }

    private static boolean isNumeric(String javaType) {
        return switch (javaType) {
            case "BigDecimal", "Integer", "Long", "Double", "Float", "Short", "int", "long", "double" -> true;
            default -> false;
        };
    }

    // ----- register report -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> registerReport(Map<String, Object> meta,
                                                     List<Map<String, Object>> movements,
                                                     List<Map<String, Object>> balances, Palette p) {
        String type = str(meta.get("type"));
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) meta.getOrDefault("dimensions", List.of());
        List<Map<String, Object>> resources = (List<Map<String, Object>>) meta.getOrDefault("resources", List.of());

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Components.pageHeader(str(meta.get("name")),
                "BALANCE".equals(type) ? "Balance register" : "Turnover register", p));

        if ("BALANCE".equals(type) && balances != null) {
            List<String> headers = new ArrayList<>();
            for (Map<String, Object> d : dimensions) headers.add(str(d.get("displayName")));
            for (Map<String, Object> r : resources) headers.add(str(r.get("displayName")));
            List<Components.Row> body = new ArrayList<>();
            for (Map<String, Object> row : balances) {
                List<String> cells = new ArrayList<>();
                for (Map<String, Object> d : dimensions) cells.add(cell(d, row));
                for (Map<String, Object> r : resources) cells.add(cell(r, row));
                body.add(new Components.Row(cells, null));
            }
            items.add(sectionLabel("Balance", p));
            items.add(Components.table(headers, body, p));
        }

        List<String> headers = new ArrayList<>(List.of("Period", "Type"));
        for (Map<String, Object> d : dimensions) headers.add(str(d.get("displayName")));
        for (Map<String, Object> r : resources) headers.add(str(r.get("displayName")));
        List<Components.Row> body = new ArrayList<>();
        for (Map<String, Object> row : movements) {
            List<String> cells = new ArrayList<>();
            cells.add(str(row.get("_period")));
            cells.add(str(row.get("_movement_type")));
            for (Map<String, Object> d : dimensions) cells.add(cell(d, row));
            for (Map<String, Object> r : resources) cells.add(cell(r, row));
            body.add(new Components.Row(cells, null));
        }
        items.add(sectionLabel("Movements", p));
        items.add(Components.table(headers, body, p));

        return content(items);
    }

    // ----- shared helpers -----

    private static Map<String, Object> content(List<Map<String, Object>> items) {
        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onec-content");
        Div.matchWidth(root);
        Div.gap(root, 4);
        return root;
    }

    private static final String DANGER = "#DC2626";

    private static Map<String, Object> detailHeader(String title, boolean posted, String editUrl, String deleteUrl,
                                                    Palette p) {
        Map<String, Object> heading = Div.color(Div.text(title, 22, "bold"), p.text());
        Map<String, Object> spacer = Div.weight(Div.horizontal(List.of()), 1);
        Map<String, Object> badge = Components.statusBadge(posted, posted ? "Posted" : "Draft", p);
        List<Map<String, Object>> rowItems = new ArrayList<>(List.of(heading, spacer, badge));
        if (editUrl != null) {
            rowItems.add(actionPill("Edit", p.primary(), p.primarySoft(), editUrl));
        }
        if (deleteUrl != null) {
            Map<String, Object> del = Div.text("Delete", 13, "medium");
            Div.color(del, DANGER);
            Div.pad(del, 7, 12);
            Div.corner(del, 8);
            Div.stroke(del, DANGER, 1);
            Div.margins(del, 0, 0, 0, 8);
            Div.action(del, "delete", deleteUrl);
            rowItems.add(del);
        }
        Map<String, Object> row = Div.horizontal(rowItems);
        Div.matchWidth(row);
        Div.alignV(row, "center");
        Div.margins(row, 0, 0, 16, 0);
        return row;
    }

    private static Map<String, Object> sectionLabel(String text, Palette p) {
        Map<String, Object> label = Div.color(Div.text(text, 13, "medium"), p.muted());
        Div.margins(label, 16, 0, 8, 2);
        return label;
    }

    private static List<String> headerLabels(ResolvedListView view) {
        return view.columns().stream().map(ResolvedListView.Column::label).toList();
    }

    private static List<String> rowCells(ResolvedListView view, Map<String, Object> row) {
        return view.columns().stream().map(c -> cellByColumn(c.columnName(), row)).toList();
    }

    private static String cellByColumn(String columnName, Map<String, Object> row) {
        if ("_posted".equals(columnName)) {
            return Boolean.TRUE.equals(row.get("_posted")) ? "Posted" : "Draft";
        }
        Object display = row.get(columnName + "_display");
        Object value = display != null ? display : row.get(columnName);
        return value == null ? "" : value.toString();
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
