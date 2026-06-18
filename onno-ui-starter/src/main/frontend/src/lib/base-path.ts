// The SPA can be mounted under a configurable URL prefix (server property `onno.ui.path`, default
// `/ui`). The server bakes the live value into the served index.html as `window.__onnoBasePath`,
// replacing the `__ONNO_BASE_PATH__` placeholder, so the client knows its mount prefix synchronously
// — before React Router constructs (its `basename` is fixed at mount) and before any deep link is
// resolved. In dev (the Vite server doesn't template index.html) the placeholder is left untouched,
// so we fall back to the web root, which is where the dev server serves the app.

declare global {
  interface Window {
    __onnoBasePath?: string;
  }
}

// The literal left in index.html until the server substitutes it. Seeing it verbatim means no
// server-side injection happened (dev, or an un-built shell) → treat the app as root-mounted.
const PLACEHOLDER = "__ONNO_BASE_PATH__";

function resolveBasePath(): string {
  const raw = window.__onnoBasePath;
  if (!raw || raw === PLACEHOLDER) return "/";
  let p = raw.trim();
  if (!p) return "/";
  if (!p.startsWith("/")) p = "/" + p;
  // React Router's basename wants no trailing slash; "/ui/" and "/ui" must behave the same.
  while (p.length > 1 && p.endsWith("/")) p = p.slice(0, -1);
  return p || "/";
}

/**
 * The router basename and URL prefix the app is mounted under: `"/"` at the web root, otherwise the
 * configured path (e.g. `"/ui"`). Used as `<BrowserRouter basename>` — React Router then strips this
 * prefix from `useLocation().pathname`, so all in-app routing (and the `/api/divkit{path}` fetch it
 * drives) stays prefix-relative while the browser URL carries the prefix.
 */
export const BASE_PATH = resolveBasePath();

const HAS_BASE = BASE_PATH !== "/";

/**
 * Prefix a router-relative path with the base path to form an absolute browser path. Shareable deep
 * links must include the prefix so they cold-load inside the router's basename (a bare `/catalogs/x`
 * would land outside it). A no-op when the app is mounted at the root.
 */
export function withBasePath(path: string): string {
  if (!HAS_BASE) return path;
  return BASE_PATH + (path.startsWith("/") ? path : "/" + path);
}

/**
 * Strip the base path from a raw `window.location.pathname`, yielding the router-relative path (what
 * `useLocation().pathname` returns). For the few spots that read `window.location` directly instead
 * of going through React Router. A no-op when the app is mounted at the root.
 */
export function stripBasePath(pathname: string): string {
  if (!HAS_BASE) return pathname;
  if (pathname === BASE_PATH) return "/";
  if (pathname.startsWith(BASE_PATH + "/")) return pathname.slice(BASE_PATH.length) || "/";
  return pathname;
}
