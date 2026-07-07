import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { RegisterSurface, type RegisterDescriptor } from "@/components/register-surface";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-register} to the React
 * {@link RegisterSurface}. The server emits a register as a small descriptor (one or more named list
 * views) rather than DivKit tabs, so the view switch lives in React — switching can't unmount the
 * portaled grid. Mirrors the list bridge: a live custom element registers in a store and
 * {@link RegisterPortals} (mounted inside the app's Router/providers) portals the surface into it.
 */

type Mount = { id: number; el: HTMLElement; register: RegisterDescriptor };

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

class OnnoRegisterElement extends HTMLElement {
  private readonly _id = ++seq;
  private _register: RegisterDescriptor | null = null;

  set register(value: RegisterDescriptor | null) {
    this._register = value;
    this.sync();
  }
  get register(): RegisterDescriptor | null {
    return this._register;
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
    if (!this.isConnected || !this._register) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.register = this._register;
    else mounts = [...mounts, { id: this._id, el: this, register: this._register }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineRegisterElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-register")) {
    customElements.define("onno-register", OnnoRegisterElement);
  }
  defined = true;
}
defineRegisterElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const REGISTER_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-register", { element: "onno-register" }],
]);

/** Portals every live {@code <onno-register>} to its React surface. Mount once, inside the Router. */
export function RegisterPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) =>
        createPortal(
          <IslandErrorBoundary label={m.register.views?.[0]?.list?.title || "register"}>
            <RegisterSurface register={m.register} />
          </IslandErrorBoundary>,
          m.el,
          String(m.id)
        )
      )}
    </>
  );
}
