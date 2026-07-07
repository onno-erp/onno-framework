import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import type { Map as MlMap } from "maplibre-gl";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useMapLibre } from "@/lib/use-maplibre";
import { readMapTheme } from "@/lib/map-style";
import { formatLatLng, parseLatLng } from "@/lib/geo";

/**
 * The single-point picker for a field hinted {@code .widget("map")}. The value is a plain
 * {@code "lat,lng"} string (so it round-trips through any String attribute and stays
 * backward-compatible). Click the map to drop/move a draggable marker, or type precise coordinates.
 * For paths/areas (GeoJSON) use the richer {@code MapEditor} via {@code .widget("geojson")}.
 *
 * <p>Renders on the theme-aware monochrome MapLibre basemap, sharing the lifecycle/theming with the
 * read maps (see use-maplibre, map-style).</p>
 */
const POINT_ZOOM = 13;

export function GeoPicker({
  value,
  onChange,
}: {
  value?: string;
  onChange: (val: string) => void;
}) {
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const [ready, setReady] = useState(false);

  const point = parseLatLng(value);
  const lat = point?.[0];
  const lng = point?.[1];
  // Snapshot for the marker effect without re-running on every keystroke object identity.
  const latLngKey = point ? `${point[0]},${point[1]}` : "";

  const { containerRef, mapRef } = useMapLibre({
    interactive: true,
    onReady: (map: MlMap) => {
      map.on("click", (e) => onChangeRef.current(formatLatLng(e.lngLat.lat, e.lngLat.lng)));
      setReady(true);
    },
  });

  // Reflect the current value onto the map: create/move/remove the draggable marker and recenter
  // when a point first appears (typed in, or a freshly-loaded record).
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !ready) return;
    if (lat == null || lng == null) {
      markerRef.current?.remove();
      markerRef.current = null;
      return;
    }
    if (!markerRef.current) {
      const marker = new maplibregl.Marker({ color: readMapTheme().primary, draggable: true });
      marker.on("dragend", () => {
        const ll = marker.getLngLat();
        onChangeRef.current(formatLatLng(ll.lat, ll.lng));
      });
      marker.setLngLat([lng, lat]).addTo(map);
      markerRef.current = marker;
      map.easeTo({ center: [lng, lat], zoom: Math.max(map.getZoom(), POINT_ZOOM), duration: 0 });
    } else {
      markerRef.current.setLngLat([lng, lat]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latLngKey, ready]);

  // Edit one axis from the numeric fields; the other keeps its value (0 if unset). Clearing both
  // fields clears the point.
  const setAxis = (axis: "lat" | "lng", raw: string) => {
    if (raw === "" && (axis === "lat" ? lng == null : lat == null)) {
      onChange("");
      return;
    }
    const n = Number(raw);
    if (Number.isNaN(n)) return;
    const nextLat = axis === "lat" ? n : lat ?? 0;
    const nextLng = axis === "lng" ? n : lng ?? 0;
    onChange(formatLatLng(nextLat, nextLng));
  };

  return (
    <div className="grid gap-2">
      <div
        ref={containerRef}
        className="h-60 w-full overflow-hidden rounded-card border border-border"
        style={{ minHeight: 240 }}
      />
      <div className="grid grid-cols-2 gap-3">
        <div className="grid gap-1.5">
          <Label className="text-xs text-muted-foreground">Latitude</Label>
          <Input
            type="number"
            step="any"
            inputMode="decimal"
            placeholder="—"
            value={lat ?? ""}
            onChange={(e) => setAxis("lat", e.target.value)}
          />
        </div>
        <div className="grid gap-1.5">
          <Label className="text-xs text-muted-foreground">Longitude</Label>
          <Input
            type="number"
            step="any"
            inputMode="decimal"
            placeholder="—"
            value={lng ?? ""}
            onChange={(e) => setAxis("lng", e.target.value)}
          />
        </div>
      </div>
    </div>
  );
}
