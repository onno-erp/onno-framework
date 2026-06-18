import { useMemo } from "react";
import type { Feature } from "geojson";
import { toFeatureCollection } from "@/lib/geo";
import { MapView } from "@/components/map-view";

/**
 * The read-only map for a detail field hinted {@code .widget("map")} or {@code .widget("geojson")}:
 * renders the stored value — a {@code "lat,lng"} point or any GeoJSON (points/paths/areas) — on the
 * theme-aware MapLibre basemap. Falls back to the raw text when the value isn't valid geometry, so a
 * stray string isn't swallowed into a blank map.
 */
export function GeoView({ value, label }: { value?: string; label?: string }) {
  const features = useMemo<Feature[]>(() => {
    const fc = toFeatureCollection(value);
    if (!fc) return [];
    return fc.features.map((f) => ({ ...f, properties: { ...(f.properties ?? {}), label } }));
  }, [value, label]);

  if (features.length === 0) {
    return <span className="text-sm text-foreground">{value || "—"}</span>;
  }
  return <MapView features={features} height={200} />;
}
