import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Check, MapPin, Pentagon, Spline, Trash2, Undo2 } from "lucide-react";
import type { Feature, FeatureCollection, Position } from "geojson";
import type { Map as MlMap, GeoJSONSource, MapMouseEvent } from "maplibre-gl";
import { cn } from "@/lib/utils";
import { useMapLibre } from "@/lib/use-maplibre";
import { readMapTheme } from "@/lib/map-style";
import { boundsOf, toFeatureCollection } from "@/lib/geo";

/**
 * The geometry editor for a field hinted {@code .widget("geojson")}: draw and edit points, paths
 * (lines), and areas (polygons) over the theme-aware monochrome basemap, storing the result as a
 * GeoJSON {@code FeatureCollection} string. Pick a tool, click to add points/vertices, double-click
 * or Finish to complete a line/area, drag the round handles to reshape, right-click a shape to
 * delete it. A lightweight drawer built straight on MapLibre's native API — no extra plugin.
 */

type Tool = "point" | "line" | "area" | null;
type Draft = { kind: "line" | "area"; coords: Position[] } | null;

const SRC_GEOM = "edit-geom";
const SRC_VERTS = "edit-verts";

/** Outer-ring vertices of a feature, tagged with their address so a drag can write them back. */
function vertexFeatures(features: Feature[]): Feature[] {
  const out: Feature[] = [];
  features.forEach((f, fi) => {
    const g = f.geometry;
    if (g.type === "Point") {
      out.push(handle(g.coordinates, fi, 0, 0));
    } else if (g.type === "LineString") {
      g.coordinates.forEach((c, vi) => out.push(handle(c, fi, 0, vi)));
    } else if (g.type === "Polygon") {
      // Skip each ring's closing point (a duplicate of its first).
      g.coordinates.forEach((ring, ri) =>
        ring.slice(0, -1).forEach((c, vi) => out.push(handle(c, fi, ri, vi)))
      );
    }
  });
  return out;
}

function handle(coord: Position, fi: number, ring: number, vi: number): Feature {
  return { type: "Feature", geometry: { type: "Point", coordinates: coord }, properties: { fi, ring, vi } };
}

/** The draft line/area as a preview feature (a Polygon once it has 3+ points, else a LineString). */
function draftFeature(draft: Draft): Feature | null {
  if (!draft || draft.coords.length === 0) return null;
  if (draft.kind === "area" && draft.coords.length >= 3) {
    return {
      type: "Feature",
      properties: { _draft: 1 },
      geometry: { type: "Polygon", coordinates: [[...draft.coords, draft.coords[0]]] },
    };
  }
  return { type: "Feature", properties: { _draft: 1 }, geometry: { type: "LineString", coordinates: draft.coords } };
}

/** Serialize committed features to a FeatureCollection string ("" when empty). */
function serialize(features: Feature[]): string {
  if (features.length === 0) return "";
  const fc: FeatureCollection = { type: "FeatureCollection", features };
  return JSON.stringify(fc);
}

