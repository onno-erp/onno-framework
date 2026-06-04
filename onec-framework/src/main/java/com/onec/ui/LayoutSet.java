package com.onec.ui;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The resolved {@link UiLayout} for each {@link Viewport}. Built once at startup
 * from the authored {@link Layout} beans: a viewport-specific layout replaces the
 * universal one for the same profile on that device. The shell endpoint picks the
 * entry matching the client's reported viewport.
 */
public final class LayoutSet {

    private final Map<Viewport, UiLayout> byViewport;
    // Viewports a device-specific default layout actually targets ({@code viewport()==vp}). A
    // viewport not in this set is served the universal layout by fallback — the shell uses this
    // to avoid inheriting a desktop nav style (e.g. a sidebar) onto a phone with no layout of
    // its own, which is what made single-layout apps render non-responsively.
    private final Set<Viewport> dedicated;

    public LayoutSet(Map<Viewport, UiLayout> byViewport, Set<Viewport> dedicated) {
        this.byViewport = new EnumMap<>(byViewport);
        this.dedicated = dedicated.isEmpty()
                ? EnumSet.noneOf(Viewport.class)
                : EnumSet.copyOf(dedicated);
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

    /**
     * Whether a {@link Layout} bean specifically targets this viewport (rather than being
     * served the universal layout by fallback). When false for a non-desktop viewport, the
     * shell uses a device-appropriate nav default instead of inheriting the universal one.
     */
    public boolean hasDedicated(Viewport viewport) {
        return dedicated.contains(viewport);
    }
}
