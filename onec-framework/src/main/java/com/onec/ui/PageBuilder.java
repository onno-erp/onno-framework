package com.onec.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return constants("");
    }

    /** Add the settings editor under an optional section heading. */
    public PageBuilder constants(String heading) {
        components.add(PageComponent.custom("onec-constants",
                Map.of("title", heading == null ? "" : heading)));
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
}