export function MapEditor({
  value,
  onChange,
  height = 380,
}: {
  value?: string;
  onChange: (val: string) => void;
  height?: number;
}) {
  const [tool, setTool] = useState<Tool>(null);
  const [features, setFeatures] = useState<Feature[]>(() => toFeatureCollection(value)?.features ?? []);
  const [draft, setDraft] = useState<Draft>(null);

  // Mirror state into refs so the once-bound MapLibre handlers read current values.
  const toolRef = useRef(tool);
  toolRef.current = tool;
  const featuresRef = useRef(features);
  featuresRef.current = features;
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const lastEmitted = useRef(serialize(features));

  // Emit committed geometry, remembering what we sent so an external value sync doesn't loop.
  const emit = useCallback((next: Feature[]) => {
    const str = serialize(next);
    lastEmitted.current = str;
    onChangeRef.current(str);
  }, []);

  const geomFc = useMemo<FeatureCollection>(() => {
    const tagged = features.map((f, fi) => ({ ...f, properties: { ...(f.properties ?? {}), _fi: fi } }));
    const d = draftFeature(draft);
    return { type: "FeatureCollection", features: d ? [...tagged, d] : tagged };
  }, [features, draft]);

  const vertsFc = useMemo<FeatureCollection>(
    () => ({ type: "FeatureCollection", features: vertexFeatures(features) }),
    [features]
  );

  const addOverlay = (map: MlMap) => {
    const t = readMapTheme();
    if (!map.getSource(SRC_GEOM)) map.addSource(SRC_GEOM, { type: "geojson", data: geomFc });
    if (!map.getSource(SRC_VERTS)) map.addSource(SRC_VERTS, { type: "geojson", data: vertsFc });

    const add = (id: string, layer: Parameters<MlMap["addLayer"]>[0]) => {
      if (!map.getLayer(id)) map.addLayer(layer);
    };
    add("eg-fill", {
      id: "eg-fill", type: "fill", source: SRC_GEOM,
      filter: ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false],
      paint: { "fill-color": t.primary, "fill-opacity": ["case", ["==", ["get", "_draft"], 1], 0.1, 0.18] },
    });
    add("eg-line", {
      id: "eg-line", type: "line", source: SRC_GEOM,
      filter: ["all",
        ["match", ["geometry-type"], ["LineString", "MultiLineString", "Polygon", "MultiPolygon"], true, false],
        ["!=", ["get", "_draft"], 1]],
      paint: { "line-color": t.primary, "line-width": 2.5 },
    });
    // The in-progress draft renders dashed. line-dasharray can't be a data expression, so the
    // draft is its own layer with a static dash rather than a per-feature case on eg-line.
    add("eg-line-draft", {
      id: "eg-line-draft", type: "line", source: SRC_GEOM,
      filter: ["==", ["get", "_draft"], 1],
      paint: { "line-color": t.primary, "line-width": 2, "line-dasharray": [2, 1.5] },
    });
    add("eg-point", {
      id: "eg-point", type: "circle", source: SRC_GEOM,
      filter: ["match", ["geometry-type"], ["Point", "MultiPoint"], true, false],
      paint: { "circle-radius": 6, "circle-color": t.primary, "circle-stroke-color": t.onPrimary, "circle-stroke-width": 2 },
    });
    add("ev-handle", {
      id: "ev-handle", type: "circle", source: SRC_VERTS,
      paint: { "circle-radius": 5, "circle-color": t.surface, "circle-stroke-color": t.primary, "circle-stroke-width": 2 },
    });
  };

  const { containerRef, mapRef } = useMapLibre({
    interactive: true,
    onStyle: addOverlay,
    onReady: (map: MlMap) => {
      map.doubleClickZoom.disable();
      bindHandlers(map);
      const b = boundsOf(featuresRef.current);
      if (b) {
        const [w, s, e, n] = b;
        if (w === e && s === n) map.easeTo({ center: [w, s], zoom: 13, duration: 0 });
        else map.fitBounds([[w, s], [e, n]], { padding: 40, maxZoom: 16, duration: 0 });
      }
    },
  });

  // Bind the drawing/editing interactions once (they read state via refs, write via setState).
  const bindHandlers = (map: MlMap) => {
    map.on("click", (e: MapMouseEvent) => {
      const active = toolRef.current;
      if (!active) return;
      const coord: Position = [e.lngLat.lng, e.lngLat.lat];
      if (active === "point") {
        setFeatures((prev) => {
          const next = [...prev, { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: coord } } as Feature];
          emit(next);
          return next;
        });
      } else {
        setDraft((prev) => {
          if (!prev) return { kind: active, coords: [coord] };
          return { ...prev, coords: [...prev.coords, coord] };
        });
      }
    });

    // Double-click finishes a line/area (trimming the duplicate vertex the 2nd click added).
    map.on("dblclick", () => {
      if (!draftRef.current) return;
      finishDraft(true);
    });

    // Right-click a committed shape to delete it.
    map.on("contextmenu", (e: MapMouseEvent) => {
      const hit = map.queryRenderedFeatures(e.point, { layers: ["eg-fill", "eg-line", "eg-point"].filter((l) => map.getLayer(l)) });
      const f = hit.find((h) => h.properties && h.properties._fi !== undefined && h.properties._draft === undefined);
      if (!f) return;
      const fi = Number(f.properties!._fi);
      setFeatures((prev) => {
        const next = prev.filter((_, i) => i !== fi);
        emit(next);
        return next;
      });
    });

    // Drag a vertex handle to reshape a committed feature.
    let dragging: { fi: number; ring: number; vi: number } | null = null;
    map.on("mousedown", "ev-handle", (e) => {
      const p = e.features?.[0]?.properties;
      if (!p) return;
      e.preventDefault(); // suppress map pan
      dragging = { fi: Number(p.fi), ring: Number(p.ring), vi: Number(p.vi) };
      map.getCanvas().style.cursor = "grabbing";
    });
    map.on("mousemove", (e) => {
      if (!dragging) {
        if (toolRef.current) map.getCanvas().style.cursor = "crosshair";
        return;
      }
      const coord: Position = [e.lngLat.lng, e.lngLat.lat];
      const next = moveVertex(featuresRef.current, dragging, coord);
      featuresRef.current = next;
      (map.getSource(SRC_GEOM) as GeoJSONSource | undefined)?.setData({
        type: "FeatureCollection",
        features: next.map((f, fi) => ({ ...f, properties: { ...(f.properties ?? {}), _fi: fi } })),
      });
      (map.getSource(SRC_VERTS) as GeoJSONSource | undefined)?.setData({
        type: "FeatureCollection",
        features: vertexFeatures(next),
      });
    });
    const endDrag = () => {
      if (!dragging) return;
      dragging = null;
      map.getCanvas().style.cursor = toolRef.current ? "crosshair" : "";
      setFeatures(featuresRef.current);
      emit(featuresRef.current);
    };
    map.on("mouseup", endDrag);
    map.on("mouseenter", "ev-handle", () => { if (!toolRef.current) map.getCanvas().style.cursor = "grab"; });
    map.on("mouseleave", "ev-handle", () => { if (!dragging) map.getCanvas().style.cursor = toolRef.current ? "crosshair" : ""; });
  };

  // Commit the current draft as a finished line/area.
  const finishDraft = (trimLast: boolean) => {
    const d = draftRef.current;
    if (!d) return;
    let coords = d.coords;
    if (trimLast && coords.length > 1) coords = coords.slice(0, -1); // drop the dblclick duplicate
    const ok = d.kind === "line" ? coords.length >= 2 : coords.length >= 3;
    if (!ok) return;
    const feature: Feature =
      d.kind === "area"
        ? { type: "Feature", properties: {}, geometry: { type: "Polygon", coordinates: [[...coords, coords[0]]] } }
        : { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } };
    setDraft(null);
    setFeatures((prev) => {
      const next = [...prev, feature];
      emit(next);
      return next;
    });
  };

  const undo = () => {
    if (draftRef.current && draftRef.current.coords.length > 0) {
      setDraft((prev) => (prev && prev.coords.length > 1 ? { ...prev, coords: prev.coords.slice(0, -1) } : null));
      return;
    }
    setFeatures((prev) => {
      const next = prev.slice(0, -1);
      emit(next);
      return next;
    });
  };

  const clear = () => {
    setDraft(null);
    setFeatures(() => {
      emit([]);
      return [];
    });
  };

  // Keyboard: Enter finishes, Escape cancels the draft.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Enter" && draftRef.current) { e.preventDefault(); finishDraft(false); }
      if (e.key === "Escape") setDraft(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reflect committed + draft geometry into the live sources whenever they change.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    (map.getSource(SRC_GEOM) as GeoJSONSource | undefined)?.setData(geomFc);
    (map.getSource(SRC_VERTS) as GeoJSONSource | undefined)?.setData(vertsFc);
  }, [geomFc, vertsFc, mapRef]);

  // External value changes (form reset / record load) that we didn't emit reset the geometry.
  useEffect(() => {
    if ((value ?? "") === lastEmitted.current) return;
    const next = toFeatureCollection(value)?.features ?? [];
    lastEmitted.current = serialize(next);
    setFeatures(next);
    setDraft(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  const pick = (t: Tool) => {
    setDraft(null);
    setTool((cur) => (cur === t ? null : t));
  };

  const tools: { id: Exclude<Tool, null>; label: string; icon: typeof MapPin }[] = [
    { id: "point", label: "Point", icon: MapPin },
    { id: "line", label: "Line", icon: Spline },
    { id: "area", label: "Area", icon: Pentagon },
  ];

  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center gap-1.5">
        {tools.map((tl) => {
          const Icon = tl.icon;
          const on = tool === tl.id;
          return (
            <button
              key={tl.id}
              type="button"
              onClick={() => pick(tl.id)}
              aria-pressed={on}
              className={cn(
                "inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-sm font-medium transition-colors",
                on ? "border-primary bg-primary text-primary-foreground" : "border-input bg-muted text-foreground hover:bg-accent"
              )}
            >
              <Icon className="size-4" />
              {tl.label}
            </button>
          );
        })}
        <span className="mx-1 h-5 w-px bg-border" />
        <button
          type="button"
          onClick={() => finishDraft(false)}
          disabled={!draft}
          className="inline-flex h-8 items-center gap-1.5 rounded-md border border-input bg-muted px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
        >
          <Check className="size-4" /> Finish
        </button>
        <button
          type="button"
          onClick={undo}
          className="inline-flex h-8 items-center gap-1.5 rounded-md border border-input bg-muted px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-accent"
        >
          <Undo2 className="size-4" /> Undo
        </button>
        <button
          type="button"
          onClick={clear}
          className="inline-flex h-8 items-center gap-1.5 rounded-md border border-input bg-muted px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-accent"
        >
          <Trash2 className="size-4" /> Clear
        </button>
      </div>
      <div
        ref={containerRef}
        className="w-full overflow-hidden rounded-lg border border-border"
        style={{ height, minHeight: height }}
      />
      <p className="text-xs text-muted-foreground">
        {tool === "point"
          ? "Click the map to drop points."
          : tool
            ? "Click to add vertices; double-click or Finish to complete. Drag handles to reshape; right-click a shape to delete."
            : "Pick a tool to draw. Drag handles to reshape an existing shape; right-click to delete it."}
      </p>
    </div>
  );
}

/** Write a dragged coordinate back into the addressed vertex, returning a new features array. */
function moveVertex(features: Feature[], at: { fi: number; ring: number; vi: number }, coord: Position): Feature[] {
  return features.map((f, fi) => {
    if (fi !== at.fi) return f;
    const g = f.geometry;
    if (g.type === "Point") {
      return { ...f, geometry: { ...g, coordinates: coord } };
    }
    if (g.type === "LineString") {
      const coords = g.coordinates.map((c, i) => (i === at.vi ? coord : c));
      return { ...f, geometry: { ...g, coordinates: coords } };
    }
    if (g.type === "Polygon") {
      const rings = g.coordinates.map((ring, ri) => {
        if (ri !== at.ring) return ring;
        const updated = ring.map((c, i) => (i === at.vi ? coord : c));
        // Keep the ring closed: if we moved the first point, move its closing duplicate too.
        if (at.vi === 0 && updated.length > 1) updated[updated.length - 1] = coord;
        return updated;
      });
      return { ...f, geometry: { ...g, coordinates: rings } };
    }
    return f;
  });
}
