import { useEffect, useMemo, useRef } from "react";
import maplibregl from "maplibre-gl";
import type { Feature, FeatureCollection, Point } from "geojson";
import type { Map as MlMap, GeoJSONSource, MapGeoJSONFeature, StyleSpecification } from "maplibre-gl";
import { useMapLibre } from "@/lib/use-maplibre";
import { readMapTheme } from "@/lib/map-style";
import { boundsOf } from "@/lib/geo";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";

/**
 * The read map: plots a set of GeoJSON features — points, paths (lines), and areas (polygons) —
 * over the theme-aware monochrome basemap, all tinted with the app's brand color. Shared by the
 * dashboard map widget, the entity list's map view, and the read-only detail field.
 *
 * <p>Points are <b>clustered</b>: they live in their own GeoJSON source with MapLibre's built-in
 * clustering, so thousands of markers collapse into brand-colored count badges that split apart as
 * you zoom (clicking one zooms into it). The badges are drawn on a canvas and registered through
 * {@code styleimagemissing}, which keeps them theme-aware on light/dark flips and — unlike a symbol
 * text layer — needs no glyph endpoint on the basemap style. Lines/polygons stay in a second,
 * unclustered source (MapLibre's clustering drops non-point geometry).</p>
 *
 * <p>Clicking a feature opens a popup card with its {@code label}, an optional {@code sublabel},
 * and — when it carries an {@code href} — an "Open" action that routes through the host's
 * {@code onno://} action bus (the same navigation a list-row tap produces). When several features
 * overlap under the cursor the popup becomes a small list of all of them.</p>
 */

const SHAPES = "onno-geo";
const POINTS = "onno-geo-points";
const FILL = "onno-geo-fill";
const OUTLINE = "onno-geo-outline";
const LINE = "onno-geo-line";
const POINT_HALO = "onno-geo-point-halo";
const POINT = "onno-geo-point";
const CLUSTER = "onno-geo-cluster";
const CLUSTER_IMG = "onno-cluster-";
// The halo participates in hit-testing so a marker's click/touch target is the full glow, not
// just the small core dot (dedupe in the click handler collapses the double hit).
const HIT_LAYERS = [FILL, LINE, POINT, POINT_HALO];

function navigate(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}

/** Approximate numeric value of MapLibre's abbreviated cluster count ("842", "1.2k", "3m"). */
function approxCount(label: string): number {
  const m = /^(\d+(?:\.\d+)?)\s*([km])?$/i.exec(label.trim());
  if (!m) return 1;
  const mult = m[2]?.toLowerCase() === "m" ? 1_000_000 : m[2] ? 1_000 : 1;
  return parseFloat(m[1]) * mult;
}

/**
 * Draw a cluster badge for an abbreviated count: a brand-colored disc (sized by magnitude) with a
 * soft halo, an inner ring, and the count itself — rendered at device pixel ratio so it stays crisp.
 * Reads the theme at draw time; the style swap on a theme flip drops old images, so badges are
 * always regenerated in the current palette.
 */
function clusterBadge(countLabel: string): { data: ImageData; pixelRatio: number } | null {
  const t = readMapTheme();
  const count = approxCount(countLabel);
  const r = Math.min(27, 15 + Math.log10(Math.max(count, 2)) * 3.5);
  const pad = 8;
  const dpr = Math.min(Math.max(window.devicePixelRatio || 1, 1), 3);
  const size = Math.ceil((r + pad) * 2);
  const canvas = document.createElement("canvas");
  canvas.width = size * dpr;
  canvas.height = size * dpr;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.scale(dpr, dpr);
  const c = size / 2;
  const disc = (radius: number, alpha: number) => {
    ctx.globalAlpha = alpha;
    ctx.beginPath();
    ctx.arc(c, c, radius, 0, Math.PI * 2);
    ctx.fill();
  };
  ctx.fillStyle = t.primary;
  disc(r + pad - 1, 0.16);
  disc(r + 3.5, 0.3);
  disc(r, 1);
  ctx.globalAlpha = 0.85;
  ctx.strokeStyle = t.onPrimary;
  ctx.lineWidth = 1.25;
  ctx.beginPath();
  ctx.arc(c, c, r - 2.5, 0, Math.PI * 2);
  ctx.stroke();
  ctx.globalAlpha = 1;
  ctx.fillStyle = t.onPrimary;
  const fontPx = Math.max(10, Math.round(r * (countLabel.length > 3 ? 0.58 : 0.74)));
  ctx.font = `600 ${fontPx}px ui-sans-serif, system-ui, -apple-system, sans-serif`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(countLabel, c, c + 0.5);
  return { data: ctx.getImageData(0, 0, canvas.width, canvas.height), pixelRatio: dpr };
}

