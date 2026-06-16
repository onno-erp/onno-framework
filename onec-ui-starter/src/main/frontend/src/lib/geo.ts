import type { Feature, FeatureCollection, Geometry, Position } from "geojson";
import type { EntityRecord } from "@/lib/types";

/**
 * Shared geolocation plumbing for every map surface: the field editor, the dashboard map widget,
 * the list map view, and the read-only detail map. One place that parses a stored value into
 * GeoJSON, so every surface agrees on the format and the bounds.
 *
 * <p>A geometry can be stored two ways: as a single {@code "lat,lng"} string (a Point — what the
 * simple picker writes, and the legacy format) or as a GeoJSON string (a {@code Feature},
 * {@code FeatureCollection}, or bare geometry — points, paths, and areas, what the geometry editor
 * writes). {@link toFeatureCollection} reads either; {@link extractLatLng} keeps the point-only
 * fast path the marker plotting uses.</p>
 */

export type LatLng = [number, number];

function inRange(lat: number, lng: number): LatLng | null {
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return [lat, lng];
}

/** Coerce a stored value to a finite number, treating null/blank as absent (not 0). */
function num(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const n = typeof value === "number" ? value : Number(value);
  return Number.isNaN(n) ? null : n;
}

/** Parse a stored {@code "lat,lng"} string into a validated [lat, lng], or null if malformed/out of range. */
export function parseLatLng(value: unknown): LatLng | null {
  if (typeof value !== "string") return null;
  const parts = value.split(",").map((s) => Number(s.trim()));
  if (parts.length !== 2 || parts.some((n) => Number.isNaN(n))) return null;
  return inRange(parts[0], parts[1]);
}

/** Format a coordinate pair back to the stored {@code "lat,lng"} string (6 dp ≈ 0.1 m). */
export function formatLatLng(lat: number, lng: number): string {
  return `${lat.toFixed(6)},${lng.toFixed(6)}`;
}

/**
 * How a record's geometry is sourced. Markers come from a combined {@code geoField}
 * ({@code "lat,lng"} string) or a {@code latField}/{@code lngField} pair; arbitrary shapes
 * (points/paths/areas) come from a {@code geoJsonField} (a GeoJSON string). A record may use any
 * combination — the marker and the shape are both plotted.
 */
export interface GeoSource {
  geoField?: string;
  latField?: string;
  lngField?: string;
  geoJsonField?: string;
}

/** Extract a record's point [lat, lng] per the configured {@link GeoSource}, or null when it has none. */
export function extractLatLng(row: EntityRecord, src: GeoSource): LatLng | null {
  if (src.geoField) {
    const point = parseLatLng(row[src.geoField]);
    if (point) return point;
  }
  if (src.latField && src.lngField) {
    const lat = num(row[src.latField]);
    const lng = num(row[src.lngField]);
    if (lat !== null && lng !== null) return inRange(lat, lng);
  }
  return null;
}

/**
 * Read a {@link GeoSource} from a config bag (the {@code geoField}/{@code latField}/{@code lngField}/
 * {@code geoJsonField} keys). Accepts any bag — a widget's {@code extraConfig} or a list's map config
 * — taking only the non-blank string entries.
 */
export function geoSourceFrom(cfg: Record<string, unknown> | undefined): GeoSource {
  const c = cfg ?? {};
  const str = (v: unknown) => (typeof v === "string" && v ? v : undefined);
  return {
    geoField: str(c.geoField),
    latField: str(c.latField),
    lngField: str(c.lngField),
    geoJsonField: str(c.geoJsonField),
  };
}

/** Whether a config bag names at least one usable geometry source (a point pair or a GeoJSON field). */
export function hasGeoSource(src: GeoSource): boolean {
  return !!src.geoField || !!(src.latField && src.lngField) || !!src.geoJsonField;
}

/** An empty FeatureCollection — the canonical "nothing drawn" value. */
export function emptyFeatureCollection(): FeatureCollection {
  return { type: "FeatureCollection", features: [] };
}

