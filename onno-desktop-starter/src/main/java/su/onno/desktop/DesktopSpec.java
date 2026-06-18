package su.onno.desktop;

import java.util.function.Consumer;

/**
 * The builder a {@link DesktopApp} configures. Mirrors the
 * {@link su.onno.ui.LayoutSpec} style: fluent top-level setters plus nested
 * builders supplied as lambdas. Produces an immutable {@link DesktopManifest}
 * the shell reads at boot.
 */
public final class DesktopSpec {

    private String title = "";
    private boolean singleInstance = true;
    private String splash = "";
    private final WindowSpec window = new WindowSpec();
    private final TraySpec tray = new TraySpec();

    /** The window/title-bar text. Defaults to the Spring application name. */
    public DesktopSpec title(String title) {
        this.title = title;
        return this;
    }

    /** When {@code true} (default) a second launch focuses the running window. */
    public DesktopSpec singleInstance(boolean singleInstance) {
        this.singleInstance = singleInstance;
        return this;
    }

    /** Message shown on the splash while the embedded server starts. */
    public DesktopSpec splash(String splash) {
        this.splash = splash;
        return this;
    }

    /** Configure window geometry. */
    public DesktopSpec window(Consumer<WindowSpec> customizer) {
        customizer.accept(window);
        return this;
    }

    /** Configure the system-tray icon and menu. */
    public DesktopSpec tray(Consumer<TraySpec> customizer) {
        tray.enabled = true;
        customizer.accept(tray);
        return this;
    }

    DesktopManifest build(String fallbackTitle) {
        String resolvedTitle = title.isBlank() ? fallbackTitle : title;
        return new DesktopManifest(
                resolvedTitle,
                singleInstance,
                splash.isBlank() ? "Starting " + resolvedTitle + "…" : splash,
                window.build(),
                tray.build(resolvedTitle));
    }

    /** Window geometry builder. */
    public static final class WindowSpec {
        private int width = 1280;
        private int height = 800;
        private int minWidth = 0;
        private int minHeight = 0;
        private boolean resizable = true;
        private boolean center = true;
        private boolean maximized = false;

        public WindowSpec size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public WindowSpec minSize(int minWidth, int minHeight) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            return this;
        }

        public WindowSpec resizable(boolean resizable) {
            this.resizable = resizable;
            return this;
        }

        public WindowSpec center(boolean center) {
            this.center = center;
            return this;
        }

        public WindowSpec maximized(boolean maximized) {
            this.maximized = maximized;
            return this;
        }

        DesktopManifest.Window build() {
            return new DesktopManifest.Window(width, height, minWidth, minHeight,
                    resizable, center, maximized);
        }
    }

    /** System-tray builder. */
    public static final class TraySpec {
        private boolean enabled = false;
        private String tooltip = "";
        private String quit = "Quit";

        public TraySpec tooltip(String tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        /** Label for the tray's quit menu item. */
        public TraySpec quit(String quit) {
            this.quit = quit;
            return this;
        }

        DesktopManifest.Tray build(String fallbackTooltip) {
            return new DesktopManifest.Tray(enabled,
                    tooltip.isBlank() ? fallbackTooltip : tooltip, quit);
        }
    }
}
