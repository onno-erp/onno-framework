import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { GeoView } from "@/components/geo-view";
import { IslandErrorBoundary } from "@/lib/island-error-boundary";

/**
 * Bridges DivKit's {@code div-custom} block of type {@code onno-geo} to the React {@link GeoView}.
 * The detail surface emits a read-only map for a {@code .widget("map")} field as this custom block
 * (carrying the stored {@code "lat,lng"} value + a label); the bridge portals the Leaflet map into
 * it, exactly like the other detail islands (form, list, comments). See {@code SurfaceDivBuilder}.
 */

type GeoProps = { value?: string; label?: string };
type Mount = { id: number; el: HTMLElement; geo: GeoProps };

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

class OnnoGeoElement extends HTMLElement {
  private readonly _id = ++seq;
  private _geo: GeoProps | null = null;

  set geo(value: GeoProps | null) {
    this._geo = value;
    this.sync();
  }
  get geo(): GeoProps | null {
    return this._geo;
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
    if (!this.isConnected || !this._geo) return;
    const existing = mounts.find((m) => m.el === this);
    if (existing) existing.geo = this._geo;
    else mounts = [...mounts, { id: this._id, el: this, geo: this._geo }];
    mounts = [...mounts];
    emit();
  }
}

let defined = false;
export function defineGeoElement() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-geo")) {
    customElements.define("onno-geo", OnnoGeoElement);
  }
  defined = true;
}
defineGeoElement();

/** The DivKit {@code customComponents} entry: custom_type → element tag. */
export const GEO_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-geo", { element: "onno-geo" }],
]);

/** Portals every live {@code <onno-geo>} to its React map. Mount once, inside the Router. */
export function GeoPortals() {
  const list = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {list.map((m) =>
        createPortal(
          <IslandErrorBoundary label={m.geo.label || "map"}>
            <GeoView value={m.geo.value} label={m.geo.label} />
          </IslandErrorBoundary>,
          m.el,
          String(m.id)
        )
      )}
    </>
  );
}
