/** Router-relative paths; protocol-relative URLs are not application routes. */
export function isAppRoute(route: string): boolean {
  return route.startsWith("/") && !route.startsWith("//") && !route.includes("\\");
}

const RECORD_URL = /^onno:\/\/(catalogs|documents|registers|information-registers)\/[^/?#\\]+(\/[^/?#\\]+)?$/;

/**
 * A pure navigation target for a server action result: an application path or an
 * `onno://{kind}/{name}[/{id}]` surface URL. Command URLs (logout, delete/, action/, redirect/,
 * open/, download/) are refused, mirroring the server-side `ActionResult` check.
 */
export function isNavigationTarget(route: string): boolean {
  if (route === "onno://") return true; // home
  return route.startsWith("onno://") ? RECORD_URL.test(route) : isAppRoute(route);
}

/** Open a route through the workspace host, preserving its tabs, panes and command handling. */
export function openRoute(route: string): void {
  if (!route.startsWith("onno://") && !isAppRoute(route)) return;
  window.dispatchEvent(new CustomEvent<string>("onno:action", { detail: route }));
}
