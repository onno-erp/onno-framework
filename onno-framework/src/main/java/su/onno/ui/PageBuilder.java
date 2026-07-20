package su.onno.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Composes a {@link Page}'s content: an optional header, a grid of dashboard
 * widgets, and freeform {@link PageComponent} blocks. Widgets reuse the same
 * builder as {@code UiLayoutBuilder.widget(...)}, so the full widget config
 * (type, entity, calendar/kanban/chart options) is available here.
 *
 * <p>Rendered order is header → widget grid → components.</p>
 */
public final class PageBuilder {

    private String title;
    private String subtitle;
    // Whether to render the page header (title/subtitle row). On by default; a bare page
    // (e.g. a dashboard that leads with its own hero widget) suppresses it via bare().
    private boolean header = true;
    // Hosts the widgets so we reuse the existing WidgetBuilder/WidgetConfig DSL.
    private final UiLayoutBuilder widgetHost = new UiLayoutBuilder();
    private final List<PageComponent> components = new ArrayList<>();
    // Server handlers for the page's action buttons, resolved by key when a button posts back.
    private final List<ActionSpec.Action> pageActions = new ArrayList<>();
    // An optional right rail (aside). Blocks added here render in a narrow column beside the main
    // content on desktop, and stacked below it on mobile. Lazily created on first aside(...) call.
    private PageBuilder aside;
    // Explicit multi-column layout bands. Each row splits into columns of any width; a column is a
    // nested region (further rows/widgets/lists). Rendered after the widget grid and components.
    private final List<PageRow> rows = new ArrayList<>();

    public PageBuilder title(String title) {
        this.title = title;
        return this;
    }

    public PageBuilder subtitle(String subtitle) {
        this.subtitle = subtitle;
        return this;
    }

    /**
     * Show or hide the page header — the title/subtitle row the framework renders above the
     * content. On by default. Hide it for a page that supplies its own heading (a hero widget,
     * a custom banner) or a chrome-less surface. {@code bare()} is the shorthand for {@code
     * header(false)}.
     */
    public PageBuilder header(boolean show) {
        this.header = show;
        return this;
    }

    /** Drop the page header entirely — shorthand for {@code header(false)}. */
    public PageBuilder bare() {
        return header(false);
    }

    /** Add a dashboard widget; returns the widget builder for further config. */
    public UiLayoutBuilder.WidgetBuilder widget(String title) {
        return widgetHost.widget(title);
    }

    /** Add a freeform text block. */
    public PageBuilder text(String text) {
        components.add(PageComponent.text(text));
        return this;
    }

