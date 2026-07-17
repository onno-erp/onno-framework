package su.onno.ui.divkit;

import su.onno.ui.ResolvedListView;
import su.onno.ui.UiMessages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the per-surface DivKit <em>content</em> (catalog/document lists, document
 * detail, register report) from the resolved metadata view + data rows. Returns a
 * bare content div — {@link su.onno.ui.DivKitController} wraps it in the app shell.
 * Composed only from native DivKit primitives so it renders on every official SDK
 * with no custom code, keeping a future Flutter client cheap.
 */
public final class SurfaceDivBuilder {

    private SurfaceDivBuilder() {}

    // ----- catalog list -----

    /**
     * A list surface as the {@code onno-list} React island: a single custom block carrying a
     * descriptor (columns, sort, searchability, the open-route + New url). The island fetches
     * pages from {@code /api/list/...} and virtualizes them, so a 10k-row entity never ships whole.
     */
    public static Map<String, Object> listSurface(ResolvedListView view, String kind, String name,
                                                  String newUrl, boolean canWrite,
                                                  List<Map<String, Object>> actions,
                                                  List<Map<String, Object>> inputs) {
        Map<String, Object> descriptor = listDescriptor(view, kind, name, newUrl, canWrite, actions, inputs);
        Map<String, Object> custom = Div.custom("onno-list", Map.of("list", descriptor));
        Div.matchWidth(custom);
        Map<String, Object> root = Div.vertical(List.of(custom));
        Div.id(root, "onno-content");
        Div.matchWidth(root);
        return root;
    }

