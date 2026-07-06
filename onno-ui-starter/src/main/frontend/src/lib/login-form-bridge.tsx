import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { LoginFormWidget, type DemoAccount } from "@/components/login-form-widget";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-login-form} to the React
 * {@link LoginFormWidget}. Mirrors {@code form-bridge}, but the login form carries no descriptor —
 * the element just marks where the password sub-form should mount, so it registers on connect
 * unconditionally. {@link LoginFormPortals} (mounted on the login route, inside the providers)
 * portals the React form into each live element.
 *
 * <p>When the server configures one-tap demo accounts (LoginDivBuilder emits them as a
 * {@code demoAccounts} custom prop), DivKit assigns them as a property on the element; the bridge
 * forwards them to the form, which renders a quick-sign-in button per account.</p>
 */

type Mount = { id: number; el: HTMLElement; demoAccounts: DemoAccount[] };

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

/** Coerce whatever DivKit assigned (an array, or a JSON string) into a clean DemoAccount[]. */
function parseDemoAccounts(value: unknown): DemoAccount[] {
  let raw = value;
  if (typeof raw === "string") {
    try {
      raw = JSON.parse(raw);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(raw)) return [];
  return raw
    .map((a) => ({
      label: String((a as DemoAccount)?.label ?? ""),
      username: String((a as DemoAccount)?.username ?? ""),
      password: String((a as DemoAccount)?.password ?? ""),
    }))
    .filter((a) => a.label && a.username);
}

class OnnoLoginFormElement extends HTMLElement {
  private readonly _id = ++seq;
  private _demoAccounts: DemoAccount[] = [];

  // DivKit assigns each custom_props key as a property on the element.
  set demoAccounts(value: unknown) {
    this._demoAccounts = parseDemoAccounts(value);
    this.sync();
  }
  get demoAccounts(): DemoAccount[] {
    return this._demoAccounts;
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
      existing.demoAccounts = this._demoAccounts;
    } else {
      mounts = [...mounts, { id: this._id, el: this, demoAccounts: this._demoAccounts }];
    }
    mounts = [...mounts];
    emit();
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
  return (
    <>
      {list.map((m) =>
        createPortal(<LoginFormWidget demoAccounts={m.demoAccounts} />, m.el, String(m.id)),
      )}
    </>
  );
}
