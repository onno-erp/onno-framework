import { useSyncExternalStore, type ComponentType } from "react";
import { createPortal } from "react-dom";
import type { DashboardWidgetMeta } from "@/lib/types";
import { ChartWidget, TimeRangeWidget } from "@/components/chart-widget";
import { CalendarWidget } from "@/components/calendar-widget";
import { KanbanWidget } from "@/components/kanban-widget";
import { ListWidget } from "@/components/list-widget";
import { StatWidget } from "@/components/stat-widget";
import { SparklineWidget } from "@/components/sparkline-widget";
import { GaugeWidget } from "@/components/gauge-widget";
import { MapWidget } from "@/components/map-widget";

/**
 * Bridges DivKit's {@code div-custom} blocks to React widgets. The server emits a
 * custom node of {@code custom_type "onno-widget"} carrying the widget descriptor;
 * DivKit instantiates the {@link OnnoWidgetElement} custom element and assigns it
 * the descriptor. Each live element registers itself in a small store; {@link
 * WidgetPortals} (mounted inside the app's Router/providers) renders the matching
 * component into it via a portal — so charts/calendars/kanban boards keep full
 * access to routing, theming, and toasts despite being placed by DivKit.
 */

/**
 * The widget-type → React component registry. Seeded with the built-ins and open to
 * app authors via {@link registerWidget}, so a host app can ship its own widget types
 * (KPI tiles, gauges, maps) for the same {@code onno-widget} custom block without
 * forking the framework. The server emits {@code .type("gauge")} as an {@code
 * onno-widget} descriptor; whatever the app registered under {@code "gauge"} renders it.
 */
const REGISTRY: Record<string, ComponentType<{ widget: DashboardWidgetMeta }>> = {
  chart: ChartWidget,
  timeRange: TimeRangeWidget,
  calendar: CalendarWidget,
  kanban: KanbanWidget,
  list: ListWidget,
  stat: StatWidget,
  sparkline: SparklineWidget,
  gauge: GaugeWidget,
  map: MapWidget,
};

/**
 * Register (or override) the renderer for a widget type. Call once at startup, before
 * or after DivKit content mounts — already-rendered hosts re-resolve against the new
 * registration. Returns nothing; last registration for a type wins.
 *
 * @example
 *   registerWidget("gauge", GaugeWidget);
 *   // server: b.widget("SLA").type("gauge").document(Incident.class)
 */
export function registerWidget(
  widgetType: string,
  component: ComponentType<{ widget: DashboardWidgetMeta }>
) {
  REGISTRY[widgetType] = component;
  // Re-publish so any host already on screen picks up the newly registered renderer.
  mounts = [...mounts];
  emit();
}

/** The widget types with a registered renderer (built-ins + app-registered). */
export function registeredWidgetTypes(): string[] {
  return Object.keys(REGISTRY);
}

type Mount = { id: number; el: HTMLElement; widget: DashboardWidgetMeta };

// A new array reference is published on every change so useSyncExternalStore sees it.
let mounts: Mount[] = [];
const listeners = new Set<() => void>();
let seq = 0;

function emit() {
  for (const l of listeners) l();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot(): Mount[] {
  return mounts;
}

class OnnoWidgetElement extends HTMLElement {
  private readonly _id = ++seq;
  private _widget: DashboardWidgetMeta | null = null;

  // DivKit assigns custom_props.widget as a property (the element exposes `widget`,
  // so the value arrives as a live object rather than a stringified attribute).
  set widget(value: DashboardWidgetMeta | null) {
    this._widget = value;
    this.sync();
  }

  get widget(): DashboardWidgetMeta | null {
    return this._widget;
  }

  connectedCallback() {
    this.sync();
  }

  disconnectedCallback() {
    if (mounts.some((m) => m.el === this)) {
      mounts = mounts.filter((m) => m.el !== this);
      emit();
    }
  }

  // Register (or refresh) this element once it's both connected and carries a widget.
  private sync() {
    if (!this.isConnected || !this._widget) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) {
      existing.widget = this._widget;
    } else {
      mounts = [...mounts, { id: this._id, el: this, widget: this._widget }];
    }
    mounts = [...mounts];
    emit();
  }
}

let defined = false;

/** Register the custom element once, before any DivKit content renders. */
export function defineWidgetElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-widget")) {
    customElements.define("onno-widget", OnnoWidgetElement);
  }
  defined = true;
}

defineWidgetElement();

/** The DivKit {@code customComponents} map: custom_type → element tag. */
export const WIDGET_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-widget", { element: "onno-widget" }],
]);

/** Placeholder for a widget type with no registered renderer (see {@link registerWidget}). */
function UnknownWidget({ type, title }: { type: string; title: string }) {
  return (
    <div className="rounded-lg border border-dashed border-border p-4 text-xs text-muted-foreground">
      <div className="font-medium text-foreground">{title}</div>
      <div className="mt-1">
        No renderer registered for widget type <code className="font-mono">{type}</code>.
      </div>
    </div>
  );
}

/** Portals every live {@code <onno-widget>} to its React component. Mount once, high in the tree. */
export function WidgetPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) => {
        const Component = REGISTRY[m.widget.widgetType];
        const node = Component ? (
          <Component widget={m.widget} />
        ) : (
          // Don't silently drop an unknown type — show a labelled placeholder so a
          // missing registerWidget(...) is visible rather than a blank gap.
          <UnknownWidget type={m.widget.widgetType} title={m.widget.title} />
        );
        return createPortal(node, m.el, String(m.id));
      })}
    </>
  );
}
