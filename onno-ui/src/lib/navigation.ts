/**
 * How any component asks the workspace to open or close a screen. The shell is the one listener
 * ({@link listenForNavigation}); everything else calls {@link openRoute} — with an `onno://` link
 * (the deep-link protocol web and mobile clients share) or an app route.
 */

import { withBasePath } from "./base-path";

const OPEN = "onno:open";
const CLOSE = "onno:close";

/** Router-relative paths; protocol-relative URLs are not application routes. */
export function isAppRoute(route: string): boolean {
  return route.startsWith("/") && !route.startsWith("//") && !route.includes("\\");
}

const RECORD_URL = /^onno:\/\/(catalogs|documents|registers|information-registers)\/[^/?#\\]+(\/[^/?#\\]+)?$/;

/**
 * A pure navigation target for a command result: an application path or an
 * `onno://{kind}/{name}[/{id}]` surface URL. Command URLs (logout, delete/, action/, redirect/,
 * open/, download/) are refused, mirroring the server-side check.
 */
export function isNavigationTarget(route: string): boolean {
  if (route === "onno://") return true; // home
  return route.startsWith("onno://") ? RECORD_URL.test(route) : isAppRoute(route);
}

/**
 * A route a link may follow: an app path without control characters or dot segments (native links
 * resolve those before following the URL), or a surface `onno://` link (see {@link isNavigationTarget}).
 */
export function isSafeRoute(route: string): boolean {
  if (route.startsWith("onno://")) return isNavigationTarget(route.split("?", 1)[0]) || /^onno:\/\/(catalogs|documents)\/[^/?#\\]+\/new(\?.*)?$/.test(route);
  return isAppRoute(route)
    && !/[\x00-\x1f\x7f]/.test(route)
    && !/(?:^|\/)(?:\.|%2e){1,2}(?:\/|$)/i.test(route.split(/[?#]/, 1)[0]);
}

/** The browser URL of a route (an `onno://` link becomes its app path), under the UI's base path. */
export function routeHref(route: string): string | undefined {
  if (!isSafeRoute(route)) return undefined;
  return withBasePath(route.startsWith("onno://") ? "/" + route.slice("onno://".length) : route);
}

/**
 * Open an `onno://` link or an app route through the workspace, preserving its tabs, panes and
 * command handling — or, with `newTab`, in a new browser tab under the UI's base path.
 */
export function openRoute(route: string, options?: { newTab?: boolean }): void {
  if (!route.startsWith("onno://") && !isAppRoute(route)) return;
  if (options?.newTab) {
    const href = routeHref(route);
    if (href) window.open(href, "_blank", "noopener,noreferrer");
    return;
  }
  window.dispatchEvent(new CustomEvent<string>(OPEN, { detail: route }));
}

/** Close the workspace tab showing `path` (a form after save or cancel). */
export function closeRoute(path: string): void {
  window.dispatchEvent(new CustomEvent<string>(CLOSE, { detail: path }));
}

/** The shell's side: handle every {@link openRoute} and {@link closeRoute}. Returns the unsubscribe. */
export function listenForNavigation(handlers: { open: (route: string) => void; close: (path: string) => void }): () => void {
  const onOpen = (e: Event) => {
    const route = (e as CustomEvent<unknown>).detail;
    if (typeof route === "string") handlers.open(route);
  };
  const onClose = (e: Event) => {
    const path = (e as CustomEvent<unknown>).detail;
    if (typeof path === "string") handlers.close(path);
  };
  window.addEventListener(OPEN, onOpen);
  window.addEventListener(CLOSE, onClose);
  return () => {
    window.removeEventListener(OPEN, onOpen);
    window.removeEventListener(CLOSE, onClose);
  };
}
