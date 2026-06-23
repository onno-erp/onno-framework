import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { PresenceBar, type PresenceTarget } from "@/components/presence-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-presence} to the React
 * {@link PresenceBar}. The server emits the block at the top of every catalog/document detail surface,
 * carrying only the entity's {@code {kind, name, id}} triple as {@code custom_props.target}; the widget
 * marks the viewer present and renders the markers itself from {@code /api/presence/...}. Mirrors the
 * comments bridge: a live custom element registers in a store, and {@link PresencePortals} (mounted
 * inside the app's providers) portals the React bar into it.
 */

type Mount = { id: number; el: HTMLElement; target: PresenceTarget };

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

class OnnoPresenceElement extends HTMLElement {
  private readonly _id = ++seq;
  private _target: PresenceTarget | null = null;

  // DivKit assigns custom_props.target as a property (live object, not a string attribute).
  set target(value: PresenceTarget | null) {
    this._target = value;
    this.sync();
  }
  get target(): PresenceTarget | null {
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
export function definePresenceElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-presence")) {
    customElements.define("onno-presence", OnnoPresenceElement);
  }
  defined = true;
}
definePresenceElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const PRESENCE_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-presence", { element: "onno-presence" }],
]);

/** Portals each mounted {@code onno-presence} element to a React presence bar. */
export function PresencePortals() {
  const live = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {live.map((m) => createPortal(<PresenceBar target={m.target} />, m.el, String(m.id)))}
    </>
  );
}
