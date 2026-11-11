/** Router-relative paths; protocol-relative URLs are not application routes. */
export function isAppRoute(route: string): boolean {
  return route.startsWith("/") && !route.startsWith("//") && !route.includes("\\");
}

/** Open a route through the workspace host, preserving its tabs, panes and command handling. */
export function openRoute(route: string): void {
  if (!route.startsWith("onno://") && !isAppRoute(route)) return;
  window.dispatchEvent(new CustomEvent<string>("onno:action", { detail: route }));
}
