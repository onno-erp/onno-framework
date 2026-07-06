import * as React from "react";
import * as ReactJSXRuntime from "react/jsx-runtime";
import htm from "htm";
import { registerWidget } from "./widget-bridge";
import { api } from "./api";

/**
 * The host globals a consumer's custom-widget plugin binds against at runtime.
 *
 * Plugins are separate ESM modules loaded at boot (see {@link ./plugin-loader}); they are NOT
 * bundled with the SPA. To keep them tiny and — crucially — to make React hooks and context work,
 * a plugin must reuse the *host's single React instance* rather than bundling its own. So we hang
 * that instance (plus the JSX runtime, the widget registry, an html tagged-template, and a
 * read-only data client) on `window.onno`. The `@onno/widget-sdk` package's `react` / `react/jsx-
 * runtime` shims resolve to these fields, so a plugin authored with normal `import ... from "react"`
 * + automatic JSX ships with zero React inside it.
 */
export interface OnnoHost {
  /** The host's React — a plugin's components must use this exact instance (shared hooks/context). */
  React: typeof React;
  /** The host's automatic-JSX runtime (`react/jsx-runtime`), for the SDK's jsx-runtime shim. */
  jsxRuntime: typeof ReactJSXRuntime;
  /** Register (or override) the renderer for a widget type; see {@link registerWidget}. */
  registerWidget: typeof registerWidget;
  /** `htm` bound to `React.createElement` — JSX-like authoring with no build step (Tier-0). */
  html: (strings: TemplateStringsArray, ...values: unknown[]) => unknown;
  /** Read-only slice of the REST client — safe surface for first-party widgets. */
  api: OnnoReadApi;
  /** Host contract version; bump on a breaking change to this shape. */
  version: number;
}

/**
 * The read-only subset of {@link api} exposed to plugins. Deliberately excludes create/update/
 * delete/post: a dashboard widget reads and renders; it does not mutate. (First-party plugins run
 * with the app's full session, so this is least-privilege by convention, not a hard sandbox.)
 */
export type OnnoReadApi = Pick<
  typeof api,
  | "listCatalog"
  | "searchCatalog"
  | "getCatalogItem"
  | "listDocuments"
  | "searchDocument"
  | "getDocument"
  | "getBalance"
  | "getTurnover"
  | "getMovements"
>;

declare global {
  interface Window {
    onno?: OnnoHost;
  }
}

const readApi: OnnoReadApi = {
  listCatalog: api.listCatalog,
  searchCatalog: api.searchCatalog,
  getCatalogItem: api.getCatalogItem,
  listDocuments: api.listDocuments,
  searchDocument: api.searchDocument,
  getDocument: api.getDocument,
  getBalance: api.getBalance,
  getTurnover: api.getTurnover,
  getMovements: api.getMovements,
};

/**
 * Install `window.onno` once, before any plugin loads. Idempotent — a second call is a no-op so
 * re-imports (HMR, test doubles) don't swap the instance out from under already-loaded plugins.
 */
export function installPluginHost(): OnnoHost {
  if (window.onno) return window.onno;
  const host: OnnoHost = Object.freeze({
    React,
    jsxRuntime: ReactJSXRuntime,
    registerWidget,
    html: htm.bind(React.createElement) as OnnoHost["html"],
    api: readApi,
    version: 1,
  });
  window.onno = host;
  return host;
}

// Install on import so the host is present as early as the module graph is evaluated — the loader
// (and any plugin it pulls in) can rely on `window.onno` existing.
installPluginHost();
