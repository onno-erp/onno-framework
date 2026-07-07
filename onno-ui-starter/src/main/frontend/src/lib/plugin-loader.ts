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