interface PopupItem {
  label: string;
  sublabel?: string;
  href?: string;
}

function itemFrom(props: Record<string, unknown> | null): PopupItem | null {
  const label = props?.label ? String(props.label) : "";
  const href = props?.href ? String(props.href) : "";
  if (!label && !href) return null;
  const sublabel = props?.sublabel ? String(props.sublabel) : undefined;
  return { label, sublabel, href: href || undefined };
}

/** The popup card: a single record's title/sublabel/Open action, or a list when features overlap. */
function popupDom(items: PopupItem[], t: Translate): HTMLElement | null {
  if (items.length === 0) return null;
  const wrap = document.createElement("div");
  wrap.className = "min-w-[180px] max-w-[280px]";

  if (items.length === 1) {
    const it = items[0];
    if (it.label) {
      const title = document.createElement("div");
      title.textContent = it.label;
      title.className = "text-sm font-semibold leading-snug text-popover-foreground";
      wrap.appendChild(title);
    }
    if (it.sublabel) {
      const sub = document.createElement("div");
      sub.textContent = it.sublabel;
      sub.className = "mt-0.5 text-xs text-muted-foreground";
      wrap.appendChild(sub);
    }
    if (it.href) {
      const link = document.createElement("button");
      link.type = "button";
      link.textContent = `${t("action.open")} →`;
      link.className =
        "mt-2.5 inline-flex h-7 cursor-pointer items-center rounded-full bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90";
      link.addEventListener("click", () => navigate(it.href!));
      wrap.appendChild(link);
    }
    return wrap;
  }

  const MAX_ROWS = 8;
  const header = document.createElement("div");
  header.textContent = t("map.recordsHere", { count: items.length });
  header.className = "mb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground";
  wrap.appendChild(header);
  const list = document.createElement("div");
  list.className = "max-h-56 overflow-y-auto";
  for (const it of items.slice(0, MAX_ROWS)) {
    const row = document.createElement(it.href ? "button" : "div");
    if (it.href) (row as HTMLButtonElement).type = "button";
    row.className =
      "group flex w-full items-center justify-between gap-3 border-b border-border py-1.5 text-left last:border-b-0" +
      (it.href ? " cursor-pointer" : "");
    const text = document.createElement("div");
    text.className = "min-w-0";
    const title = document.createElement("div");
    title.textContent = it.label || t("action.open");
    title.className =
      "truncate text-sm font-medium text-popover-foreground" + (it.href ? " group-hover:text-primary" : "");
    text.appendChild(title);
    if (it.sublabel) {
      const sub = document.createElement("div");
      sub.textContent = it.sublabel;
      sub.className = "truncate text-xs text-muted-foreground";
      text.appendChild(sub);
    }
    row.appendChild(text);
    if (it.href) {
      const arrow = document.createElement("span");
      arrow.textContent = "→";
      arrow.className = "shrink-0 text-xs text-muted-foreground group-hover:text-primary";
      row.appendChild(arrow);
      row.addEventListener("click", () => navigate(it.href!));
    }
    list.appendChild(row);
  }
  wrap.appendChild(list);
  if (items.length > MAX_ROWS) {
    const more = document.createElement("div");
    more.textContent = t("map.more", { count: items.length - MAX_ROWS });
    more.className = "pt-1.5 text-xs text-muted-foreground";
    wrap.appendChild(more);
  }
  return wrap;
}

