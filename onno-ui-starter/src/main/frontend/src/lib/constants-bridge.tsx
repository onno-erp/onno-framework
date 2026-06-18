import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { ConstantsEditor } from "@/components/constants-editor";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-constants} to the React
 * {@link ConstantsEditor}. A page composes the settings editor with {@code PageBuilder.constants(...)};
 * the server emits it as a small custom block carrying an optional heading, and {@link
 * ConstantsPortals} (mounted inside the app's Router/providers) portals the editor into it. Mirrors
 * the list/form bridges, so Settings is just another page built from framework primitives.
 */

type Mount = { id: number; el: HTMLElement; title: string; names: string[] | null };

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

class OnnoConstantsElement extends HTMLElement {
  private readonly _id = ++seq;
  private _title = "";
  // The @Constant logical names to show, or null for "all". DivKit passes the payload list as an
  // array property; tolerate a JSON string too in case a client stringifies it.
  private _names: string[] | null = null;

  set title(value: string) {
    this._title = value ?? "";
    this.sync();
  }
  get title(): string {
    return this._title;
  }
  set names(value: string[] | string | null) {
    if (Array.isArray(value)) this._names = value.map(String);
    else if (typeof value === "string" && value) {
      try {
        const parsed = JSON.parse(value);
        this._names = Array.isArray(parsed) ? parsed.map(String) : null;
      } catch {
        this._names = null;
      }
    } else this._names = null;
    this.sync();
  }
  get names(): string[] | null {
    return this._names;
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
    if (existing) {
      existing.title = this._title;
      existing.names = this._names;
    } else {
      mounts = [...mounts, { id: this._id, el: this, title: this._title, names: this._names }];
    }
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineConstantsElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-constants")) {
    customElements.define("onno-constants", OnnoConstantsElement);
  }
  defined = true;
}
defineConstantsElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const CONSTANTS_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-constants", { element: "onno-constants" }],
]);

/** Portals every live {@code <onno-constants>} to its editor. Mount once, inside the Router. */
export function ConstantsPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) =>
        createPortal(<ConstantsEditor title={m.title} names={m.names ?? undefined} />, m.el, String(m.id))
      )}
    </>
  );
}
