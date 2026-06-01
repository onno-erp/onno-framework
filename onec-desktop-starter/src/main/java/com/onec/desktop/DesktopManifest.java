package com.onec.desktop;

/**
 * The immutable window description the Tauri shell fetches from
 * {@code /api/desktop/manifest} at boot. Plain records so Jackson serialises
 * them to the JSON the shell consumes.
 */
public record DesktopManifest(
        String title,
        boolean singleInstance,
        String splash,
        Window window,
        Tray tray) {

    public record Window(
            int width,
            int height,
            int minWidth,
            int minHeight,
            boolean resizable,
            boolean center,
            boolean maximized) {
    }

    public record Tray(
            boolean enabled,
            String tooltip,
            String quit) {
    }
}