export function MapView({
  features,
  height = 240,
  fill = false,
  interactive = false,
  styleOverride,
  className,
}: {
  features: Feature[];
  /** Fixed pixel height; ignored when {@code fill} is set. */
  height?: number;
  /** Fill the parent's height (the parent provides it, e.g. a flexed route surface). */
  fill?: boolean;
  interactive?: boolean;
  styleOverride?: string | StyleSpecification;
  className?: string;
}) {
  // Points cluster; everything else renders as-is — so they live in two sources (MapLibre's
  // clustering silently drops non-point geometry). MultiPoints are exploded into cluster-able Points.
  const split = useMemo(() => {
    const points: Feature[] = [];
    const shapes: Feature[] = [];
    for (const f of features) {
      const g = f.geometry;
      if (!g) continue;
      if (g.type === "Point") points.push(f);
      else if (g.type === "MultiPoint") {
        for (const coords of g.coordinates) {
          points.push({ type: "Feature", geometry: { type: "Point", coordinates: coords }, properties: f.properties });
        }
      } else shapes.push(f);
    }
    return {
      points: { type: "FeatureCollection", features: points } as FeatureCollection,
      shapes: { type: "FeatureCollection", features: shapes } as FeatureCollection,
      all: features,
    };
  }, [features]);
  const splitRef = useRef(split);
  splitRef.current = split;
  const t = useMessages();
  const tRef = useRef(t);
  tRef.current = t;

  // (Re)attach the overlay after every style load (initial + theme flip), reading fresh theme colors.
  const addOverlay = (map: MlMap) => {
    const th = readMapTheme();
    if (!map.getSource(SHAPES)) {
      map.addSource(SHAPES, { type: "geojson", data: splitRef.current.shapes });
    }
    if (!map.getSource(POINTS)) {
      map.addSource(POINTS, {
        type: "geojson",
        data: splitRef.current.points,
        cluster: true,
        clusterMaxZoom: 14,
        clusterRadius: 52,
      });
    }
    if (!map.getLayer(FILL)) {
      map.addLayer({
        id: FILL,
        type: "fill",
        source: SHAPES,
        filter: ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false],
        paint: { "fill-color": th.primary, "fill-opacity": 0.18 },
      });
    }
    if (!map.getLayer(OUTLINE)) {
      map.addLayer({
        id: OUTLINE,
        type: "line",
        source: SHAPES,
        filter: ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false],
        paint: { "line-color": th.primary, "line-width": 2 },
      });
    }
    if (!map.getLayer(LINE)) {
      map.addLayer({
        id: LINE,
        type: "line",
        source: SHAPES,
        filter: ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false],
        paint: { "line-color": th.primary, "line-width": 3, "line-opacity": 0.9 },
      });
    }
    // A soft brand-colored glow under each marker lifts it off the muted basemap.
    if (!map.getLayer(POINT_HALO)) {
      map.addLayer({
        id: POINT_HALO,
        type: "circle",
        source: POINTS,
        filter: ["!", ["has", "point_count"]],
        paint: {
          "circle-radius": 12,
          "circle-color": th.primary,
          "circle-opacity": 0.16,
          "circle-blur": 0.4,
        },
      });
    }
    if (!map.getLayer(POINT)) {
      map.addLayer({
        id: POINT,
        type: "circle",
        source: POINTS,
        filter: ["!", ["has", "point_count"]],
        paint: {
          "circle-radius": 6.5,
          "circle-color": th.primary,
          "circle-stroke-color": th.onPrimary,
          "circle-stroke-width": 2,
        },
      });
    }
    // Cluster badges: canvas images resolved on demand via styleimagemissing (see onReady), keyed
    // by the abbreviated count so each distinct label is drawn exactly once per style.
    if (!map.getLayer(CLUSTER)) {
      map.addLayer({
        id: CLUSTER,
        type: "symbol",
        source: POINTS,
        filter: ["has", "point_count"],
        layout: {
          "icon-image": ["concat", CLUSTER_IMG, ["to-string", ["get", "point_count_abbreviated"]]],
          "icon-allow-overlap": true,
          "icon-ignore-placement": true,
        },
      });
    }
  };

  // Frame the data — but only once the container actually has a size. Maps mount inside animated/
  // portaled islands that start at 0×0; a fitBounds computed then lands on a world view, so the fit
  // stays pending until a resize delivers real dimensions.
  const needsFitRef = useRef(true);
  const fit = (map: MlMap) => {
    const el = map.getContainer();
    if (!el.clientWidth || !el.clientHeight) return;
    const b = boundsOf(splitRef.current.all);
    if (!b) return;
    needsFitRef.current = false;
    const [w, s, e, n] = b;
    if (w === e && s === n) {
      map.easeTo({ center: [w, s], zoom: Math.max(map.getZoom(), 13), duration: 0 });
    } else {
      map.fitBounds([[w, s], [e, n]], { padding: 40, maxZoom: 16, duration: 0 });
    }
  };

  // Draw a missing cluster badge on demand. Attached before the first style loads so the first
  // render's requests aren't missed; it survives style swaps (it hangs off the map, not the style),
  // and a theme flip wipes the style's images — so badges re-render in the new palette automatically.
  const onInit = (map: MlMap) => {
    map.on("styleimagemissing", (e: { id: string }) => {
      if (!e.id.startsWith(CLUSTER_IMG) || map.hasImage(e.id)) return;
      const badge = clusterBadge(e.id.slice(CLUSTER_IMG.length));
      if (badge && !map.hasImage(e.id)) map.addImage(e.id, badge.data, { pixelRatio: badge.pixelRatio });
    });
    // A pending fit (container was 0-sized, or data arrived pre-style) runs once there's room.
    map.on("resize", () => {
      if (needsFitRef.current) fit(map);
    });
  };

  const onReady = (map: MlMap) => {
    if (needsFitRef.current) fit(map);

    // Clicking a cluster zooms to where it breaks apart — or two levels in when the expansion
    // zoom can't be resolved (a re-clustered tile can invalidate the id), so a click always acts.
    map.on("click", CLUSTER, (e) => {
      const f = e.features?.[0];
      if (!f) return;
      const center = (f.geometry as Point).coordinates as [number, number];
      const zoomTo = (zoom: number) => map.easeTo({ center, zoom, duration: 500 });
      const clusterId = Number(f.properties?.cluster_id);
      const src = map.getSource(POINTS) as GeoJSONSource | undefined;
      if (!src || Number.isNaN(clusterId)) {
        zoomTo(map.getZoom() + 2);
        return;
      }
      src
        .getClusterExpansionZoom(clusterId)
        .then((zoom) => zoomTo(Number.isFinite(zoom) ? zoom : map.getZoom() + 2))
        .catch(() => zoomTo(map.getZoom() + 2));
    });

    const open = (e: maplibregl.MapMouseEvent) => {
      const layers = [CLUSTER, ...HIT_LAYERS].filter((l) => map.getLayer(l));
      const hits = map.queryRenderedFeatures(e.point, { layers });
      // A cluster click zooms (handled above) — never opens a popup.
      if (hits.some((h: MapGeoJSONFeature) => h.layer.id === CLUSTER)) return;
      const items: PopupItem[] = [];
      const seen = new Set<string>();
      for (const h of hits) {
        const item = itemFrom(h.properties as Record<string, unknown> | null);
        if (!item) continue;
        const key = item.href ?? `label:${item.label}`;
        if (seen.has(key)) continue;
        seen.add(key);
        items.push(item);
      }
      const dom = popupDom(items, tRef.current);
      if (!dom) return;
      new maplibregl.Popup({ closeButton: false, offset: 14, maxWidth: "320px" })
        .setLngLat(e.lngLat)
        .setDOMContent(dom)
        .addTo(map);
    };
    map.on("click", open);
    // Pointer affordance over interactive features.
    const enter = () => (map.getCanvas().style.cursor = "pointer");
    const leave = () => (map.getCanvas().style.cursor = "");
    for (const l of [...HIT_LAYERS, CLUSTER]) {
      map.on("mouseenter", l, enter);
      map.on("mouseleave", l, leave);
    }
  };

  const { containerRef, mapRef } = useMapLibre({ interactive, styleOverride, onInit, onStyle: addOverlay, onReady });

  // Push new data into the live sources and re-frame when the features change.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const pts = map.getSource(POINTS) as GeoJSONSource | undefined;
    const shp = map.getSource(SHAPES) as GeoJSONSource | undefined;
    if (pts) pts.setData(split.points);
    if (shp) shp.setData(split.shapes);
    needsFitRef.current = true;
    fit(map);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [split]);

  return (
    <div
      ref={containerRef}
      className={className ?? "w-full overflow-hidden rounded-card border border-border"}
      style={fill ? { height: "100%", minHeight: 320 } : { height, minHeight: height }}
    />
  );
}
