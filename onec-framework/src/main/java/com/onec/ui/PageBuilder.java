package com.onec.ui;

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
    // Hosts the widgets so we reuse the existing WidgetBuilder/WidgetConfig DSL.
    private final UiLayoutBuilder widgetHost = new UiLayoutBuilder();
    private final List<PageComponent> components = new ArrayList<>();
    // Server handlers for the page's action buttons, resolved by key when a button posts back.
    private final List<ActionSpec.Action> pageActions = new ArrayList<>();

    public PageBuilder title(String title) {
        this.title = title;
        return this;
    }

    public PageBuilder subtitle(String subtitle) {
        this.subtitle = subtitle;
        return this;
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
     * Add the app-settings editor — the {@code @Constant} values rendered as toggles/inputs and
     * saved in place. A page (e.g. the Settings page) composes this alongside widgets and lists, so
     * settings are just another page built from the framework's primitives.
     */
    public PageBuilder constants() {
        return constants("", new String[0]);
    }

    /** Add the settings editor under an optional section heading. */
    public PageBuilder constants(String heading) {
        return constants(heading, new String[0]);
    }

    /**
     * Add the settings editor under an optional section heading, restricted to the named
     * {@code @Constant}s (by their {@code @Constant(name=...)} logical name). With no names the
     * whole editor is shown; with names you can drop a single toggle (or a small group) onto any
     * page — a dashboard, or a catalog page authored at that route — not just the Settings page.
     */
    public PageBuilder constants(String heading, String... names) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", heading == null ? "" : heading);
        if (names != null && names.length > 0) {
            payload.put("names", List.of(names));
        }
        components.add(PageComponent.custom("onec-constants", payload));
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
     * <p>A button's server handler runs only for an authenticated user; because a page action has
     * no entity to gate on, the handler enforces its own authorization via {@code ctx.user()}.</p>
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
            buttons.add(m);
        }
        components.add(PageComponent.actions(heading, buttons));
        return this;
    }

    /** Add a {@code div-custom} extension block (chart, kanban, ...). */
    public PageBuilder custom(String customType, Map<String, Object> payload) {
        components.add(PageComponent.custom(customType, payload));
        return this;
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

    // ----- build accessors (consumed by the renderer) -----

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
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
