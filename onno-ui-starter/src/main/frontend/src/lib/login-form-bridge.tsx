import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { LoginFormWidget } from "@/components/login-form-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-login-form} to the React
 * {@link LoginFormWidget}. Mirrors {@code form-bridge}, but the login form carries no descriptor —
 * the element just marks where the password sub-form should mount, so it registers on connect
 * unconditionally. {@link LoginFormPortals} (mounted on the login route, inside the providers)
 * portals the React form into each live element.
 */

type Mount = { id: number; el: HTMLElement };

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

class OnnoLoginFormElement extends HTMLElement {
  private readonly _id = ++seq;

  connectedCallback() {
    if (!mounts.some((m) => m.el === this)) {
      mounts = [...mounts, { id: this._id, el: this }];
      emit();
    }
  }
  disconnectedCallback() {
    if (mounts.some((m) => m.el === this)) {
      mounts = mounts.filter((m) => m.el !== this);
      emit();
    }
  }
}

let defined = false;
export function defineLoginFormElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-login-form")) {
    customElements.define("onno-login-form", OnnoLoginFormElement);
  }
  defined = true;
}
defineLoginFormElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const LOGIN_FORM_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-login-form", { element: "onno-login-form" }],
]);

/** Portals every live {@code <onno-login-form>} to its React form. Mount once, on the login route. */
export function LoginFormPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return <>{list.map((m) => createPortal(<LoginFormWidget />, m.el, String(m.id)))}</>;
}
