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

    /** Add a {@code div-custom} extension block (chart, kanban, ...). */
    public PageBuilder custom(String customType, Map<String, Object> payload) {
        components.add(PageComponent.custom(customType, payload));
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
