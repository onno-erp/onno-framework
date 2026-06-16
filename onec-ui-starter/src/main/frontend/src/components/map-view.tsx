import { useEffect, useMemo, useRef } from "react";
import maplibregl from "maplibre-gl";
import type { Feature, FeatureCollection } from "geojson";
import type { Map as MlMap, GeoJSONSource, MapGeoJSONFeature, StyleSpecification } from "maplibre-gl";
import { useMapLibre } from "@/lib/use-maplibre";
import { readMapTheme } from "@/lib/map-style";
import { boundsOf } from "@/lib/geo";

/**
 * The read map: plots a set of GeoJSON features — points, paths (lines), and areas (polygons) —
 * over the theme-aware monochrome basemap, all tinted with the app's brand color. Shared by the
 * dashboard map widget, the entity list's map view, and the read-only detail field. A feature's
 * popup shows its {@code label} and, when it carries an {@code href}, an "Open" link that routes
 * through the host's {@code onec://} action bus (the same navigation a list-row tap produces).
 */

const SOURCE = "onec-geo";
const FILL = "onec-geo-fill";
const OUTLINE = "onec-geo-outline";
const LINE = "onec-geo-line";
const POINT = "onec-geo-point";
const HIT_LAYERS = [FILL, LINE, POINT];

function navigate(url: string) {
  window.dispatchEvent(new CustomEvent("onec:action", { detail: url }));
}

function popupDom(props: Record<string, unknown>): HTMLElement | null {
  const label = props.label ? String(props.label) : "";
  const href = props.href ? String(props.href) : "";
  if (!label && !href) return null;
  const wrap = document.createElement("div");
  if (label) {
    const title = document.createElement("div");
    title.textContent = label;
    title.className = "text-sm font-semibold text-foreground";
    wrap.appendChild(title);
  }
  if (href) {
    const link = document.createElement("button");
    link.type = "button";
    link.textContent = "Open →";
    link.className = "mt-1 cursor-pointer text-xs font-medium text-primary hover:underline";
    link.addEventListener("click", () => navigate(href));
    wrap.appendChild(link);
  }
  return wrap;
}

export function MapView({
  features,
  height = 240,
  interactive = false,
  styleOverride,
  className,
}: {
  features: Feature[];
  height?: number;
  interactive?: boolean;
  styleOverride?: string | StyleSpecification;
  className?: string;
}) {
  const fc = useMemo<FeatureCollection>(
    () => ({ type: "FeatureCollection", features }),
    [features]
  );
  const fcRef = useRef(fc);
  fcRef.current = fc;

  // (Re)attach the overlay after every style load (initial + theme flip), reading fresh theme colors.
  const addOverlay = (map: MlMap) => {
    const t = readMapTheme();
    if (!map.getSource(SOURCE)) {
      map.addSource(SOURCE, { type: "geojson", data: fcRef.current });
    }
    if (!map.getLayer(FILL)) {
      map.addLayer({
        id: FILL,
        type: "fill",
        source: SOURCE,
        filter: ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false],
        paint: { "fill-color": t.primary, "fill-opacity": 0.18 },
      });
    }
    if (!map.getLayer(OUTLINE)) {
      map.addLayer({
        id: OUTLINE,
        type: "line",
        source: SOURCE,
        filter: ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false],
        paint: { "line-color": t.primary, "line-width": 2 },
      });
    }
    if (!map.getLayer(LINE)) {
      map.addLayer({
        id: LINE,
        type: "line",
        source: SOURCE,
        filter: ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false],
        paint: { "line-color": t.primary, "line-width": 3, "line-opacity": 0.9 },
      });
    }
    if (!map.getLayer(POINT)) {
      map.addLayer({
        id: POINT,
        type: "circle",
        source: SOURCE,
        filter: ["match", ["geometry-type"], ["Point", "MultiPoint"], true, false],
        paint: {
          "circle-radius": 6,
          "circle-color": t.primary,
          "circle-stroke-color": t.onPrimary,
          "circle-stroke-width": 2,
        },
      });
    }
  };

  const fit = (map: MlMap) => {
    const b = boundsOf(fcRef.current.features);
    if (!b) return;
    const [w, s, e, n] = b;
    if (w === e && s === n) {
      map.easeTo({ center: [w, s], zoom: Math.max(map.getZoom(), 13), duration: 0 });
    } else {
      map.fitBounds([[w, s], [e, n]], { padding: 36, maxZoom: 16, duration: 0 });
    }
  };

  const onReady = (map: MlMap) => {
    fit(map);
    const open = (e: maplibregl.MapMouseEvent) => {
      const hit = map.queryRenderedFeatures(e.point, { layers: HIT_LAYERS.filter((l) => map.getLayer(l)) });
      const f = hit.find((h: MapGeoJSONFeature) => h.properties && (h.properties.label || h.properties.href));
      if (!f) return;
      const dom = popupDom(f.properties as Record<string, unknown>);
      if (dom) new maplibregl.Popup({ closeButton: false, offset: 12 }).setLngLat(e.lngLat).setDOMContent(dom).addTo(map);
    };
    map.on("click", open);
    // Pointer affordance over interactive features.
    const enter = () => (map.getCanvas().style.cursor = "pointer");
    const leave = () => (map.getCanvas().style.cursor = "");
    for (const l of HIT_LAYERS) {
      map.on("mouseenter", l, enter);
      map.on("mouseleave", l, leave);
    }
  };

  const { containerRef, mapRef } = useMapLibre({ interactive, styleOverride, onStyle: addOverlay, onReady });

  // Push new data into the live source and re-frame when the features change.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const src = map.getSource(SOURCE) as GeoJSONSource | undefined;
    if (src) {
      src.setData(fc);
      fit(map);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fc]);

  return (
    <div
      ref={containerRef}
      className={className ?? "w-full overflow-hidden rounded-lg border border-border"}
      style={{ height, minHeight: height }}
    />
  );
}
