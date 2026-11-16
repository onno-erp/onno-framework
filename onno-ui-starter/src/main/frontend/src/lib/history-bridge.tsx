import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { SurfaceHost } from "@/lib/surface-refresh";
import { EntityHistoryWidget, type HistoryTarget } from "@/components/entity-history-widget";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-history} to the React
 * {@link EntityHistoryWidget}. The server emits the block on the record surface of every catalog or
 * document that opted in with {@code history = true}, carrying only the record's
 * {@code {kind, name, id}} triple as {@code custom_props.target}; the widget loads
 * {@code /api/history/...} itself. Same mechanism as the comments bridge.
 */

type Mount = { id: number; el: HTMLElement; target: HistoryTarget };

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

class OnnoHistoryElement extends HTMLElement {
  private readonly _id = ++seq;
  private _target: HistoryTarget | null = null;

  // DivKit assigns custom_props.target as a property (live object, not a string attribute).
  set target(value: HistoryTarget | null) {
    this._target = value;
    this.sync();
  }
  get target(): HistoryTarget | null {
    return this._target;
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

  private sync() {
    if (!this.isConnected || !this._target) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.target = this._target;
    else mounts = [...mounts, { id: this._id, el: this, target: this._target }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineHistoryElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-history")) {
    customElements.define("onno-history", OnnoHistoryElement);
  }
  defined = true;
}
defineHistoryElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const HISTORY_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-history", { element: "onno-history" }],
]);

/** Portals each mounted {@code onno-history} element to a React history panel. */
export function HistoryPortals() {
  const live = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {live.map((m) =>
        createPortal(
          <SurfaceHost.Provider value={m.el}>
            <IslandErrorBoundary label={`${m.target.name} history`}>
              <EntityHistoryWidget target={m.target} />
            </IslandErrorBoundary>
          </SurfaceHost.Provider>,
          m.el,
          String(m.id)
        )
      )}
    </>
  );
}
