package su.onno.ui.divkit;

import su.onno.ui.ResolvedListView;
import su.onno.ui.UiMessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-surface DivKit <em>content</em> (catalog/document lists, the combined
 * record form, register report) from the resolved metadata view. Returns a bare content
 * div — {@link su.onno.ui.DivKitController} wraps it in the app shell. The heavy surfaces
 * are React islands carried as {@code div-custom} blocks ({@code onno-list},
 * {@code onno-form}, {@code onno-register}); every client implements them from the same
 * plain-JSON descriptors.
 */
public final class SurfaceDivBuilder {

    private SurfaceDivBuilder() {}

    // ----- list surfaces -----

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
            // query (so a code/enum-mirror column can show a localized choice). See ListSpec.Option.
            List<Map<String, Object>> options = new ArrayList<>();
            for (ResolvedListView.Option o : f.options()) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("value", o.value());
                opt.put("label", o.label());
                // @EnumLabel(color=…) hex, so the control tints the choice like the status pills.
                if (!o.color().isBlank()) {
                    opt.put("color", o.color());
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

    // ----- record header actions -----

    /**
     * One record-header action (rendered by the entity form's action cluster). {@code tone}
     * is {@code "primary"} (solid success), {@code "accent"} (solid brand), {@code "danger"}
     * (Delete) or {@code "normal"} (neutral); {@code placement} is {@code "primary"} (inline
     * button) or {@code "menu"} (overflow ⋯). {@code icon} is a kebab-case lucide name.
     */
    /** {@code form} (may be empty): an action-form dialog's field descriptors — the client collects
     *  them in a modal before POSTing the action (see {@code ActionSpec.ActionBuilder#form}). */
    public record HeaderAction(String icon, String label, String tone, String url, String placement,
                               List<Map<String, Object>> form) {
        public HeaderAction(String icon, String label, String tone, String url, String placement) {
            this(icon, label, tone, url, placement, List.of());
        }
    }

    // ----- record form -----

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
     * {@link HeaderAction}s as the plain-JSON items the record surface's action cluster
     * consumes ({@code label / icon / url / tone / placement} + optional {@code form}) —
     * carried in the entity-form descriptor's {@code actions}.
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
            if (a.form() != null && !a.form().isEmpty()) {
                // The island opens a modal collecting these fields before it POSTs the action.
                m.put("form", a.form());
            }
            list.add(m);
        }
        return list;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * The entity's display heading: its {@code title} when set, else the URL-safe
     * {@code name}. Keeps localized/multi-word titles out of routes while still showing
     * them in list/report headers and the record form title.
     */
    private static String titleOf(Map<String, Object> meta) {
        String title = str(meta.get("title"));
        return title.isBlank() ? str(meta.get("name")) : title;
    }
}