    /**
     * Add a section of action buttons — each runs an обработка-style server handler (or routes the
     * client) when clicked. Reuses the same {@link ActionSpec} DSL as entity actions, but the
     * buttons live on the page itself rather than a list toolbar, so triggering backend logic is a
     * first-class page primitive:
     *
     * <pre>
     * b.actions("Reports", a -> {
     *     a.action("createDrafts").label("Create draft reports").icon("file-plus")
     *      .handler(ctx -> { reports.createDrafts(); return ActionResult.refresh("Drafts created"); });
     *     a.action("postPending").label("Post pending drafts").icon("send")
     *      .handler(ctx -> { reports.postPending(); return ActionResult.message("Posted"); });
     * });
     * </pre>
     *
     * <p>A button's server handler runs only for an authenticated user. Because a page action has
     * no entity to gate on, declare {@code .roles("MANAGER")} to restrict who may run (and see) it;
     * without roles, any authenticated user may run it and the handler enforces its own finer
     * authorization via {@code ctx.user()}.</p>
     */
    public PageBuilder actions(String heading, Consumer<ActionSpec> configurer) {
        ActionSpec spec = new ActionSpec();
        configurer.accept(spec);
        List<ActionSpec.Action> resolved = spec.actions();
        pageActions.addAll(resolved);

        List<Map<String, Object>> buttons = new ArrayList<>();
        for (ActionSpec.Action a : resolved) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a.key());
            m.put("label", a.label());
            m.put("icon", a.icon());
            if (a.logo() != null && !a.logo().isBlank()) {
                m.put("logo", a.logo());
            }
            m.put("server", a.isServer());
            if (!a.isServer()) {
                m.put("url", a.navigateUrl());
            }
            if (a.hasForm()) {
                m.put("form", actionFormDescriptors(a));
                m.put("formDialog", actionFormDialogDescriptor(a));
            }
            if (!a.roles().isEmpty()) {
                m.put("roles", a.roles());
            }
            buttons.add(m);
        }
        components.add(PageComponent.actions(heading, buttons));
        return this;
    }

    private static List<Map<String, Object>> actionFormDescriptors(ActionSpec.Action action) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InputSpec.InputField field : action.form()) {
            Map<String, Object> m = inputFieldDescriptor(field);
            m.put("kind", "field");
            out.add(m);
        }
        for (InputSpec.InputGroup group : action.formGroups()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "group");
            m.put("key", group.key());
            m.put("label", group.label());
            m.put("required", group.required());
            m.put("columns", group.columns().stream().map(PageBuilder::inputFieldDescriptor).toList());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> inputFieldDescriptor(InputSpec.InputField field) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", field.key());
        m.put("label", field.label());
        m.put("type", field.type().name().toLowerCase());
        m.put("placeholder", field.placeholder());
        m.put("options", field.options());
        m.put("value", field.defaultValue());
        m.put("required", field.required());
        if (field.reference() != null) {
            m.put("reference", field.reference());
            m.put("refKind", field.refKind());
        }
        return m;
    }

    private static Map<String, Object> actionFormDialogDescriptor(ActionSpec.Action action) {
        InputSpec.ActionFormDialog d = action.formDialog();
        if (d == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        if (d.title() != null && !d.title().isBlank()) m.put("title", d.title());
        if (d.description() != null && !d.description().isBlank()) m.put("description", d.description());
        if (d.submitLabel() != null && !d.submitLabel().isBlank()) m.put("submitLabel", d.submitLabel());
        if (d.cancelLabel() != null && !d.cancelLabel().isBlank()) m.put("cancelLabel", d.cancelLabel());
        if (d.icon() != null && !d.icon().isBlank()) m.put("icon", d.icon());
        if (d.tone() != null) m.put("tone", d.tone().name().toLowerCase());
        if (d.size() != null) m.put("size", d.size().name().toLowerCase());
        return m;
    }

    /** Add a {@code div-custom} extension block (chart, kanban, ...). */
    public PageBuilder custom(String customType, Map<String, Object> payload) {
        components.add(PageComponent.custom(customType, payload));
        return this;
    }

    /**
     * Compose a right rail beside the main content — a narrow side column for stats, filters, or a
     * summary that sits next to a list rather than stacked above it. The rail takes the same block
     * DSL as the page itself ({@code widget}, {@code text}, {@code constants}, {@code custom}), so
     * you can drop stat tiles to the right of a list surface:
     *
     * <pre>
     * b.list(Order.class);                                  // main
     * b.aside(a -> {
     *     a.widget("Open").type("count").document(Order.class).config("filter", "open = true");
     *     a.widget("Revenue").type("metric").document(Order.class).config("metric", "sum")...;
     * });
     * </pre>
     *
     * <p>Desktop lays the rail out to the right of the main content (which flexes to fill the rest);
     * mobile stacks it below. Repeated calls extend the same rail. Widgets in the rail always stack
     * one-per-row (it is a narrow column). A nested {@code aside(...)} inside the rail is ignored.</p>
     */
    public PageBuilder aside(Consumer<PageBuilder> configurer) {
        if (aside == null) {
            aside = new PageBuilder();
        }
        configurer.accept(aside);
        return this;
    }

    /**
     * Add a multi-column layout band — the general layout primitive. Split the page into columns of
     * any width and compose any block in each; nest further rows inside a column for arbitrary
     * structure. Columns lay out side by side on desktop and stack on mobile.
     *
     * <pre>
     * b.row(r -> {
     *     r.col("2/3", c -> c.list(Order.class));            // main
     *     r.col("1/3", c -> {                                 // side
     *         c.widget("Open").type("count").document(Order.class).config("filter", "open = true");
     *         c.widget("Revenue").type("metric").document(Order.class)
     *          .config("metric", "sum").config("metricField", "total");
     *     });
     * });
     * </pre>
     *
     * <p>Rows render after the page's own widget grid and freeform blocks, in the order added. A
     * column is itself a full {@link PageBuilder}, so it takes every block method (and further
     * {@code row(...)} calls).</p>
     */
    public PageBuilder row(Consumer<RowBuilder> configurer) {
        RowBuilder rb = new RowBuilder();
        configurer.accept(rb);
        rows.add(new PageRow(rb.columns));
        return this;
    }

    /** Builds one {@link PageRow}: a sequence of {@link #col} calls. See {@link #row(Consumer)}. */
    public static final class RowBuilder {
        private final List<PageColumn> columns = new ArrayList<>();

        /** A column of the given {@code width} (fraction like {@code "2/3"}, {@code "<n>px"}, or {@code null}/{@code "full"} for an equal share). */
        public RowBuilder col(String width, Consumer<PageBuilder> configurer) {
            PageBuilder region = new PageBuilder();
            configurer.accept(region);
            columns.add(new PageColumn(width, region));
            return this;
        }

        /** A column that takes an equal share of the row. */
        public RowBuilder col(Consumer<PageBuilder> configurer) {
            return col(null, configurer);
        }
    }

    /**
     * Embed the full interactive list of a catalog/document — the same surface as its own route,
     * with the New button, custom action buttons, search/sort and rows that open a detail beside
     * the page. Lets a page (e.g. Settings) manage reference data inline. {@code entity} is the
     * catalog or document class.
     */
    public PageBuilder list(Class<?> entity) {
        components.add(PageComponent.list(entity));
        return this;
    }

    /**
     * Embed an entity list opened on a <b>default view</b> — a preset filter, grouping, and/or sort
     * the list starts from (the viewer can still change them, and the filter is a base constraint on
     * the feed). Reuses the same {@code col op value} filter grammar as dashboard widgets:
     *
     * <pre>
     * b.list(Order.class, v -> v.filter("open = true")
     *                           .groupBy("status_display")
     *                           .sort("_date", true));   // newest first
     * </pre>
     */
    public PageBuilder list(Class<?> entity, Consumer<ListDefaults> configurer) {
        ListDefaults defaults = new ListDefaults();
        configurer.accept(defaults);
        components.add(PageComponent.list(entity, defaults.build()));
        return this;
    }

    /**
     * Preset view for an embedded {@link #list(Class, Consumer)} — a base filter, a group-by column,
     * and a sort. Every part is optional; only what's set is applied.
     */
    public static final class ListDefaults {
        private String filter;
        private String groupBy;
        private String sortColumn;
        private boolean sortDescending;

        /** A base filter constraint on the feed, in the {@code col op value} grammar (AND-chained). */
        public ListDefaults filter(String filter) {
            this.filter = filter;
            return this;
        }

        /** Open the list grouped by this column (a column the list declares groupable). */
        public ListDefaults groupBy(String column) {
            this.groupBy = column;
            return this;
        }

        /** Sort ascending by this column. */
        public ListDefaults sort(String column) {
            return sort(column, false);
        }

        /** Sort by this column, descending when {@code descending} is true. */
        public ListDefaults sort(String column, boolean descending) {
            this.sortColumn = column;
            this.sortDescending = descending;
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (filter != null && !filter.isBlank()) {
                m.put("filter", filter);
            }
            if (groupBy != null && !groupBy.isBlank()) {
                m.put("groupBy", groupBy);
            }
            if (sortColumn != null && !sortColumn.isBlank()) {
                m.put("sort", sortColumn);
                m.put("sortDescending", sortDescending);
            }
            return m;
        }
    }

    // ----- build accessors (consumed by the renderer) -----

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    /** Whether the renderer should emit the header row (title/subtitle). */
    public boolean showHeader() {
        return header;
    }

    /** The right-rail sub-builder, or {@code null} if the page composed no {@code aside(...)}. */
    public PageBuilder aside() {
        return aside;
    }

    /** The explicit layout rows composed with {@code row(...)}, in declaration order. */
    public List<PageRow> rows() {
        return List.copyOf(rows);
    }

    public List<UiLayoutBuilder.WidgetConfig> widgets() {
        return widgetHost.buildWidgets();
    }

    public List<PageComponent> components() {
        return List.copyOf(components);
    }

    /** The page's action-button handlers, in declaration order (resolved by key on post-back). */
    public List<ActionSpec.Action> pageActions() {
        return List.copyOf(pageActions);
    }

    /** The page action with this key, or {@code null} if the page declares none. */
    public ActionSpec.Action pageAction(String key) {
        return pageActions.stream().filter(a -> a.key().equals(key)).findFirst().orElse(null);
    }
}
