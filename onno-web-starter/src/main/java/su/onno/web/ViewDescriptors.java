package su.onno.web;

import su.onno.command.CommandRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plain-JSON descriptors the React client renders: the list grid ({@code list}), a record's
 * built-in actions, and the register surface. Data only — no icons, placement or tones; every
 * client decides presentation itself (see {@link UiViewController}). Entity commands travel as
 * {@link CommandRegistry} descriptors.
 */
public final class ViewDescriptors {

    private ViewDescriptors() {}

    /**
     * An entity's default list descriptor (columns, filters, routes, commands) the React list
     * component consumes — without the surface wrapping, so a client can embed it in any page. It
     * opens unsorted (the query's default order), searchable, flat; the client customizes the rest.
     */
    public static Map<String, Object> listDescriptor(DefaultListView view, String kind, String name,
                                                     String newUrl, boolean canWrite,
                                                     List<Map<String, Object>> commands) {
        return listDescriptor(view, kind, name, newUrl, canWrite, commands, false);
    }

    /**
     * @param commands        the entity's command descriptors (record and collection) the caller may run
     * @param dynamicCommands whether some commands are late-bound, so the client refetches
     *                        {@code GET /api/commands/{kind}/{name}} before offering them
     */
    public static Map<String, Object> listDescriptor(DefaultListView view, String kind, String name,
                                                     String newUrl, boolean canWrite,
                                                     List<Map<String, Object>> commands,
                                                     boolean dynamicCommands) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (DefaultListView.Column c : view.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("columnName", c.columnName());
            col.put("fieldName", c.fieldName());
            col.put("label", c.label());
            // Presentation hints only a server-side view sets; the model's default list carries
            // none and the client applies the app's customization instead.
            putIfSet(col, "width", c.width());
            putIfSet(col, "widget", c.widget());
            putIfSet(col, "format", c.format());
            putIfSet(col, "hint", c.hint());
            // The value type (text/number/boolean/date/enum/ref): a client filter's default control.
            putIfSet(col, "type", c.type());
            columns.add(col);
        }
        Map<String, Object> sort = new LinkedHashMap<>();
        sort.put("column", null);
        sort.put("descending", false);

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", kind);
        descriptor.put("name", name);
        descriptor.put("title", view.title());
        descriptor.put("columns", columns);
        descriptor.put("searchable", true);
        descriptor.put("sort", sort);
        descriptor.put("filters", filters(view.filters()));
        descriptor.put("newUrl", newUrl);
        // The caller's write access on the entity, so the island hides its write affordances (row
        // Edit/Duplicate/Delete, batch delete) for read-only viewers. REST enforces regardless.
        descriptor.put("canWrite", canWrite);
        descriptor.put("commands", commands == null ? List.of() : commands);
        if (dynamicCommands) {
            // The client refetches the current descriptors before offering commands. Omitted for
            // static-only entities so their zero-request path stays intact.
            descriptor.put("dynamicCommands", true);
        }
        // Keyset window size (onno.web.list.page-size).
        descriptor.put("pageSize", view.pageSize());
        return descriptor;
    }

    /**
     * Filter controls the island renders in the toolbar and folds into the {@code /api/list} query.
     * Each option travels as {@code {value, label}} (plus an {@code @EnumLabel} {@code color} or an
     * {@code avatarUrl} when present): the client shows the label and sends the value.
     */
    public static List<Map<String, Object>> filters(List<DefaultListView.Filter> resolved) {
        List<Map<String, Object>> filters = new ArrayList<>();
        for (DefaultListView.Filter f : resolved) {
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("key", f.key());
            filter.put("label", f.label());
            filter.put("column", f.columnName());
            filter.put("type", f.type());
            List<Map<String, Object>> options = new ArrayList<>();
            for (DefaultListView.Option o : f.options()) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("value", o.value());
                opt.put("label", o.label());
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
        return filters;
    }

    /**
     * One built-in operation on a record the caller may perform now: {@code unpost},
     * {@code duplicate}, {@code delete}, {@code openInList}, or an extension module's contribution
     * (its own key, e.g. {@code print-invoice}). {@code title} is set only for contributions — the
     * client names the built-ins; {@code danger} marks an operation that destroys data. Entity
     * commands are not record actions — they travel as command descriptors under {@code commands}.
     */
    public record RecordAction(String key, String title, String url, boolean danger) {

        /** A built-in action the client names and presents by its key. */
        public static RecordAction builtIn(String key, String url) {
            return new RecordAction(key, null, url, false);
        }
    }

    /**
     * The register surface descriptor: one or more named, paginated list views fed page-by-page from
     * {@code /api/list/registers/...}. A BALANCE register carries a Balance + a Movements view; a
     * TURNOVER register just Movements. The client renders the view switch.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> registerDescriptor(Map<String, Object> meta, String name, UiMessages msg) {
        String type = str(meta.get("type"));
        boolean isBalance = "BALANCE".equals(type);
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) meta.getOrDefault("dimensions", List.of());
        List<Map<String, Object>> resources = (List<Map<String, Object>>) meta.getOrDefault("resources", List.of());
        String title = titleOf(meta);

        List<Map<String, Object>> views = new ArrayList<>();
        if (isBalance) {
            views.add(registerView("balance", msg.get("register.balanceTab"),
                    registerListDescriptor(title, name, "/api/list/registers/" + name + "/balance",
                            balanceColumns(dimensions, resources), resourceRangeFilters(resources),
                            firstColumnName(dimensions), false)));
        }
        views.add(registerView("movements", msg.get("register.movementsTab"),
                registerListDescriptor(title, name, "/api/list/registers/" + name + "/movements",
                        movementColumns(dimensions, resources, msg),
                        movementFilters(msg, resources), "_period", true)));

        Map<String, Object> register = new LinkedHashMap<>();
        register.put("title", title);
        register.put("views", views);
        return register;
    }

    /** One named view of a register surface: {@code {key, label, list}} for the React toggle. */
    private static Map<String, Object> registerView(String key, String label, Map<String, Object> list) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("key", key);
        v.put("label", label);
        v.put("list", list);
        return v;
    }

    /** A register list descriptor: no search/New, fed by an explicit feed URL. */
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
        // A register's movement log / balance is append-heavy and depth-scrolled.
        d.put("pageSize", 50);
        // Where the island fetches its windows from (a register has no /api/list/{kind}/{name} route).
        d.put("feed", feed);
        return d;
    }

    /**
     * The movements tab's built-in filter facets: a {@code _period} date range, a
     * {@code _movement_type} Receipt/Expense choice and a from–to number range per numeric resource.
     * All compile server-side through {@link su.onno.web.ListFilter} like any authored list filter
     * (ge/le on the period and the resources, eq on the type), so the register no longer forces
     * scrolling the whole log to find a window of activity.
     */
    private static List<Map<String, Object>> movementFilters(UiMessages msg,
                                                             List<Map<String, Object>> resources) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(filterControl("period", msg.get("register.period"), "_period", "dateRange", List.of()));
        filters.add(filterControl("type", msg.get("register.type"), "_movement_type", "options", List.of(
                filterOption("RECEIPT", msg.get("register.receipt")),
                filterOption("EXPENSE", msg.get("register.expense")))));
        filters.addAll(resourceRangeFilters(resources));
        return filters;
    }

    /** Java simple type names (as register metadata reports them) that persist as numeric columns. */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "BigDecimal", "BigInteger", "Integer", "Long", "Short", "Byte", "Double", "Float",
            "int", "long", "short", "byte", "double", "float");

    /**
     * A from–to number-range facet per numeric register resource (stock below a threshold, debts
     * above an amount) — the balance tab's filters, and appended to the movements tab's.
     */
    private static List<Map<String, Object>> resourceRangeFilters(List<Map<String, Object>> resources) {
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Map<String, Object> r : resources) {
            String column = str(r.get("columnName"));
            if (!column.isBlank() && NUMERIC_TYPES.contains(str(r.get("javaType")))) {
                filters.add(filterControl(column, str(r.get("displayName")), column, "numberRange", List.of()));
            }
        }
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
                                                             UiMessages msg) {
        List<Map<String, Object>> cols = new ArrayList<>();
        // The client formats the movement timestamp (locale date-time by default; an app may set a
        // format through its register customization).
        cols.add(column("_period", msg.get("register.period")));
        cols.add(column("_movement_type", msg.get("register.type")));
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
        return column(str(attr.get("columnName")), str(attr.get("displayName")));
    }

    private static Map<String, Object> column(String columnName, String label) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("columnName", columnName);
        c.put("label", label);
        return c;
    }

    private static String firstColumnName(List<Map<String, Object>> dimensions) {
        return dimensions.isEmpty() ? null : str(dimensions.get(0).get("columnName"));
    }

    /**
     * {@link RecordAction}s as plain JSON ({@code key, url[, title][, danger]}) — the record
     * descriptor's {@code actions}.
     */
    public static List<Map<String, Object>> actionItems(List<RecordAction> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RecordAction a : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a.key());
            m.put("url", a.url());
            if (a.title() != null && !a.title().isBlank()) {
                m.put("title", a.title());
            }
            if (a.danger()) {
                m.put("danger", true);
            }
            list.add(m);
        }
        return list;
    }

    private static void putIfSet(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
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
