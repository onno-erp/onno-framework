package su.onno.desktop;

/**
 * An authored desktop shell — the structural peer of {@link su.onno.ui.Layout}.
 * One {@code DesktopApp} bean describes how this onno application presents itself
 * as a native window: its title, window geometry, system-tray behaviour and the
 * splash shown while the embedded server boots.
 *
 * <p>The shell itself (Tauri) ships generic and dumb: at launch it boots the
 * embedded JVM, polls {@code /api/desktop/ready}, then asks the server for this
 * manifest via {@code /api/desktop/manifest} and draws the window accordingly.
 * That keeps window configuration as config-as-code — a single typed source of
 * truth in Java — instead of a hand-edited {@code tauri.conf.json}.</p>
 *
 * <pre>
 * &#64;Component
 * class RentalsDesktop implements DesktopApp {
 *     public void configure(DesktopSpec app) {
 *         app.title("Rentals ERP")
 *            .window(w -> w.size(1400, 900).minSize(1024, 720))
 *            .singleInstance(true)
 *            .tray(t -> t.tooltip("Rentals").quit("Quit"))
 *            .splash("Starting Rentals ERP…");
 *     }
 * }
 * </pre>
 *
 * <p>If no {@code DesktopApp} bean is present, a sensible default is supplied
 * (application name as title, 1280&times;800), so dropping the starter on the
 * classpath is enough to ship a window.</p>
 */
public interface DesktopApp {

    /** Build this application's window title, geometry, tray and splash. */
    void configure(DesktopSpec spec);
}
