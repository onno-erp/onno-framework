import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { PresenceAvatars } from "@/components/presence-avatars";
import { useEntityViewers } from "@/lib/presence-store";

/**
 * Bridges DivKit's `onno-nav-presence` custom block — one per catalog/document nav item — to a React
 * indicator showing who is viewing any record of that entity (the sidebar ambient marker). The nav is
 * server-driven DivKit, so this is the only seam to put a live React dot on a nav item; it mirrors the
 * icon bridge. The block carries the item's route `path` (e.g. "/catalogs/properties"); the indicator
 * resolves viewers from the global presence store and renders nothing for non-entity routes or when
 * nobody is viewing — a small reserved slot the server sizes, so it never disturbs nav layout.
 */

type Mount = { id: number; el: HTMLElement; path: string };

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

class OnnoNavPresenceElement extends HTMLElement {
  private readonly _id = ++seq;
  private _path = "";

  // DivKit assigns custom_props.path as a property.
  set path(value: string) {
    this._path = value ?? "";
    this.sync();
  }
  get path(): string {
    return this._path;
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
    if (!this.isConnected || !this._path) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.path = this._path;
    else mounts = [...mounts, { id: this._id, el: this, path: this._path }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineNavPresenceElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-nav-presence")) {
    customElements.define("onno-nav-presence", OnnoNavPresenceElement);
  }
  defined = true;
}
defineNavPresenceElement();

/** The DivKit `customComponents` entry: custom_type → element tag. */
export const NAV_PRESENCE_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-nav-presence", { element: "onno-nav-presence" }],
]);

/**
 * Map a nav route path to the (kind, name) the presence store aggregates by — mirroring the server's
 * route identity: a catalogs/documents route resolves to its entity (so the nav item unions everyone on
 * the list and on any of its records); every other route is a "page" keyed by its normalized path.
 */
function navIdentity(path: string): { kind: string; name: string } {
  const seg = path.split("/").filter(Boolean);
  if (seg.length >= 2 && (seg[0] === "catalogs" || seg[0] === "documents")) {
    return { kind: seg[0], name: seg[1] };
  }
  return { kind: "page", name: "/" + seg.join("/") };
}

/** A compact face-pile of the route's viewers — up to three tiny avatars, pinned to the slot's right. */
function NavPresenceIndicator({ path }: { path: string }) {
  const { kind, name } = navIdentity(path);
  const viewers = useEntityViewers(kind, name);
  return <PresenceAvatars viewers={viewers} size={20} max={3} overlap className="h-full w-full justify-end" />;
}

/** Portals each mounted `onno-nav-presence` element to its nav indicator. Mount once, inside providers. */
export function NavPresencePortals() {
  const live = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {live.map((m) => createPortal(<NavPresenceIndicator path={m.path} />, m.el, String(m.id)))}
    </>
  );
}