/** A single-Point FeatureCollection at [lat, lng] (note: GeoJSON order is [lng, lat]). */
export function pointFeatureCollection(lat: number, lng: number): FeatureCollection {
  return { type: "FeatureCollection", features: [pointFeature(lat, lng)] };
}

/** A GeoJSON Point feature from a [lat, lng] pair (emitting [lng, lat] per the spec). */
export function pointFeature(lat: number, lng: number, properties: Record<string, unknown> = {}): Feature {
  return { type: "Feature", geometry: { type: "Point", coordinates: [lng, lat] }, properties };
}

/** Wrap any GeoJSON object (FeatureCollection/Feature/Geometry) into a normalized FeatureCollection. */
function normalize(obj: unknown): FeatureCollection | null {
  if (!obj || typeof obj !== "object") return null;
  const g = obj as { type?: string };
  if (g.type === "FeatureCollection") {
    const fc = obj as FeatureCollection;
    return Array.isArray(fc.features) ? fc : null;
  }
  if (g.type === "Feature") {
    const f = obj as Feature;
    return f.geometry ? { type: "FeatureCollection", features: [f] } : null;
  }
  // A bare geometry (Point, LineString, Polygon, Multi*, GeometryCollection).
  if (typeof g.type === "string" && /^(Point|MultiPoint|LineString|MultiLineString|Polygon|MultiPolygon|GeometryCollection)$/.test(g.type)) {
    return { type: "FeatureCollection", features: [{ type: "Feature", geometry: obj as Geometry, properties: {} }] };
  }
  return null;
}

/**
 * Parse a stored geometry value into a normalized {@link FeatureCollection}, or null when empty. Accepts
 * a {@code "lat,lng"} string (→ a Point), a GeoJSON string, or an already-parsed GeoJSON object.
 */
export function toFeatureCollection(value: unknown): FeatureCollection | null {
  if (value == null || value === "") return null;
  const point = parseLatLng(value);
  if (point) return pointFeatureCollection(point[0], point[1]);
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
    try {
      return normalize(JSON.parse(trimmed));
    } catch {
      return null;
    }
  }
  return normalize(value);
}

/**
 * The GeoJSON features a record contributes per the configured {@link GeoSource}: a marker Point from
 * the point field(s), plus any geometry from the {@code geoJsonField}. Each feature carries the given
 * {@code properties} (e.g. id/label/href) so the renderer can build popups and links.
 */
export function featuresFromRow(row: EntityRecord, src: GeoSource, properties: Record<string, unknown> = {}): Feature[] {
  const out: Feature[] = [];
  const point = extractLatLng(row, src);
  if (point) out.push(pointFeature(point[0], point[1], properties));
  if (src.geoJsonField) {
    const fc = toFeatureCollection(row[src.geoJsonField]);
    if (fc) for (const f of fc.features) out.push({ ...f, properties: { ...(f.properties ?? {}), ...properties } });
  }
  return out;
}

/** Walk a Position tree (any nesting depth) applying {@code fn} to each [lng, lat] coordinate. */
function eachPosition(coords: unknown, fn: (lng: number, lat: number) => void): void {
  if (!Array.isArray(coords)) return;
  if (typeof coords[0] === "number" && typeof coords[1] === "number") {
    fn(coords[0] as number, coords[1] as number);
    return;
  }
  for (const c of coords) eachPosition(c, fn);
}

/** [west, south, east, north] enclosing every coordinate in the features, or null when there are none. */
export function boundsOf(features: Feature[]): [number, number, number, number] | null {
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity;
  const visit = (g: Geometry | null | undefined) => {
    if (!g) return;
    if (g.type === "GeometryCollection") {
      g.geometries.forEach(visit);
      return;
    }
    eachPosition((g as { coordinates?: Position[] }).coordinates, (lng, lat) => {
      if (lng < west) west = lng;
      if (lng > east) east = lng;
      if (lat < south) south = lat;
      if (lat > north) north = lat;
    });
  };
  for (const f of features) visit(f.geometry);
  if (west === Infinity) return null;
  return [west, south, east, north];
}
