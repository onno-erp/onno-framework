import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { EntityListWidget, type ListDescriptor } from "@/components/entity-list-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onec-list} to the React
 * {@link EntityListWidget}. The server emits a list as a small descriptor (columns, sort,
 * searchability, routes) rather than a server-rendered table, so the client can virtualize and
 * page it. Mirrors the form bridge: a live custom element registers in a store and
 * {@link ListPortals} (mounted inside the app's Router/providers) portals the React grid into it.
 */

type Mount = { id: number; el: HTMLElement; list: ListDescriptor };

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

class OnecListElement extends HTMLElement {
  private readonly _id = ++seq;
  private _list: ListDescriptor | null = null;

  set list(value: ListDescriptor | null) {
    this._list = value;
    this.sync();
  }
  get list(): ListDescriptor | null {
    return this._list;
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
    if (!this.isConnected || !this._list) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.list = this._list;
    else mounts = [...mounts, { id: this._id, el: this, list: this._list }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineListElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onec-list")) {
    customElements.define("onec-list", OnecListElement);
  }
  defined = true;
}
defineListElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const LIST_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onec-list", { element: "onec-list" }],
]);

/** Portals every live {@code <onec-list>} to its React grid. Mount once, inside the Router. */
export function ListPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return <>{list.map((m) => createPortal(<EntityListWidget list={m.list} />, m.el, String(m.id)))}</>;
}
