import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * A geolocation picker for an attribute whose field hint sets {@code .widget("map")}.
 * The value is a plain {@code "lat,lng"} string (so it round-trips through any String
 * attribute and renders as coordinates in read views with no extra metadata). Click the
 * map to drop/move the marker, or type precise coordinates into the lat/lng fields.
 *
 * <p>Uses Leaflet over OpenStreetMap tiles — no API key, no billing. The marker is a
 * self-contained {@code divIcon} (an inline SVG pin) so it never depends on Leaflet's
 * default marker image assets, which break under a bundler without extra wiring.</p>
 */

const MARKER = L.divIcon({
  className: "",
  // A teardrop pin centered on its tip (iconAnchor at the bottom-middle).
  html:
    '<svg width="26" height="34" viewBox="0 0 26 34" xmlns="http://www.w3.org/2000/svg">' +
    '<path d="M13 0C5.82 0 0 5.82 0 13c0 9.2 11.1 19.6 11.6 20.04a2 2 0 0 0 2.8 0C14.9 32.6 26 22.2 26 13 26 5.82 20.18 0 13 0z" fill="#DC2626"/>' +
    '<circle cx="13" cy="13" r="5" fill="#fff"/></svg>',
  iconSize: [26, 34],
  iconAnchor: [13, 34],
});

// World view when no point is set yet.
const DEFAULT_CENTER: [number, number] = [20, 0];
const DEFAULT_ZOOM = 2;
const POINT_ZOOM = 13;

function parseLatLng(value: string | undefined): [number, number] | null {
  if (!value) return null;
  const parts = value.split(",").map((s) => Number(s.trim()));
  if (parts.length !== 2 || parts.some((n) => Number.isNaN(n))) return null;
  const [lat, lng] = parts;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return [lat, lng];
}

function format(lat: number, lng: number): string {
  return `${lat.toFixed(6)},${lng.toFixed(6)}`;
}

export function GeoPicker({
  value,
  onChange,
}: {
  value?: string;
  onChange: (val: string) => void;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const markerRef = useRef<L.Marker | null>(null);
  // Keep the latest onChange without re-running the map-init effect (it runs once).
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const point = parseLatLng(value);
  const lat = point?.[0];
  const lng = point?.[1];

  // Initialize the Leaflet map exactly once for this control's lifetime.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = L.map(containerRef.current, {
      center: point ?? DEFAULT_CENTER,
      zoom: point ? POINT_ZOOM : DEFAULT_ZOOM,
      // A map embedded in a form shouldn't hijack page scroll; zoom via the +/- control.
      scrollWheelZoom: false,
    });
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 19,
    }).addTo(map);
    map.on("click", (e: L.LeafletMouseEvent) => {
      onChangeRef.current(format(e.latlng.lat, e.latlng.lng));
    });
    mapRef.current = map;

    // The form often mounts in a hidden/animated island (visibility-toggled tabs), so the
    // map can initialize at zero size and render grey. Recompute on first paint and on any
    // container resize.
    const ro = new ResizeObserver(() => map.invalidateSize());
    ro.observe(containerRef.current);
    const t = window.setTimeout(() => map.invalidateSize(), 200);

    return () => {
      window.clearTimeout(t);
      ro.disconnect();
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
    // Intentionally once: subsequent value changes are reflected by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reflect the current value onto the map: place/move/remove the marker, and recenter
  // when a point first appears (e.g. typed in or a freshly-loaded record).
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (lat == null || lng == null) {
      markerRef.current?.remove();
      markerRef.current = null;
      return;
    }
    if (markerRef.current) {
      markerRef.current.setLatLng([lat, lng]);
    } else {
      markerRef.current = L.marker([lat, lng], { icon: MARKER }).addTo(map);
      map.setView([lat, lng], Math.max(map.getZoom(), POINT_ZOOM));
    }
  }, [lat, lng]);

  // Edit one axis from the numeric fields; the other keeps its value (0 if unset). Clearing
  // both fields clears the point.
  const setAxis = (axis: "lat" | "lng", raw: string) => {
    if (raw === "" && (axis === "lat" ? lng == null : lat == null)) {
      onChange("");
      return;
    }
    const n = Number(raw);
    if (Number.isNaN(n)) return;
    const nextLat = axis === "lat" ? n : lat ?? 0;
    const nextLng = axis === "lng" ? n : lng ?? 0;
    onChange(format(nextLat, nextLng));
  };

  return (
    <div className="grid gap-2">
      <div
        ref={containerRef}
        className="h-60 w-full overflow-hidden rounded-lg border border-border"
        // Leaflet needs an explicit height on its container; the class sets it, this is a
        // belt-and-braces fallback if utility CSS is purged.
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
