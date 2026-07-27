package su.onno.ui;

import su.onno.ui.divkit.Palette;

import java.util.List;
import java.util.Map;

/** Optional chrome supplied by a UI feature pack. */
public interface ShellUiContributor {

    /** Trailing block in a sidebar/mobile navigation row. */
    default Map<String, Object> navTrailing(String path, Palette palette) {
        return null;
    }

    /** Overlay placed over a bottom-tab glyph. */
    default Map<String, Object> bottomTabOverlay(String path, Palette palette) {
        return null;
    }

    /** Feature-owned rows/groups inserted near the top of the mobile More menu. */
    default List<Map<String, Object>> mobileMenuItems(Palette palette, UiMessages messages) {
        return List.of();
    }
}
