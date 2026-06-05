import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { ConstantsEditor } from "@/components/constants-editor";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onec-constants} to the React
 * {@link ConstantsEditor}. A page composes the settings editor with {@code PageBuilder.constants(...)};
 * the server emits it as a small custom block carrying an optional heading, and {@link
 * ConstantsPortals} (mounted inside the app's Router/providers) portals the editor into it. Mirrors
 * the list/form bridges, so Settings is just another page built from framework primitives.
 */

type Mount = { id: number; el: HTMLElement; title: string };

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

class OnecConstantsElement extends HTMLElement {
  private readonly _id = ++seq;
  private _title = "";

  set title(value: string) {
    this._title = value ?? "";
    this.sync();
  }
  get title(): string {
    return this._title;
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
    if (!this.isConnected) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.title = this._title;
    else mounts = [...mounts, { id: this._id, el: this, title: this._title }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineConstantsElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onec-constants")) {
    customElements.define("onec-constants", OnecConstantsElement);
  }
  defined = true;
}
defineConstantsElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const CONSTANTS_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onec-constants", { element: "onec-constants" }],
]);

/** Portals every live {@code <onec-constants>} to its editor. Mount once, inside the Router. */
export function ConstantsPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return <>{list.map((m) => createPortal(<ConstantsEditor title={m.title} />, m.el, String(m.id)))}</>;
}
