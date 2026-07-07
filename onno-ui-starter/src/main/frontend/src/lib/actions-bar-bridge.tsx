import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { PageActionsBar, type PageActionButton } from "@/components/page-actions-bar";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-actions} to the React
 * {@link PageActionsBar}. A page composes a button section with {@code PageBuilder.actions(...)};
 * the server emits it as a small custom block carrying the heading, the page route/profile the
 * buttons post back to, and the button descriptors, and {@link ActionsBarPortals} (mounted inside
 * the app's Router/providers) portals the React section into it. Mirrors the list/constants
 * bridges, so a button section is just another page primitive.
 */

type Mount = {
  id: number;
  el: HTMLElement;
  heading: string;
  route: string;
  profile: string;
  buttons: PageActionButton[];
};

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

class OnnoActionsElement extends HTMLElement {
  private readonly _id = ++seq;
  private _heading = "";
  private _route = "";
  private _profile = "";
  private _buttons: PageActionButton[] | null = null;

  // DivKit assigns each custom_props key as a property on the element.
  set heading(value: string) {
    this._heading = value ?? "";
    this.sync();
  }
  get heading(): string {
    return this._heading;
  }
  set route(value: string) {
    this._route = value ?? "";
    this.sync();
  }
  get route(): string {
    return this._route;
  }
  set profile(value: string) {
    this._profile = value ?? "";
    this.sync();
  }
  get profile(): string {
    return this._profile;
  }
  set buttons(value: PageActionButton[] | null) {
    this._buttons = value;
    this.sync();
  }
  get buttons(): PageActionButton[] | null {
    return this._buttons;
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

  // Buttons is the last prop that matters; render once it (and the route) are present.
  private sync() {
    if (!this.isConnected || !this._buttons) return;
    const next: Mount = {
      id: this._id,
      el: this,
      heading: this._heading,
      route: this._route,
      profile: this._profile,
      buttons: this._buttons,
    };
    const existing = mounts.find((m) => m.el === this);
    if (existing) {
      existing.heading = next.heading;
      existing.route = next.route;
      existing.profile = next.profile;
      existing.buttons = next.buttons;
    } else {
      mounts = [...mounts, next];
    }
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineActionsElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-actions")) {
    customElements.define("onno-actions", OnnoActionsElement);
  }
  defined = true;
}
defineActionsElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const ACTIONS_BAR_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-actions", { element: "onno-actions" }],
]);

/** Portals every live {@code <onno-actions>} to its button section. Mount once, inside the Router. */
export function ActionsBarPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) =>
        createPortal(
          <IslandErrorBoundary label={m.heading || "actions"}>
            <PageActionsBar heading={m.heading} route={m.route} profile={m.profile} buttons={m.buttons} />
          </IslandErrorBoundary>,
          m.el,
          String(m.id)
        )
      )}
    </>
  );
}
