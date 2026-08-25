import type { ComponentType } from "react";
import type {
  DashboardWidgetMeta,
  ListRendererProps,
  OnnoEvents,
  OnnoHost,
  OnnoReadApi,
  OnnoUi,
  UiEvent,
  UiEventFilter,
} from "./types";

export type {
  DashboardWidgetMeta,
  EntityRecord,
  ListRendererColumn,
  ListRendererDescriptor,
  ListRendererProps,
  OnnoReadApi,
  OnnoEvents,
  OnnoHost,
  OnnoUi,
  UiEvent,
  UiEventFilter,
} from "./types";

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
 *   import { registerWidget, useCallback, useState, useEffect, useWidgetUpdates, api, WidgetProps } from "@onno/widget-sdk";
 *
 *   function EventLog({ widget }: WidgetProps) {
 *     const [rows, setRows] = useState<any[]>([]);
 *     const load = useCallback(async () => setRows(await api.listDocuments(widget.entityName)), [widget.entityName]);
 *     useEffect(() => { void load(); }, [load]);
 *     useWidgetUpdates(widget, load);
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

/**
 * Register the body renderer for a custom <em>list</em> view — the component an entity's
 * {@code ListSpec.custom("type")} resolves. Same registry as {@link registerWidget}, but the
 * component receives the list-renderer contract ({@link ListRendererProps}: the current window of
 * rows, the list descriptor, and an open-record callback) instead of a dashboard-widget descriptor.
 * The framework keeps the toolbar and the data feed; the component only draws the rows.
 *
 * @example
 *   import { registerListRenderer, type ListRendererProps } from "@onno/widget-sdk";
 *
 *   function BookTiles({ rows, open }: ListRendererProps) {
 *     return <div className="grid grid-cols-4 gap-3">{rows.map((r) => (
 *       <button key={String(r.id)} onClick={() => open(r)}>{String(r.description)}</button>
 *     ))}</div>;
 *   }
 *   registerListRenderer("bookTiles", BookTiles);
 *   // server: list.custom("bookTiles").label("Shelf")
 */
export const registerListRenderer = (
  rendererType: string,
  component: ComponentType<ListRendererProps>
): void =>
  // The host registry stores both prop shapes; the list island renders this entry with
  // ListRendererProps (dashboards never resolve a type an EntityView declared for its list).
  host.registerWidget(rendererType, component as unknown as ComponentType<{ widget: DashboardWidgetMeta }>);

/** `htm` bound to the host's `React.createElement` — JSX-like markup with no build step, if wanted. */
export const html = host.html;

/** The read-only REST client (same-origin, session + CSRF handled by the host). */
export const api: OnnoReadApi = host.api;

function requireEvents(): OnnoEvents {
  if (host.events) return host.events;
  throw new Error(
    "@onno/widget-sdk: live updates require host contract v3. Upgrade onno-ui-starter to the " +
      "same version as the su.onno.widgets Gradle plugin."
  );
}

/**
 * Subscribe to the host's shared live-update stream. Do not construct an {@code EventSource} in a
 * widget: this facade reuses the SPA's authenticated, reconnecting connection across every widget
 * and browser tab. The capability is resolved lazily so widgets that do not use live updates still
 * run on a v2 host.
 */
export const events: OnnoEvents = Object.freeze({
  subscribe: (listener: (event: UiEvent) => void, filter?: UiEventFilter) =>
    requireEvents().subscribe(listener, filter),
});

/** Function-form alias for {@link events.subscribe}. */
export const subscribeUiEvents = events.subscribe;

/** Subscribe a React component to the shared event stream and clean up on unmount. */
export function useUiEvents(listener: (event: UiEvent) => void, filter?: UiEventFilter): void {
  const listenerRef = host.React.useRef(listener);
  listenerRef.current = listener;
  const typesKey = filter?.types?.join("\u0000") ?? "";
  host.React.useEffect(
    () => events.subscribe((event) => listenerRef.current(event), filter),
    [filter?.entityType, filter?.entityName, filter?.id, typesKey]
  );
}

const ENTITY_UPDATE_TYPES = ["created", "updated", "deleted", "posted", "unposted", "changed"] as const;

export interface WidgetUpdateOptions {
  /** Coalesce posting/change bursts before refreshing. Default 250 ms; set 0 for immediate delivery. */
  debounceMs?: number;
  /** Set false for a deliberately static widget. Default true. */
  enabled?: boolean;
}

/** Whether an entity-change event invalidates the data bound to a widget. */
export function eventMatchesWidget(event: UiEvent, widget: DashboardWidgetMeta): boolean {
  if (!(ENTITY_UPDATE_TYPES as readonly string[]).includes(event.type)) return false;
  if (event.entityType === "register") {
    return (
      widget.entityType === "register" &&
      (!event.entityName || event.entityName === "*" || event.entityName === widget.entityName)
    );
  }
  return event.entityType === widget.entityType && event.entityName === widget.entityName;
}

/**
 * Refresh a widget when its bound catalog, document, or register changes. Matching, burst
 * coalescing, latest-callback handling, shared transport reuse, and unmount cleanup are built in.
 * Pair it with the same stable loader used for the widget's initial fetch.
 */
export function useWidgetUpdates(
  widget: DashboardWidgetMeta,
  refresh: () => void | Promise<unknown>,
  options: WidgetUpdateOptions = {}
): void {
  const refreshRef = host.React.useRef(refresh);
  refreshRef.current = refresh;
  const debounceMs = Math.max(0, options.debounceMs ?? 250);
  const enabled = options.enabled ?? true;
  host.React.useEffect(() => {
    if (!enabled) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = events.subscribe(
      (event) => {
        if (!eventMatchesWidget(event, widget)) return;
        if (timer !== undefined) clearTimeout(timer);
        timer = setTimeout(() => {
          try {
            Promise.resolve(refreshRef.current()).catch((error) =>
              console.error("[onno] widget live refresh failed", error)
            );
          } catch (error) {
            console.error("[onno] widget live refresh failed", error);
          }
        }, debounceMs);
      },
      { types: ENTITY_UPDATE_TYPES }
    );
    return () => {
      if (timer !== undefined) clearTimeout(timer);
      unsubscribe();
    };
  }, [widget.entityType, widget.entityName, debounceMs, enabled]);
}

/**
 * The host's UI primitives — the *real* design-system controls (Radix-backed `Select`/`Popover`,
 * the app's `Button`/`Segmented`/`Badge`/`Input`/…), so a widget matches the product instead of
 * shipping hand-rolled lookalikes (and sidesteps the Tailwind class-emission gotcha, since these
 * carry the host's own already-emitted classes). Requires host contract v2+.
 *
 * @example
 *   import { ui, useState } from "@onno/widget-sdk";
 *   const { Select, SelectTrigger, SelectValue, SelectContent, SelectItem, Segmented } = ui;
 *
 *   function ViewSwitch() {
 *     const [view, setView] = useState("day");
 *     return (
 *       <Segmented
 *         value={view}
 *         onChange={setView}
 *         options={[{ value: "day", label: "Day" }, { value: "week", label: "Week" }]}
 *       />
 *     );
 *   }
 */
export const ui: OnnoUi = host.ui;

/**
 * The host UI primitives as direct named exports, so a widget can
 * `import { DatePicker, Select, Button } from "@onno/widget-sdk"` and drop them in like any
 * component. Same instances as {@link ui} (they resolve to the host's singletons at runtime); this
 * is purely the ergonomic import surface. Requires host contract v2+.
 *
 * @example
 *   import { Segmented, DatePicker, Button } from "@onno/widget-sdk";
 */
export const {
  Button,
  Badge,
  Input,
  Label,
  Textarea,
  Checkbox,
  Switch,
  Segmented,
  DatePicker,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Popover,
  PopoverTrigger,
  PopoverContent,
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} = ui;

/** Props every registered widget receives. */
export interface WidgetProps {
  widget: DashboardWidgetMeta;
}