    /**
     * The {@code onno-list} descriptor (columns, sort, searchability, routes, actions, inputs) that
     * the React island consumes — without the surface wrapping, so it can be embedded inside a page
     * as an {@code onno-list} {@code div-custom} block (see {@code PageBuilder.list}).
     */
    public static Map<String, Object> listDescriptor(ResolvedListView view, String kind, String name,
                                                     String newUrl, boolean canWrite,
                                                     List<Map<String, Object>> actions,
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
            // A row-action submenu the cell opens directly on right-click (ListSpec.cellMenu).
            if (c.cellMenu() != null && !c.cellMenu().isBlank()) {
                col.put("cellMenu", c.cellMenu());
            }
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
            // Each option travels as {value,label}: the client renders label, sends value to the
            // query (so a code/enum-mirror column can show a localized choice). Optional color and
            // avatarUrl carry richer enum/ref presentation without changing the filter predicate.
            List<Map<String, Object>> options = new ArrayList<>();
            for (ResolvedListView.Option o : f.options()) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("value", o.value());
                opt.put("label", o.label());
                // @EnumLabel(color=…) hex, so the control tints the choice like the status pills.
                if (!o.color().isBlank()) {
                    opt.put("color", o.color());
                }
                if (!o.avatarUrl().isBlank()) {
                    opt.put("avatarUrl", o.avatarUrl());
                }
                options.add(opt);
            }
            filter.put("options", options);
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
        // The caller's write access on the entity, so the island hides its write affordances (row
        // Edit/Duplicate/Delete, batch delete) for read-only viewers. REST enforces regardless.
        descriptor.put("canWrite", canWrite);
        descriptor.put("actions", actions == null ? List.of() : actions);
        descriptor.put("inputs", inputs == null ? List.of() : inputs);
        // How the grid feeds rows: "infinite" (cursor/keyset scroll — the default) or "paged"
        // (numbered offset pages), plus the window/page size. Both resolved from the view over the
        // global onno.ui.list.* defaults (see UiViewResolver / ListSpec.feed).
        descriptor.put("feedMode", view.feedMode());
        descriptor.put("pageSize", view.pageSize());
        // Grouping: the columns the "Group by ▾" picker offers, and the per-group subtotals to show.
        // A date column carries date:true so the picker offers a day/month/year granularity. Fetched
        // from /api/list/{kind}/{name}/groups; a group's rows expand through the normal feed.
        List<Map<String, Object>> groupable = new ArrayList<>();
        for (ResolvedListView.GroupColumn g : view.grouping().columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("columnName", g.columnName());
            col.put("label", g.label());
            col.put("date", g.date());
            groupable.add(col);
        }
        descriptor.put("groupable", groupable);
        // The column the list opens grouped by (ListSpec.defaultGroupBy). Same descriptor key a
        // page-embedded list's defaults use, so a PageBuilder.list(...) groupBy still overrides it.
        if (!view.grouping().defaultColumn().isBlank()) {
            descriptor.put("defaultGroupBy", view.grouping().defaultColumn());
        }
        List<Map<String, Object>> aggregates = new ArrayList<>();
        for (ResolvedListView.Aggregate a : view.grouping().aggregates()) {
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("columnName", a.columnName());
            agg.put("fn", a.fn());
            agg.put("label", a.label());
            agg.put("format", a.format());
            aggregates.add(agg);
        }
        descriptor.put("aggregates", aggregates);
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
        // Optional custom body renderer: a Table ⇄ custom toggle over the same toolbar + feed. The
        // island resolves the type in its widget registry (registerListRenderer); an unregistered
        // type degrades to the default grid (see ResolvedListView.CustomView).
        if (view.customView() != null) {
            ResolvedListView.CustomView cv = view.customView();
            Map<String, Object> custom = new LinkedHashMap<>();
            custom.put("type", cv.type());
            custom.put("label", cv.label());
            custom.put("defaultView", cv.defaultView());
            descriptor.put("custom", custom);
        }
        return descriptor;
    }

    public static Map<String, Object> catalogList(ResolvedListView view, List<Map<String, Object>> rows,
                                                  String routeName, String newUrl, Palette p) {
        return listContent(view.title(), "items", newUrl, headerLabels(view), columnWidths(view),
                catalogBody(view, rows, routeName), p);
    }

    /**
     * The rows stack as a {@code div-patch} of {@code onno-rows} — a single replacement
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
            String url = "onno://catalogs/" + routeName + "/" + str(row.get("_id"));
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
     * The rows stack as a {@code div-patch} of {@code onno-rows} — a single replacement
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
            String url = "onno://documents/" + routeName + "/" + str(row.get("_id"));
            body.add(new Components.Row(rowCells(view, row), url));
        }
        return body;
    }

    // A list surface: title + a count subtitle bound to the @{onno_count} variable
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

        Map<String, Object> subtitle = Div.color(Div.text("@{onno_count} " + nounPlural, 13, "regular"), p.muted());
        Div.margins(subtitle, 2, 0, 0, 0);
        Map<String, Object> header = Div.vertical(List.of(top, subtitle));
        Div.margins(header, 0, 0, 16, 0);

        Map<String, Object> table = Components.scrollX(rowsStack(headers, widths, body, p), p);
        return content(List.of(header, table));
    }

    /**
     * The header+rows stack carrying the {@code onno-rows} id. The scroll gallery holds
     * exactly this one child, so a live update must replace it with a single node that
     * <em>re-carries</em> the id — not splice in the bare row list, which would leave the
     * gallery with N children and no patch target, breaking the next update.
     */
    private static Map<String, Object> rowsStack(List<String> headers, List<String> widths,
                                                List<Components.Row> body, Palette p) {
        return Div.id(Components.tableStack(Components.tableItems(headers, body, widths, p)), "onno-rows");
    }

    /** The single-node {@code onno-rows} replacement payload for a {@code div-patch}. */
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
    /** {@code form} (may be empty): an action-form dialog's field descriptors — the client collects
     *  them in a modal before POSTing the action (see {@code ActionSpec.ActionBuilder#form}).
     *  {@code disabled} renders the button greyed and inert — a custom DETAIL action whose
     *  {@code enabledWhen} predicate failed against the loaded record (issue #255).
     *  {@code dynamicForm}: the modal fetches its opening values from
     *  {@code GET /api/actions/{kind}/{name}/{key}/form} before rendering ({@code formDefaults}). */
    public record HeaderAction(String icon, String label, String tone, String url, String placement,
                               List<Map<String, Object>> form, boolean disabled, boolean dynamicForm) {
        public HeaderAction(String icon, String label, String tone, String url, String placement) {
            this(icon, label, tone, url, placement, List.of(), false, false);
        }

        public HeaderAction(String icon, String label, String tone, String url, String placement,
                            List<Map<String, Object>> form) {
            this(icon, label, tone, url, placement, form, false, false);
        }

        public HeaderAction(String icon, String label, String tone, String url, String placement,
                            List<Map<String, Object>> form, boolean disabled) {
            this(icon, label, tone, url, placement, form, disabled, false);
        }
    }

    /** Back-compat overload for surfaces with no related-list panels (e.g. unit tests). */
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row,
                                                     List<HeaderAction> actions, Palette p) {
        return documentDetail(meta, row, Map.of(), actions, p, UiMessages.defaults());
    }

    /** Back-compat overload rendering the English chrome defaults (used by unit tests). */
    public static Map<String, Object> documentDetail(Map<String, Object> meta, Map<String, Object> row,
                                                     Map<String, List<Map<String, Object>>> relatedRows,
                                                     List<HeaderAction> actions, Palette p) {
        return documentDetail(meta, row, relatedRows, actions, p, UiMessages.defaults());
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
                                                     List<HeaderAction> actions, Palette p, UiMessages msg) {
        List<Map<String, Object>> items = new ArrayList<>();

        boolean posted = Boolean.TRUE.equals(row.get("_posted"));
        Map<String, Object> badge = Components.statusBadge(posted,
                msg.get(posted ? "status.posted" : "status.draft"), p);
        items.add(detailHeader(titleOf(meta), str(row.get("_number")), badge, actions, p));

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        // The header date honors a .field("date").format(...) hint, like any other column; its
        // label honors a .field("date").label(...) hint (else the English "Date" fallback) — #154.
        String dateText = ValueFormat.apply(systemColumnFormat(meta, "_date"), row.get("_date"));
        fieldRows.add(Components.fieldRow(systemColumnLabel(meta, "_date", "Date"),
                dateText != null ? dateText : str(row.get("_date")), p));
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
            items.add(Components.table(headers, body, p, msg.get("empty.noRecords")));
        }

        // Related-list panels render read-only here — the document-side analogue of a catalog's
        // (same flag/skip rules as catalogDetail): showInDetail and present in the preloaded map.
        for (Map<String, Object> rl : (List<Map<String, Object>>) meta.getOrDefault("relatedLists", List.of())) {
            if (!Boolean.TRUE.equals(rl.get("showInDetail")) || !relatedRows.containsKey(str(rl.get("name")))) {
                continue;
            }
            items.add(sectionLabel(relatedListTitle(rl), p));
            items.add(relatedListTable(rl, relatedRows.get(str(rl.get("name"))), p, msg));
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
    /** Back-compat overload rendering the English chrome defaults (used by unit tests). */
    public static Map<String, Object> catalogDetail(Map<String, Object> meta, Map<String, Object> row,
                                                    Map<String, List<Map<String, Object>>> relatedRows,
                                                    List<HeaderAction> actions, Palette p) {
        return catalogDetail(meta, row, relatedRows, actions, p, UiMessages.defaults());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> catalogDetail(Map<String, Object> meta, Map<String, Object> row,
                                                    Map<String, List<Map<String, Object>>> relatedRows,
                                                    List<HeaderAction> actions, Palette p, UiMessages msg) {
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
            items.add(relatedListTable(rl, relatedRows.get(str(rl.get("name"))), p, msg));
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
                                                        List<Map<String, Object>> rows, Palette p, UiMessages msg) {
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
        return Components.table(headers, body, p, msg.get("empty.noRecords"));
    }

    // ----- create / edit form -----

    /**
     * Emits the create/edit form as the portable {@code onno-form} custom component: a
     * {@code div-custom} carrying a plain-JSON {@code form} descriptor (field metadata +
     * the record's initial values + submit target). Rich controls — styled dropdowns, a
     * calendar picker, a ref picker that can jump to the target catalog's form — need
     * native widgets a DivKit document can't express, so this is deliberately a custom
     * component: every client implements {@code onno-form} from the same descriptor (the
     * web client renders it in React today; a Flutter client renders its own form later).
     */
    public static Map<String, Object> entityForm(Map<String, Object> descriptor) {
        Map<String, Object> custom = Div.custom("onno-form", Map.of("form", descriptor));
        Div.matchWidth(custom);
        return content(List.of(custom));
    }

    // ----- register report -----

    /**
     * The register surface as an {@code onno-register} custom block: one or more named, paginated
     * list views fed page-by-page from {@code /api/list/registers/...}. A BALANCE register carries a
     * Balance + a Movements view; a TURNOVER register just Movements. The view switch is a plain
     * React toggle (no DivKit tabs) — React owns which list is mounted, so switching can never blank
     * the surface, and there's no animation. This replaces the old fully-server-rendered table: a
     * packed register no longer streams its whole movement log into one DivKit document.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> registerSurface(Map<String, Object> meta, String name,
                                                      Palette p, UiMessages msg) {
        String type = str(meta.get("type"));
        boolean isBalance = "BALANCE".equals(type);
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) meta.getOrDefault("dimensions", List.of());
        List<Map<String, Object>> resources = (List<Map<String, Object>>) meta.getOrDefault("resources", List.of());
        String title = titleOf(meta);

        List<Map<String, Object>> views = new ArrayList<>();
        if (isBalance) {
            views.add(registerView("balance", msg.get("register.balanceTab"),
                    registerListDescriptor(title, name, "/api/list/registers/" + name + "/balance",
                            balanceColumns(dimensions, resources), List.of(),
                            firstColumnName(dimensions), false)));
        }
        views.add(registerView("movements", msg.get("register.movementsTab"),
                registerListDescriptor(title, name, "/api/list/registers/" + name + "/movements",
                        movementColumns(dimensions, resources, msg, str(meta.get("periodFormat"))),
                        movementFilters(msg), "_period", true)));

        Map<String, Object> register = new LinkedHashMap<>();
        register.put("views", views);
        Map<String, Object> custom = Div.custom("onno-register", Map.of("register", register));
        Div.matchWidth(custom);
        Map<String, Object> root = Div.vertical(List.of(custom));
        Div.id(root, "onno-content");
        Div.matchWidth(root);
        return root;
    }

    /** One named view of a register surface: {@code {key, label, list}} for the React toggle. */
    private static Map<String, Object> registerView(String key, String label, Map<String, Object> list) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("key", key);
        v.put("label", label);
        v.put("list", list);
        return v;
    }

    /** A register {@code onno-list} descriptor: no search/New, fed by an explicit feed URL. */
    private static Map<String, Object> registerListDescriptor(String title, String name, String feed,
                                                              List<Map<String, Object>> columns,
                                                              List<Map<String, Object>> filters,
                                                              String sortColumn, boolean sortDescending) {
        Map<String, Object> sort = new LinkedHashMap<>();
        sort.put("column", sortColumn);
        sort.put("descending", sortDescending);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", "registers");
        d.put("name", name);
        d.put("title", title);
        d.put("columns", columns);
        d.put("searchable", false);
        d.put("sort", sort);
        d.put("filters", filters);
        d.put("newUrl", null);
        // Register movement/balance views are read-only report surfaces — never offer row writes.
        d.put("canWrite", false);
        d.put("actions", List.of());
        d.put("inputs", List.of());
        // A register's movement log / balance is append-heavy and depth-scrolled — cursor-stream it.
        d.put("feedMode", "infinite");
        d.put("pageSize", 50);
        // Where the island fetches its windows from (a register has no /api/list/{kind}/{name} route).
        d.put("feed", feed);
        return d;
    }

    /**
     * The movements tab's built-in filter facets: a {@code _period} date range and a
     * {@code _movement_type} Receipt/Expense choice. Both compile server-side through
     * {@link su.onno.ui.ListFilter} like any authored list filter (ge/le on the period, eq on the
     * type), so the register no longer forces scrolling the whole log to find a window of activity.
     */
    private static List<Map<String, Object>> movementFilters(UiMessages msg) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(filterControl("period", msg.get("register.period"), "_period", "dateRange", List.of()));
        filters.add(filterControl("type", msg.get("register.type"), "_movement_type", "options", List.of(
                filterOption("RECEIPT", msg.get("register.receipt")),
                filterOption("EXPENSE", msg.get("register.expense")))));
        return filters;
    }

    /** One declarative filter control in the list-descriptor shape the grid expects. */
    private static Map<String, Object> filterControl(String key, String label, String column,
                                                     String type, List<Map<String, Object>> options) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("column", column);
        f.put("type", type);
        f.put("options", options);
        return f;
    }

    private static Map<String, Object> filterOption(String value, String label) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        return o;
    }

    private static List<Map<String, Object>> movementColumns(List<Map<String, Object>> dimensions,
                                                             List<Map<String, Object>> resources,
                                                             UiMessages msg, String periodFormat) {
        List<Map<String, Object>> cols = new ArrayList<>();
        // The movement timestamp honors a display format authored on the register's view
        // (field("period").format("dd-MM-yyyy HH:mm")) — the register analogue of a document's
        // _date column. Blank/absent falls back to the grid's locale-default date-time rendering
        // (see formatTimestampDefault in cell-format.ts), never the raw ISO string.
        cols.add(column("_period", msg.get("register.period"), null, periodFormat));
        cols.add(column("_movement_type", msg.get("register.type"), null, null));
        for (Map<String, Object> d : dimensions) cols.add(columnFromMeta(d));
        for (Map<String, Object> r : resources) cols.add(columnFromMeta(r));
        return cols;
    }

    private static List<Map<String, Object>> balanceColumns(List<Map<String, Object>> dimensions,
                                                            List<Map<String, Object>> resources) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Map<String, Object> d : dimensions) cols.add(columnFromMeta(d));
        for (Map<String, Object> r : resources) cols.add(columnFromMeta(r));
        return cols;
    }

    private static Map<String, Object> columnFromMeta(Map<String, Object> attr) {
        return column(str(attr.get("columnName")), str(attr.get("displayName")),
                str(attr.get("widget")), str(attr.get("format")));
    }

    private static Map<String, Object> column(String columnName, String label, String widget, String format) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("columnName", columnName);
        c.put("label", label);
        c.put("width", "");
        c.put("widget", widget == null ? "" : widget);
        c.put("format", format == null ? "" : format);
        c.put("hint", "");
        return c;
    }

    private static String firstColumnName(List<Map<String, Object>> dimensions) {
        return dimensions.isEmpty() ? null : str(dimensions.get(0).get("columnName"));
    }

    // ----- shared helpers -----

    private static Map<String, Object> content(List<Map<String, Object>> items) {
        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onno-content");
        Div.contentPadding(root);
        Div.matchWidth(root);
        Div.gap(root, 4);
        return root;
    }

    /**
     * Append the comments thread panel to a built record surface. The panel is an
     * {@code onno-comments} {@code div-custom} carrying the entity's {@code (kind, name, id)} triple;
     * the React bridge loads and posts the thread itself from {@code /api/comments/...}. Returns the
     * same content map (its {@code items} replaced with an extended copy), so callers can chain it
     * onto {@link #entityForm} (the combined record surface) without that method knowing about
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
        Map<String, Object> panel = Div.custom("onno-comments",
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
        // (Edit / Duplicate) and Delete route through onno:// exactly as before.
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
     * The detail-header action cluster: an {@code onno-actions-menu} custom block carrying every
     * action ({@code label / icon / url / tone / placement}). The React island renders the
     * {@code "primary"}-placed ones as inline buttons and tucks {@code "menu"}-placed ones into an
     * overflow ⋯ dropdown, runs the async ones (Post / Unpost / custom server actions) with an
     * in-button loading state, and routes the rest through the same {@code onno://} events.
     */
    private static Map<String, Object> actionCluster(List<HeaderAction> items) {
        List<Map<String, Object>> list = actionItems(items);
        // Reserve a (generous) width for the DivKit box: the React island is portaled in after
        // DivKit lays out, so wrap_content would measure it empty. Erring large avoids clipping —
        // the inline-flex host still hugs the real buttons inside the reserved space.
        int inline = 0;
        boolean hasMenu = false;
        int width = 0;
        for (HeaderAction a : items) {
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
        Map<String, Object> node = Div.custom("onno-actions-menu", Map.of("items", list));
        Div.width(node, Math.max(40, width));
        return node;
    }

    /**
     * {@link HeaderAction}s as the plain-JSON items the action-cluster island consumes
     * ({@code label / icon / url / tone / placement} + optional {@code form}). Shared by the
     * {@code onno-actions-menu} block and the entity-form descriptor's {@code actions} — the
     * combined record surface renders the same cluster from the same shape.
     */
    public static List<Map<String, Object>> actionItems(List<HeaderAction> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (HeaderAction a : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", a.label());
            m.put("icon", a.icon());
            m.put("url", a.url());
            m.put("tone", a.tone());
            m.put("placement", a.placement());
            if (a.disabled()) {
                // A per-record enabledWhen failed: render the button greyed and inert (#255).
                m.put("disabled", true);
            }
            if (a.form() != null && !a.form().isEmpty()) {
                // The island opens a modal collecting these fields before it POSTs the action.
                m.put("form", a.form());
                if (a.dynamicForm()) {
                    // The modal fetches its opening values (formDefaults) before rendering.
                    m.put("dynamicForm", true);
                }
            }
            list.add(m);
        }
        return list;
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
        Div.corner(card, Radii.CARD);
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

    /**
     * The resolved display label for a system column (e.g. {@code _date}) — the {@code displayName}
     * the metadata service emitted, which already folds in any {@code .field(...).label(...)} hint
     * (#154). Falls back to {@code fallback} when the column or a blank label leaves nothing to show.
     */
    @SuppressWarnings("unchecked")
    private static String systemColumnLabel(Map<String, Object> meta, String columnName, String fallback) {
        for (Map<String, Object> sc : (List<Map<String, Object>>) meta.getOrDefault("systemColumns", List.of())) {
            if (columnName.equals(str(sc.get("columnName")))) {
                String label = str(sc.get("displayName"));
                return label.isBlank() ? fallback : label;
            }
        }
        return fallback;
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
        // An enum value with an @EnumLabel(color = …) rides as {col}_color (RefResolver) — render it
        // as a coloured status pill, matching the list cell and the form dropdown's colour.
        String color = str(row.get(str(a.get("columnName")) + "_color"));
        if (!color.isBlank()) {
            return Components.pillFieldRow(label, cell(a, row), color, hint, p);
        }
        return Components.fieldRow(label, cell(a, row), hint, p);
    }

    /**
     * The {@code onno://} url that opens the record a ref attribute points at, or null when the
     * attribute isn't a (resolved) ref. The target id comes from the {@code {column}_ref} map the
     * {@link su.onno.ui.RefResolver} stamps on the row; the route is the attribute's
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
        return "onno://" + kind + "/" + routeNameOf(target) + "/" + id;
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
        if (su.onno.security.SecretRedactor.SET.equals(value)) return "•••• set";
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
