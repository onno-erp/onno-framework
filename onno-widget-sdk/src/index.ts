import type { ComponentType } from "react";
import type { DashboardWidgetMeta, OnnoHost, OnnoReadApi } from "./types";

export type { DashboardWidgetMeta, EntityRecord, OnnoReadApi, OnnoHost } from "./types";

/**
 * `@onno/widget-sdk` — write a custom widget for the onno UI as a normal React component, then
 * register it by the type string your server-side `.widget(...).type("…")` declaration uses.
 *
 * The runtime bindings below resolve to the host SPA's singletons on `window.onno`, so your compiled
 * plugin ships with **no React inside it** and shares the host's hooks, context, router, and theme.
 * (The `su.onno.widgets` Gradle plugin bundles your `.tsx` with React aliased to the host — you just
 * write the component.)
 *
 * @example
 *   import { registerWidget, useState, useEffect, api, WidgetProps } from "@onno/widget-sdk";
 *
 *   function EventLog({ widget }: WidgetProps) {
 *     const [rows, setRows] = useState<any[]>([]);
 *     useEffect(() => { api.listDocuments(widget.entityName).then(setRows); }, [widget.entityName]);
 *     return (
 *       <ul className="text-sm text-foreground">
 *         {rows.map((r) => <li key={String(r.id)}>{String(r.date)} — {String(r.description)}</li>)}
 *       </ul>
 *     );
 *   }
 *   registerWidget("eventLog", EventLog);
 */

// The host is installed by the SPA before any plugin loads (see plugin-host.ts). Reading it at module
// scope is safe: a plugin module only evaluates once the loader dynamic-imports it, post-install.
const host: OnnoHost = (globalThis as unknown as { onno: OnnoHost }).onno;

if (!host) {
  throw new Error(
    "@onno/widget-sdk: window.onno is not installed. A widget plugin must be loaded by the onno SPA, " +
      "not imported standalone."
  );
}

/** The host's React instance — the same one the app renders with. */
export const React = host.React;

// Re-export the common hooks so a widget can `import { useState } from "@onno/widget-sdk"`. (Authors
// may equally `import { useState } from "react"` — the Gradle build aliases react to the host too.)
export const {
  useState,
  useEffect,
  useMemo,
  useRef,
  useCallback,
  useReducer,
  useContext,
  useLayoutEffect,
} = host.React;

/**
 * Register (or override) the renderer for a widget type. Call once at plugin load. Last registration
 * wins; already-rendered hosts re-resolve, so registration timing is not load-bearing.
 */
export const registerWidget: (
  widgetType: string,
  component: ComponentType<{ widget: DashboardWidgetMeta }>
) => void = host.registerWidget;

/** `htm` bound to the host's `React.createElement` — JSX-like markup with no build step, if wanted. */
export const html = host.html;

/** The read-only REST client (same-origin, session + CSRF handled by the host). */
export const api: OnnoReadApi = host.api;

/** Props every registered widget receives. */
export interface WidgetProps {
  widget: DashboardWidgetMeta;
}
