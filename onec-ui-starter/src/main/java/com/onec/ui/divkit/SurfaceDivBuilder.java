package com.onec.ui.divkit;

import com.onec.ui.ResolvedListView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    public static Map<String, Object> catalogList(ResolvedListView view, List<Map<String, Object>> rows,
                                                  String routeName, String newUrl, Palette p) {
        return listContent(view.title(), "items", newUrl, headerLabels(view),
                catalogBody(view, rows, routeName), p);
    }

    /**
     * The rows stack as a {@code div-patch} of {@code onec-rows} — a single replacement
     * node re-carrying that id (see {@link #rowsPatch}).
     */
    public static List<Map<String, Object>> catalogRows(ResolvedListView view, List<Map<String, Object>> rows,
                                                        String routeName, Palette p) {
        return rowsPatch(headerLabels(view), catalogBody(view, rows, routeName), p);
    }

    private static List<Components.Row> catalogBody(ResolvedListView view, List<Map<String, Object>> rows,
                                                   String routeName) {
        List<Components.Row> body = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String url = "onec://catalogs/" + routeName + "/" + str(row.get("_id"));
            body.add(new Components.Row(rowCells(view, row), url));
        }
        return body;
    }

    // ----- document list -----

    public static Map<String, Object> documentList(ResolvedListView view, List<Map<String, Object>> rows,
                                                   String routeName, String newUrl, Palette p) {
        return listContent(view.title(), "documents", newUrl, headerLabels(view),
                documentBody(view, rows, routeName), p);
    }

    /**
     * The rows stack as a {@code div-patch} of {@code onec-rows} — a single replacement
     * node re-carrying that id (see {@link #rowsPatch}).
     */
    public static List<Map<String, Object>> documentRows(ResolvedListView view, List<Map<String, Object>> rows,
                                                         String routeName, Palette p) {
        return rowsPatch(headerLabels(view), documentBody(view, rows, routeName), p);
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
        Div.maxLines(titleNode, 1);
        List<Map<String, Object>> topRow = new ArrayList<>(List.of(
                Div.weight(titleNode, 1)));
        if (newUrl != null) {
            topRow.add(Components.actionButton("plus", "New", p.primary(), p.primarySoft(), null, newUrl, "new"));
        }
        Map<String, Object> top = Div.horizontal(topRow);
        Div.matchWidth(top);
        Div.alignV(top, "center");

        Map<String, Object> subtitle = Div.color(Div.text("@{onec_count} " + nounPlural, 13, "regular"), p.muted());
        Div.margins(subtitle, 2, 0, 0, 0);
        Map<String, Object> header = Div.vertical(List.of(top, subtitle));
        Div.margins(header, 0, 0, 16, 0);

        Map<String, Object> table = Components.scrollX(rowsStack(headers, body, p), p);
        return content(List.of(header, table));
    }

    /**
     * The header+rows stack carrying the {@code onec-rows} id. The scroll gallery holds
     * exactly this one child, so a live update must replace it with a single node that
     * <em>re-carries</em> the id — not splice in the bare row list, which would leave the
     * gallery with N children and no patch target, breaking the next update.
     */
    private static Map<String, Object> rowsStack(List<String> headers, List<Components.Row> body, Palette p) {
        return Div.id(Components.tableStack(Components.tableItems(headers, body, p)), "onec-rows");
    }

    /** The single-node {@code onec-rows} replacement payload for a {@code div-patch}. */
    private static List<Map<String, Object>> rowsPatch(List<String> headers, List<Components.Row> body, Palette p) {
        return List.of(rowsStack(headers, body, p));
    }

    // ----- document detail -----

    /**
     * One detail-header action. {@code tone} is {@code "primary"} (solid accent —
     * Post), {@code "danger"} (Delete) or {@code "normal"}; {@code placement} is
     * {@code "primary"} (inline button) or {@code "menu"} (overflow ⋯). {@code icon}
     * is a kebab-case lucide name. A null {@code url} drops the action.
     */
    public record HeaderAction(String icon, String label, String tone, String url, String placement) {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row,
                                                     List<HeaderAction> actions, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        boolean posted = Boolean.TRUE.equals(row.get("_posted"));
        Map<String, Object> badge = Components.statusBadge(posted, posted ? "Posted" : "Draft", p);
        items.add(detailHeader(str(meta.get("name")), str(row.get("_number")), badge, actions, p));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        fieldRows.add(Components.fieldRow("Date", str(row.get("_date")), p));
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInDetail")) {
            fieldRows.add(Components.fieldRow(str(a.get("displayName")), cell(a, row), p));
        }
        items.add(fieldCard(fieldRows, p));

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

    // ----- catalog detail -----

    /**
     * A catalog item's detail surface: a header (with edit/delete actions when the
     * caller may write) over a card of its visible system columns (code/description)
     * and attributes. Catalogs have no posting or tabular sections, so it's flatter
     * than {@link #documentDetail}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> catalogDetail(Map<String, Object> meta, Map<String, Object> row,
                                                    List<HeaderAction> actions, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        // Code/description lead the header (title + subtitle), so the card carries just
        // the attributes — no duplicate Code/Description rows.
        String description = str(row.get("_description"));
        String code = str(row.get("_code"));
        String title = description.isBlank() ? str(meta.get("name")) : description;
        String subtitle = description.isBlank() ? code : (code.isBlank() ? null : code);
        items.add(detailHeader(title, subtitle, null, actions, p));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInDetail")) {
            fieldRows.add(Components.fieldRow(str(a.get("displayName")), cell(a, row), p));
        }
        if (!fieldRows.isEmpty()) {
            items.add(fieldCard(fieldRows, p));
        }

        return content(items);
    }

    // ----- create / edit form -----

    /**
     * Emits the create/edit form as the portable {@code onec-form} custom component: a
     * {@code div-custom} carrying a plain-JSON {@code form} descriptor (field metadata +
     * the record's initial values + submit target). Rich controls — styled dropdowns, a
     * calendar picker, a ref picker that can jump to the target catalog's form — need
     * native widgets a DivKit document can't express, so this is deliberately a custom
     * component: every client implements {@code onec-form} from the same descriptor (the
     * web client renders it in React today; a Flutter client renders its own form later).
     */
    public static Map<String, Object> entityForm(Map<String, Object> descriptor) {
        Map<String, Object> custom = Div.custom("onec-form", Map.of("form", descriptor));
        Div.matchWidth(custom);
        return content(List.of(custom));
    }

    // ----- register report -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> registerReport(Map<String, Object> meta,
                                                     List<Map<String, Object>> movements,
                                                     List<Map<String, Object>> balances, Palette p) {
        String type = str(meta.get("type"));
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) meta.getOrDefault("dimensions", List.of());
        List<Map<String, Object>> resources = (List<Map<String, Object>>) meta.getOrDefault("resources", List.of());
        boolean isBalance = "BALANCE".equals(type);

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Components.pageHeader(str(meta.get("name")),
                isBalance ? "Balance register" : "Turnover register", p));

        Map<String, Object> movementsTable = movementsTable(movements, dimensions, resources, p);

        // Balance registers carry both a current balance and the movement log; show them
        // as Balance / Movements tabs rather than two stacked lists. A turnover register
        // has no balance, so it's just the movement log.
        if (isBalance && balances != null) {
            items.add(Components.tabs(List.of(
                    Div.tab("Balance", tabBody(balanceTable(balances, dimensions, resources, p))),
                    Div.tab("Movements", tabBody(movementsTable))), p));
        } else {
            items.add(movementsTable);
        }

        return content(items);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> balanceTable(List<Map<String, Object>> balances,
                                                    List<Map<String, Object>> dimensions,
                                                    List<Map<String, Object>> resources, Palette p) {
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
        return Components.table(headers, body, p);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> movementsTable(List<Map<String, Object>> movements,
                                                      List<Map<String, Object>> dimensions,
                                                      List<Map<String, Object>> resources, Palette p) {
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
        return Components.table(headers, body, p);
    }

    /** Wrap a tab's content with breathing room below the tab strip. */
    private static Map<String, Object> tabBody(Map<String, Object> content) {
        Map<String, Object> wrap = Div.vertical(List.of(content));
        Div.matchWidth(wrap);
        Div.margins(wrap, 12, 0, 0, 0);
        return wrap;
    }

    // ----- shared helpers -----

    private static Map<String, Object> content(List<Map<String, Object>> items) {
        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onec-content");
        Div.contentPadding(root);
        Div.matchWidth(root);
        Div.gap(root, 4);
        return root;
    }

    private static final String DANGER = "#DC2626";

    /**
     * The detail surface header: a title (with an optional muted subtitle — e.g. a
     * document number or catalog code, which keeps long identifiers out of the big
     * title so it no longer wraps into the corner) on the left, and an action cluster
     * (optional status chip + primary buttons + an overflow ⋯ menu) pinned right. The
     * title takes the remaining width and ellipsizes so the actions never get crammed.
     *
     * <p>Each {@link HeaderAction} renders inline (placement {@code "primary"}) or is
     * collected into a single trailing overflow menu (placement {@code "menu"}), so a
     * document can keep just Post on the surface and tuck Unpost/Edit/Delete away.
     */
    private static Map<String, Object> detailHeader(String title, String subtitle, Map<String, Object> badge,
                                                    List<HeaderAction> actions, Palette p) {
        Map<String, Object> titleNode = Div.color(Div.text(title, 20, "bold"), p.text());
        Div.matchWidth(titleNode);
        Div.maxLines(titleNode, 2);
        List<Map<String, Object>> leftItems = new ArrayList<>(List.of(titleNode));
        if (subtitle != null && !subtitle.isBlank()) {
            Map<String, Object> sub = Div.color(Div.text(subtitle, 13, "regular"), p.muted());
            Div.matchWidth(sub);
            Div.maxLines(sub, 1);
            Div.margins(sub, 3, 0, 0, 0);
            leftItems.add(sub);
        }
        Map<String, Object> left = Div.vertical(leftItems);
        Div.weight(left, 1);

        List<Map<String, Object>> cluster = new ArrayList<>();
        if (badge != null) {
            cluster.add(badge);
        }
        List<HeaderAction> menu = new ArrayList<>();
        for (HeaderAction a : actions == null ? List.<HeaderAction>of() : actions) {
            if (a == null || a.url() == null) {
                continue;
            }
            if ("menu".equals(a.placement())) {
                menu.add(a);
                continue;
            }
            String[] c = toneColors(a.tone(), p);
            cluster.add(Components.actionButton(a.icon(), a.label(), c[0], c[1], c[2], a.url(),
                    a.label().toLowerCase()));
        }
        if (!menu.isEmpty()) {
            cluster.add(actionsMenu(menu, p));
        }

        Map<String, Object> actionRow = Div.horizontal(cluster);
        // Hug the buttons (containers default to match_parent, which would stretch the
        // cluster); the weighted title then pushes it to the right edge.
        Div.wrapWidth(actionRow);
        Div.gap(actionRow, 8);
        Div.alignV(actionRow, "center");

        Map<String, Object> row = Div.horizontal(List.of(left, actionRow));
        Div.matchWidth(row);
        Div.alignV(row, "center");
        Div.margins(row, 0, 0, 16, 0);
        return row;
    }

    /** Inline-button colors {fg, bg, border} for an action tone. */
    private static String[] toneColors(String tone, Palette p) {
        return switch (tone == null ? "normal" : tone) {
            case "primary" -> new String[]{"#FFFFFF", p.success(), null};
            case "danger" -> new String[]{DANGER, null, DANGER};
            default -> new String[]{p.text(), p.primarySoft(), null};
        };
    }

    /**
     * The overflow (⋯) menu: an {@code onec-actions-menu} custom block carrying the
     * menu-placed actions as plain items ({@code label / icon / url / danger}). The
     * client renders a kebab trigger + dropdown and dispatches each item's
     * {@code onec://} url — same routing as the inline buttons.
     */
    private static Map<String, Object> actionsMenu(List<HeaderAction> items, Palette p) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (HeaderAction a : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", a.label());
            m.put("icon", a.icon());
            m.put("url", a.url());
            m.put("danger", "danger".equals(a.tone()));
            list.add(m);
        }
        Map<String, Object> node = Div.custom("onec-actions-menu", Map.of("items", list));
        Div.width(node, 38);
        Div.height(node, 34);
        return node;
    }

    /** A definition-list card: field rows separated by hairline dividers. */
    private static Map<String, Object> fieldCard(List<Map<String, Object>> rows, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                items.add(Div.separator(p.border()));
            }
            items.add(rows.get(i));
        }
        Map<String, Object> card = Div.vertical(items);
        Div.matchWidth(card);
        Div.background(card, p.surface());
        Div.pad(card, 4, 16);
        Div.corner(card, 12);
        Div.stroke(card, p.border(), 1);
        return card;
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
