import { useSyncExternalStore, type ComponentType } from "react";
import { createPortal } from "react-dom";
import type { DashboardWidgetMeta } from "@/lib/types";
import { ChartWidget } from "@/components/chart-widget";
import { CalendarWidget } from "@/components/calendar-widget";
import { KanbanWidget } from "@/components/kanban-widget";

/**
 * Bridges DivKit's {@code div-custom} blocks to React widgets. The server emits a
 * custom node of {@code custom_type "onec-widget"} carrying the widget descriptor;
 * DivKit instantiates the {@link OnecWidgetElement} custom element and assigns it
 * the descriptor. Each live element registers itself in a small store; {@link
 * WidgetPortals} (mounted inside the app's Router/providers) renders the matching
 * component into it via a portal — so charts/calendars/kanban boards keep full
 * access to routing, theming, and toasts despite being placed by DivKit.
 */

const REGISTRY: Record<string, ComponentType<{ widget: DashboardWidgetMeta }>> = {
  chart: ChartWidget,
  calendar: CalendarWidget,
  kanban: KanbanWidget,
};

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

class OnecWidgetElement extends HTMLElement {
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
  if (!customElements.get("onec-widget")) {
    customElements.define("onec-widget", OnecWidgetElement);
  }
  defined = true;
}

defineWidgetElement();

/** The DivKit {@code customComponents} map: custom_type → element tag. */
export const WIDGET_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onec-widget", { element: "onec-widget" }],
]);

/** Portals every live {@code <onec-widget>} to its React component. Mount once, high in the tree. */
export function WidgetPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) => {
        const Component = REGISTRY[m.widget.widgetType];
        if (!Component) return null;
        return createPortal(<Component widget={m.widget} />, m.el, String(m.id));
      })}
    </>
  );
}
