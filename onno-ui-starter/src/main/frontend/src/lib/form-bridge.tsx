import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { EntityFormWidget, type FormDescriptor } from "@/components/entity-form-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-form} to the React
 * {@link EntityFormWidget}. The server emits the form as a portable descriptor (field
 * metadata + initial values + submit target) rather than DivKit field nodes, because
 * rich controls — styled dropdowns, a calendar picker, a ref picker that opens the
 * target catalog's form — can't be expressed in a DivKit document. Every client
 * implements {@code onno-form}; this is the web implementation. Mirrors the widget
 * bridge: a live custom element registers in a store, and {@link FormPortals} (mounted
 * inside the app's Router/providers) portals the React form into it.
 */

type Mount = { id: number; el: HTMLElement; form: FormDescriptor };

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

class OnnoFormElement extends HTMLElement {
  private readonly _id = ++seq;
  private _form: FormDescriptor | null = null;

  // DivKit assigns custom_props.form as a property (live object, not a string attribute).
  set form(value: FormDescriptor | null) {
    this._form = value;
    this.sync();
  }
  get form(): FormDescriptor | null {
    return this._form;
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
    if (!this.isConnected || !this._form) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.form = this._form;
    else mounts = [...mounts, { id: this._id, el: this, form: this._form }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineFormElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-form")) {
    customElements.define("onno-form", OnnoFormElement);
  }
  defined = true;
}
defineFormElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const FORM_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-form", { element: "onno-form" }],
]);

/** Portals every live {@code <onno-form>} to its React form. Mount once, inside the Router. */
export function FormPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) => createPortal(<EntityFormWidget form={m.form} />, m.el, String(m.id)))}
    </>
  );
}
