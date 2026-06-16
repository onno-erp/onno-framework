import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { MagicLinkWidget } from "@/components/magic-link-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onec-magic-link} to the React
 * {@link MagicLinkWidget}. Mirrors {@code login-form-bridge}: the element just marks where the
 * passwordless sub-form should mount, so it registers on connect unconditionally, and
 * {@link MagicLinkPortals} (mounted on the login route, inside the providers) portals the React form
 * into each live element.
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

class OnecMagicLinkElement extends HTMLElement {
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
export function defineMagicLinkElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onec-magic-link")) {
    customElements.define("onec-magic-link", OnecMagicLinkElement);
  }
  defined = true;
}
defineMagicLinkElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const MAGIC_LINK_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onec-magic-link", { element: "onec-magic-link" }],
]);

/** Portals every live {@code <onec-magic-link>} to its React form. Mount once, on the login route. */
export function MagicLinkPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return <>{list.map((m) => createPortal(<MagicLinkWidget />, m.el, String(m.id)))}</>;
}
