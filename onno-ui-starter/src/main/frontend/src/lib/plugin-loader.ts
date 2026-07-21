import { installPluginHost } from "./plugin-host";

/**
 * Loads consumer widget plugins listed in `AppConfig.pluginScripts` (served by the backend from
 * `classpath:/onno-plugins/*.js`). Each URL is a standalone ESM module that, on evaluation, calls
 * `window.onno.registerWidget(type, Component)`. `registerWidget` re-publishes to already-mounted
 * hosts, so a plugin that finishes after DivKit has rendered still fills in its widget — load order
 * and timing are not load-bearing.
 *
 * A failing plugin (network error, throw at module scope) is logged and skipped; it never blocks the
 * others or the app. The widget host shows a labelled placeholder for any type left unregistered.
 */
/**
 * Inject a `<link rel="stylesheet">` for each widget stylesheet (`AppConfig.pluginStyles`, emitted by
 * the Gradle plugin's Tailwind pass). Idempotent — a URL already linked is skipped, so a config
 * refetch or HMR re-run doesn't stack duplicate links. Call before {@link loadPlugins} so the CSS is
 * present by the time a widget module renders.
 *
 * The links go BEFORE the host's own stylesheets, never appended to the end of <head>. Plugin CSS is
 * a second, unscoped Tailwind utilities pass over the widget sources; utilities the host also emits
 * are byte-identical, but the plugin sheet only has the variants the widgets use. Loaded after the
 * host sheet, a plugin's bare `.flex-col` would win the cascade tie over the host's
 * `@media … .sm:flex-row` on any host element carrying both classes (that broke the desktop
 * date-range popover into a stacked column). Loaded first, host rules win ties on host markup while
 * widget-only utilities still apply inside widgets.
 */
export function injectPluginStyles(urls: string[] | undefined | null): void {
  if (!urls || urls.length === 0) return;
  for (const url of urls) {
    if (document.querySelector(`link[data-onno-plugin-style="${CSS.escape(url)}"]`)) continue;
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = url;
    link.dataset.onnoPluginStyle = url;
    // First host style in <head>: the built app's <link> or, in Vite dev, its injected <style>.
    const hostStyle = document.head.querySelector(
      'link[rel="stylesheet"]:not([data-onno-plugin-style]), style'
    );
    document.head.insertBefore(link, hostStyle);
  }
}

export async function loadPlugins(urls: string[] | undefined | null): Promise<void> {
  // Make sure window.onno exists before any plugin module evaluates and reaches for it.
  installPluginHost();
  if (!urls || urls.length === 0) return;
  await Promise.all(
    urls.map(async (url) => {
      try {
        // Absolute, server-served module URL — outside Vite's module graph, so don't let it try to
        // resolve/transform this import at build time.
        await import(/* @vite-ignore */ url);
      } catch (err) {
        console.error(`[onno] failed to load widget plugin: ${url}`, err);
      }
    })
  );
}
