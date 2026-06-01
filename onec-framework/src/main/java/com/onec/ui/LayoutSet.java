package com.onec.ui;

import java.util.EnumMap;
import java.util.Map;

/**
 * The resolved {@link UiLayout} for each {@link Viewport}. Built once at startup
 * from the authored {@link Layout} beans: a viewport-specific layout replaces the
 * universal one for the same profile on that device. The shell endpoint picks the
 * entry matching the client's reported viewport.
 */
public final class LayoutSet {

    private final Map<Viewport, UiLayout> byViewport;

    public LayoutSet(Map<Viewport, UiLayout> byViewport) {
        this.byViewport = new EnumMap<>(byViewport);
    }

    /** The layout for the given viewport, falling back to desktop then any present. */
    public UiLayout forViewport(Viewport viewport) {
        UiLayout layout = byViewport.get(viewport);
        if (layout != null) {
            return layout;
        }
        UiLayout desktop = byViewport.get(Viewport.DESKTOP);
        if (desktop != null) {
            return desktop;
        }
        return byViewport.values().iterator().next();
    }
}
