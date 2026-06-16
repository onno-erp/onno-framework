package com.onec.ui.divkit;

import com.onec.ui.ResolvedListView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * A list surface as the {@code onec-list} React island: a single custom block carrying a
     * descriptor (columns, sort, searchability, the open-route + New url). The island fetches
     * pages from {@code /api/list/...} and virtualizes them, so a 10k-row entity never ships whole.
     */
    public static Map<String, Object> listSurface(ResolvedListView view, String kind, String name,
                                                  String newUrl, List<Map<String, Object>> actions,
                                                  List<Map<String, Object>> inputs) {
        Map<String, Object> descriptor = listDescriptor(view, kind, name, newUrl, actions, inputs);
        Map<String, Object> custom = Div.custom("onec-list", Map.of("list", descriptor));
        Div.matchWidth(custom);
        Map<String, Object> root = Div.vertical(List.of(custom));
        Div.id(root, "onec-content");
        Div.matchWidth(root);
        return root;
    }

    /**
     * The {@code onec-list} descriptor (columns, sort, searchability, routes, actions, inputs) that
     * the React island consumes — without the surface wrapping, so it can be embedded inside a page
     * as an {@code onec-list} {@code div-custom} block (see {@code PageBuilder.list}).
     */
    public static Map<String, Object> listDescriptor(ResolvedListView view, String kind, String name,
                                                     String newUrl, List<Map<String, Object>> actions,
                                                     List<Map<String, Object>> inputs) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ResolvedListView.Column c : view.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("columnName", c.columnName());
            col.put("label", c.label());
            col.put("width", c.width() == null ? "" : c.width());
            // Display hints the React list honors: an image/avatar widget renders a thumbnail, and
            // a format string reformats dates/numbers in the cell.
            col.put("widget", c.widget() == null ? "" : c.widget());
            col.put("format", c.format() == null ? "" : c.format());
            // Optional help text → a hoverable "?" next to the column header.
            col.put("hint", c.hint() == null ? "" : c.hint());
            columns.add(col);
        }
        Map<String, Object> sort = new LinkedHashMap<>();
        sort.put("column", view.sortColumn());
        sort.put("descending", view.sortDescending());

        // Declarative filters: controls the island renders in the toolbar and folds into the
        // /api/list query (see ListFilter). The column travels so the client can address the
        // server-side predicate; the server re-validates it against the entity's real columns.
        List<Map<String, Object>> filters = new ArrayList<>();
        for (ResolvedListView.Filter f : view.filters()) {
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("key", f.key());
            filter.put("label", f.label());
            filter.put("column", f.columnName());
            filter.put("type", f.type());
            filter.put("options", f.options());
            filters.add(filter);
        }

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", kind);
        descriptor.put("name", name);
        descriptor.put("title", view.title());
        descriptor.put("columns", columns);
        descriptor.put("searchable", view.searchable());
        descriptor.put("sort", sort);
        descriptor.put("filters", filters);
        descriptor.put("newUrl", newUrl);
        descriptor.put("actions", actions == null ? List.of() : actions);
        descriptor.put("inputs", inputs == null ? List.of() : inputs);
        descriptor.put("pageSize", 100);
        // Optional map view: a Table ⇄ Map toggle the island renders, plotting the rows as markers
        // (the geo columns are resolved + validated server-side; see ResolvedListView.MapView).
        if (view.mapView() != null) {
            ResolvedListView.MapView mv = view.mapView();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("geoField", mv.geoField());
            map.put("latField", mv.latField());
            map.put("lngField", mv.lngField());
            map.put("geoJsonField", mv.geoJsonField());
            map.put("labelField", mv.labelField());
            map.put("defaultView", mv.defaultView());
            descriptor.put("map", map);
        }
        return descriptor;
    }

    public static Map<String, Object> catalogList(ResolvedListView view, List<Map<String, Object>> rows,
                                                  String routeName, String newUrl, Palette p) {
        return listContent(view.title(), "items", newUrl, headerLabels(view), columnWidths(view),
                catalogBody(view, rows, routeName), p);
    }

    /**
     * The rows stack as a {@code div-patch} of {@code onec-rows} — a single replacement
     * node re-carrying that id (see {@link #rowsPatch}).
     */
    public static List<Map<String, Object>> catalogRows(ResolvedListView view, List<Map<String, Object>> rows,
                                                        String routeName, Palette p) {
        return rowsPatch(headerLabels(view), columnWidths(view), catalogBody(view, rows, routeName), p);
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
        return listContent(view.title(), "documents", newUrl, headerLabels(view), columnWidths(view),
                documentBody(view, rows, routeName), p);
    }

    /**
     * The rows stack as a {@code div-patch} of {@code onec-rows} — a single replacement
     * node re-carrying that id (see {@link #rowsPatch}).
     */
    public static List<Map<String, Object>> documentRows(ResolvedListView view, List<Map<String, Object>> rows,
                                                         String routeName, Palette p) {
        return rowsPatch(headerLabels(view), columnWidths(view), documentBody(view, rows, routeName), p);
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
                                                   List<String> headers, List<String> widths,
                                                   List<Components.Row> body, Palette p) {
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

        Map<String, Object> table = Components.scrollX(rowsStack(headers, widths, body, p), p);
        return content(List.of(header, table));
    }

    /**
     * The header+rows stack carrying the {@code onec-rows} id. The scroll gallery holds
     * exactly this one child, so a live update must replace it with a single node that
     * <em>re-carries</em> the id — not splice in the bare row list, which would leave the
     * gallery with N children and no patch target, breaking the next update.
     */
    private static Map<String, Object> rowsStack(List<String> headers, List<String> widths,
                                                List<Components.Row> body, Palette p) {
        return Div.id(Components.tableStack(Components.tableItems(headers, body, widths, p)), "onec-rows");
    }

    /** The single-node {@code onec-rows} replacement payload for a {@code div-patch}. */
    private static List<Map<String, Object>> rowsPatch(List<String> headers, List<String> widths,
                                                      List<Components.Row> body, Palette p) {
        return List.of(rowsStack(headers, widths, body, p));
    }

    // ----- document detail -----

    /**
     * One detail-header action. {@code tone} is {@code "primary"} (solid success —
     * Post), {@code "accent"} (solid brand — the surface's main action, e.g. Edit),
     * {@code "danger"} (Delete) or {@code "normal"} (neutral); {@code placement} is
     * {@code "primary"} (inline button) or {@code "menu"} (overflow ⋯). {@code icon}
     * is a kebab-case lucide name. A null {@code url} drops the action.
     */
    public record HeaderAction(String icon, String label, String tone, String url, String placement) {}

    /** Back-compat overload for surfaces with no related-list panels (e.g. unit tests). */
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row,
                                                     List<HeaderAction> actions, Palette p) {
        return documentDetail(meta, row, Map.of(), actions, p);
    }

    /**
     * A document's detail surface: header (+ posting/edit actions), a card of its visible system
     * columns and attributes, then a table per tabular section and finally a read-only table per
     * related-list panel. The related-list panels are the document-side parity with the catalog
     * detail (see {@link #catalogDetail}) — a booking can surface its guests (the reverse side of a
     * Booking↔Client junction) without entering edit mode (see #110).
     *
     * <p>{@code relatedRows} maps each panel's {@code name} to its preloaded junction rows; a panel
     * renders iff it is {@code showInDetail} and present in that map (the caller omits panels the
     * user may not read). Empty map → no panels.</p>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row,
                                                     Map<String, List<Map<String, Object>>> relatedRows,
                                                     List<HeaderAction> actions, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        boolean posted = Boolean.TRUE.equals(row.get("_posted"));
        Map<String, Object> badge = Components.statusBadge(posted, posted ? "Posted" : "Draft", p);
        items.add(detailHeader(titleOf(meta), str(row.get("_number")), badge, actions, p));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        // The header date honors a .field("date").format(...) hint, like any other column.
        String dateText = ValueFormat.apply(systemColumnFormat(meta, "_date"), row.get("_date"));
        fieldRows.add(Components.fieldRow("Date", dateText != null ? dateText : str(row.get("_date")), p));
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInDetail")) {
            fieldRows.add(fieldRowFor(a, row, p));
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
                List<String> cellUrls = new ArrayList<>();
                Object ln = tsRow.get("_line_number");
                cells.add(ln != null ? str(ln) : String.valueOf(line));
                cellUrls.add(null); // the leading "#" line-number column is never a ref
                for (Map<String, Object> a : tsAttrs) {
                    cells.add(cell(a, tsRow));
                    cellUrls.add(refUrlFor(a, tsRow)); // null for non-ref columns
                }
                body.add(new Components.Row(cells, null, cellUrls));
                line++;
            }
            items.add(sectionLabel(str(ts.get("name")), p));
            items.add(Components.table(headers, body, p));
        }

        // Related-list panels render read-only here — the document-side analogue of a catalog's
        // (same flag/skip rules as catalogDetail): showInDetail and present in the preloaded map.
        for (Map<String, Object> rl : (List<Map<String, Object>>) meta.getOrDefault("relatedLists", List.of())) {
            if (!Boolean.TRUE.equals(rl.get("showInDetail")) || !relatedRows.containsKey(str(rl.get("name")))) {
                continue;
            }
            items.add(sectionLabel(relatedListTitle(rl), p));
            items.add(relatedListTable(rl, relatedRows.get(str(rl.get("name"))), p));
        }

        return content(items);
    }

    // ----- catalog detail -----

    /**
     * A catalog item's detail surface: a header (with edit/delete actions when the
     * caller may write) over a card of its visible system columns (code/description)
     * and attributes, then a read-only table per related-list panel. Catalogs have no
     * posting, so it's flatter than {@link #documentDetail} — but the related-list
     * tables are the catalog-side analogue of that view's tabular sections.
     *
     * <p>{@code relatedRows} maps each panel's {@code name} to its preloaded join rows;
     * a panel renders (read-only, no add/remove) iff it is {@code showInDetail} and present
     * in that map (the caller omits panels the user may not read). Empty maps to no panel.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> catalogDetail(Map<String, Object> meta, Map<String, Object> row,
                                                    Map<String, List<Map<String, Object>>> relatedRows,
                                                    List<HeaderAction> actions, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        // Code/description lead the header (title + subtitle), so the card carries just
        // the attributes — no duplicate Code/Description rows.
        String description = str(row.get("_description"));
        String code = str(row.get("_code"));
        String title = description.isBlank() ? titleOf(meta) : description;
        String subtitle = description.isBlank() ? code : (code.isBlank() ? null : code);
        items.add(detailHeader(title, subtitle, null, actions, p));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        for (Map<String, Object> a : visible(
                (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of()), "visibleInDetail")) {
            fieldRows.add(fieldRowFor(a, row, p));
        }
        if (!fieldRows.isEmpty()) {
            items.add(fieldCard(fieldRows, p));
        }

        // Related-list panels render read-only here (the catalog-side analogue of a document's
        // tabular sections), mirroring the form widget minus the add-row / remove controls.
        for (Map<String, Object> rl : (List<Map<String, Object>>) meta.getOrDefault("relatedLists", List.of())) {
            if (!Boolean.TRUE.equals(rl.get("showInDetail")) || !relatedRows.containsKey(str(rl.get("name")))) {
                continue;
            }
            items.add(sectionLabel(relatedListTitle(rl), p));
            items.add(relatedListTable(rl, relatedRows.get(str(rl.get("name"))), p));
        }

        return content(items);
    }

    /** A related-list panel heading: its explicit {@code label}, else the capitalized panel name. */
    private static String relatedListTitle(Map<String, Object> rl) {
        String label = str(rl.get("label"));
        if (!label.isBlank()) {
            return label;
        }
        String name = str(rl.get("name"));
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * One related-list panel as a read-only table: a column per resolved join-row column (refs
     * resolved to their description, and clickable through to the target record), one row per
     * join row. Shape mirrors {@link #documentDetail}'s tabular-section tables.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> relatedListTable(Map<String, Object> rl,
                                                        List<Map<String, Object>> rows, Palette p) {
        List<Map<String, Object>> columns = (List<Map<String, Object>>) rl.getOrDefault("columns", List.of());
        List<String> headers = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            headers.add(str(c.get("displayName")));
        }
        List<Components.Row> body = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            List<String> cellUrls = new ArrayList<>();
            for (Map<String, Object> c : columns) {
                cells.add(cell(c, r));
                cellUrls.add(refUrlFor(c, r)); // null for non-ref columns
            }
            body.add(new Components.Row(cells, null, cellUrls));
        }
        return Components.table(headers, body, p);
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
        items.add(Components.pageHeader(titleOf(meta),
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

    /**
     * Append the comments thread panel to a built detail surface. The panel is an
     * {@code onec-comments} {@code div-custom} carrying the entity's {@code (kind, name, id)} triple;
     * the React bridge loads and posts the thread itself from {@code /api/comments/...}. Returns the
     * same content map (its {@code items} replaced with an extended copy), so callers can chain it
     * onto {@link #catalogDetail} / {@link #documentDetail} without those methods knowing about
     * comments. A no-op if the content has no {@code items} list (defensive).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withComments(Map<String, Object> content, String kind,
                                                   String name, String id) {
        Object items = content.get("items");
        if (!(items instanceof List<?> existing)) {
            return content;
        }
        List<Map<String, Object>> next = new ArrayList<>((List<Map<String, Object>>) existing);
        Map<String, Object> panel = Div.custom("onec-comments",
                Map.of("target", Map.of("kind", kind, "name", name, "id", id)));
        Div.matchWidth(panel);
        next.add(panel);
        content.put("items", next);
        return content;
    }

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
        // The whole action cluster (inline primary buttons + the overflow ⋯ menu) renders as one
        // React island, so the async actions (Post / Unpost / custom server actions) show an
        // in-button loading state — like the list's toolbar/row buttons. Navigation actions
        // (Edit / Duplicate) and Delete route through onec:// exactly as before.
        List<HeaderAction> acts = new ArrayList<>();
        for (HeaderAction a : actions == null ? List.<HeaderAction>of() : actions) {
            if (a != null && a.url() != null) {
                acts.add(a);
            }
        }
        if (!acts.isEmpty()) {
            cluster.add(actionCluster(acts));
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

    /**
     * The detail-header action cluster: an {@code onec-actions-menu} custom block carrying every
     * action ({@code label / icon / url / tone / placement}). The React island renders the
     * {@code "primary"}-placed ones as inline buttons and tucks {@code "menu"}-placed ones into an
     * overflow ⋯ dropdown, runs the async ones (Post / Unpost / custom server actions) with an
     * in-button loading state, and routes the rest through the same {@code onec://} events.
     */
    private static Map<String, Object> actionCluster(List<HeaderAction> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        // Reserve a (generous) width for the DivKit box: the React island is portaled in after
        // DivKit lays out, so wrap_content would measure it empty. Erring large avoids clipping —
        // the inline-flex host still hugs the real buttons inside the reserved space.
        int inline = 0;
        boolean hasMenu = false;
        int width = 0;
        for (HeaderAction a : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", a.label());
            m.put("icon", a.icon());
            m.put("url", a.url());
            m.put("tone", a.tone());
            m.put("placement", a.placement());
            list.add(m);
            if ("menu".equals(a.placement())) {
                hasMenu = true;
            } else {
                inline++;
                width += 48 + a.label().length() * 9; // icon + label + horizontal padding
            }
        }
        if (hasMenu) {
            width += 44;
        }
        width += Math.max(0, (inline + (hasMenu ? 1 : 0)) - 1) * 8; // inter-button gaps
        Map<String, Object> node = Div.custom("onec-actions-menu", Map.of("items", list));
        Div.width(node, Math.max(40, width));
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

    /** Per-column authored width hints, positionally aligned with {@link #headerLabels}. */
    private static List<String> columnWidths(ResolvedListView view) {
        return view.columns().stream().map(ResolvedListView.Column::width).toList();
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
        return maskSecret(value);
    }

    private static List<Map<String, Object>> visible(List<Map<String, Object>> attrs, String slot) {
        return attrs.stream()
                .filter(a -> Boolean.TRUE.equals(a.get(slot)))
                .sorted(Comparator.comparingInt(a -> a.get("order") == null
                        ? 0 : ((Number) a.get("order")).intValue()))
                .toList();
    }

    /** The display {@code format} hint declared for a system column (e.g. {@code _date}), or "". */
    @SuppressWarnings("unchecked")
    private static String systemColumnFormat(Map<String, Object> meta, String columnName) {
        for (Map<String, Object> sc : (List<Map<String, Object>>) meta.getOrDefault("systemColumns", List.of())) {
            if (columnName.equals(str(sc.get("columnName")))) {
                return str(sc.get("format"));
            }
        }
        return "";
    }

    private static String cell(Map<String, Object> attr, Map<String, Object> row) {
        String col = str(attr.get("columnName"));
        Object display = row.get(col + "_display");
        // A resolved ref/enum label is shown as-is; only a raw typed value is run through the
        // optional .format(...) hint (a date pattern or number spec).
        if (display != null) {
            return maskSecret(display);
        }
        Object value = row.get(col);
        String formatted = ValueFormat.apply(str(attr.get("format")), value);
        return formatted != null ? formatted : maskSecret(value);
    }

    /**
     * A detail field row for an attribute: an inline image when the attribute is an image
     * widget ({@code .widget("image"|"avatar")}) and holds a value, otherwise the usual
     * label/value text row. The image source is the raw stored string — a {@code data:} URL
     * from the picker or a plain {@code http(s)} URL.
     */
    private static Map<String, Object> fieldRowFor(Map<String, Object> a, Map<String, Object> row, Palette p) {
        String label = str(a.get("displayName"));
        String hint = str(a.get("hint"));
        if (isGalleryWidget(a)) {
            List<String> urls = splitGallery(str(row.get(str(a.get("columnName")))));
            if (!urls.isEmpty()) {
                return Components.imageGalleryRow(label, urls, hint, p);
            }
        } else if (isImageWidget(a)) {
            String url = str(row.get(str(a.get("columnName"))));
            if (!url.isBlank()) {
                return Components.imageFieldRow(label, url, isAvatarWidget(a), hint, p);
            }
        } else if (isFileWidget(a)) {
            String url = str(row.get(str(a.get("columnName"))));
            if (!url.isBlank()) {
                return Components.fileFieldRow(label, url, hint, p);
            }
        } else if (isMapWidget(a)) {
            String value = str(row.get(str(a.get("columnName"))));
            if (!value.isBlank()) {
                return Components.geoFieldRow(label, value, hint, p);
            }
        }
        String refUrl = refUrlFor(a, row);
        if (refUrl != null) {
            return Components.refFieldRow(label, cell(a, row), refUrl, hint, p);
        }
        return Components.fieldRow(label, cell(a, row), hint, p);
    }

    /**
     * The {@code onec://} url that opens the record a ref attribute points at, or null when the
     * attribute isn't a (resolved) ref. The target id comes from the {@code {column}_ref} map the
     * {@link com.onec.ui.RefResolver} stamps on the row; the route is the attribute's
     * refKind/refTarget — same shape a list-row tap produces (see {@link #documentBody}).
     */
    @SuppressWarnings("unchecked")
    private static String refUrlFor(Map<String, Object> a, Map<String, Object> row) {
        if (!Boolean.TRUE.equals(a.get("isRef"))) {
            return null;
        }
        Object refObj = row.get(str(a.get("columnName")) + "_ref");
        if (!(refObj instanceof Map)) {
            return null;
        }
        Object id = ((Map<String, Object>) refObj).get("id");
        String target = str(a.get("refTarget"));
        if (id == null || target.isBlank()) {
            return null;
        }
        String kind = "document".equals(str(a.get("refKind"))) ? "documents" : "catalogs";
        return "onec://" + kind + "/" + routeNameOf(target) + "/" + id;
    }

    /**
     * The URL-safe route segment for a logical name: snake_case, mirroring the client's
     * {@code toSnakeCase} so a ref link hits the same endpoint the user would by navigating.
     */
    private static String routeNameOf(String logicalName) {
        return logicalName
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("\\s+", "_")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isImageWidget(Map<String, Object> a) {
        String w = str(a.get("widget"));
        return w.equalsIgnoreCase("image") || w.equalsIgnoreCase("photo") || isAvatarWidget(a);
    }

    private static boolean isAvatarWidget(Map<String, Object> a) {
        return "avatar".equalsIgnoreCase(str(a.get("widget")));
    }

    /** A file-upload widget ({@code .widget("file")}); value is the stored media reference URL. */
    private static boolean isFileWidget(Map<String, Object> a) {
        return "file".equalsIgnoreCase(str(a.get("widget")));
    }

    /**
     * A map widget ({@code .widget("map"|"geo"|"geolocation")} — a {@code "lat,lng"} point — or
     * {@code .widget("geojson")} — GeoJSON points/paths/areas). Either renders read-only as a map.
     */
    private static boolean isMapWidget(Map<String, Object> a) {
        String w = str(a.get("widget"));
        return w.equalsIgnoreCase("map") || w.equalsIgnoreCase("geo")
                || w.equalsIgnoreCase("geolocation") || w.equalsIgnoreCase("geojson");
    }

    /** A multi-image widget ({@code .widget("images"|"gallery")}); value is newline-joined URLs. */
    private static boolean isGalleryWidget(Map<String, Object> a) {
        String w = str(a.get("widget"));
        return w.equalsIgnoreCase("images") || w.equalsIgnoreCase("gallery") || w.equalsIgnoreCase("photos");
    }

    /** Split a gallery value into its image URLs. base64 data URLs hold no newline, so the
     *  newline join is unambiguous (see GalleryPicker on the client). */
    private static List<String> splitGallery(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return value.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Renders a secret attribute's read-side sentinel as a masked "set" indicator rather than
     * the raw {@code __SECRET_SET__} marker. Non-secret values pass through unchanged.
     */
    private static String maskSecret(Object value) {
        if (value == null) return "";
        if (com.onec.security.SecretRedactor.SET.equals(value)) return "•••• set";
        // An image (or gallery) widget's value is a (potentially huge) data URL — or several,
        // newline-joined; never dump it as text into a list cell or a fallback row. Show a
        // compact placeholder. Detail surfaces render the actual image(s) (see fieldRowFor)
        // before reaching here.
        String s = value.toString();
        if (s.startsWith("data:")) {
            long n = s.lines().count();
            return n > 1 ? "🖼 " + n + " images" : "🖼 Image";
        }
        return s;
    }

    /**
     * The entity's display heading: its {@code title} when set, else the URL-safe
     * {@code name}. Keeps localized/multi-word titles out of routes while still showing
     * them in list/detail/report headers.
     */
    private static String titleOf(Map<String, Object> meta) {
        String title = str(meta.get("title"));
        return title.isBlank() ? str(meta.get("name")) : title;
    }
}
