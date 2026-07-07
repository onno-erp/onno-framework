import { useEffect, useRef } from "react";
import maplibregl from "maplibre-gl";
import type { Map as MlMap, StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { resolveBasemap, isDarkNow, useIsDark } from "@/lib/map-style";

/**
 * Shared MapLibre lifecycle for the read map and the editor: create the map once, swap the basemap
 * when the app theme flips light/dark, and re-attach overlay layers afterward. MapLibre wipes
 * sources/layers on a {@code setStyle}, so the consumer puts all of its {@code addSource}/
 * {@code addLayer} in {@code onStyle} — which runs on the first style load and after every theme
 * swap — and reads fresh theme colors each time. One-time framing/handlers go in {@code onReady}.
 */
export interface UseMapLibreOpts {
  /** Pan/zoom/draw enabled (read maps that are just thumbnails can pass false). */
  interactive?: boolean;
  /** Override the built-in monochrome basemap with a style URL or full MapLibre style. */
  styleOverride?: string | StyleSpecification;
  /**
   * One-time setup right after the map object is created, before the first style loads — for
   * handlers that must not miss first-render events (e.g. {@code styleimagemissing}).
   */
  onInit?: (map: MlMap) => void;
  /** (Re)add overlay sources + layers here; runs on initial load and after each theme swap. */
  onStyle?: (map: MlMap) => void;
  /** One-time setup after the map first loads (framing, click handlers). */
  onReady?: (map: MlMap) => void;
}

export function useMapLibre(opts: UseMapLibreOpts) {
  const { interactive = true, styleOverride, onInit, onStyle, onReady } = opts;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MlMap | null>(null);
  const dark = useIsDark();
  // Keep the latest callbacks without re-initializing the map.
  const onInitRef = useRef(onInit);
  onInitRef.current = onInit;
  const onStyleRef = useRef(onStyle);
  onStyleRef.current = onStyle;
  const onReadyRef = useRef(onReady);
  onReadyRef.current = onReady;

  // Initialize the map exactly once for this control's lifetime.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: resolveBasemap(isDarkNow(), styleOverride),
      center: [0, 20],
      zoom: 1.4,
      attributionControl: false,
      interactive,
      dragRotate: false,
      pitchWithRotate: false,
    });
    // A compact, collapsed attribution (the tiny ⓘ) — not the sprawling default footer.
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    if (interactive) {
      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    }
    mapRef.current = map;

    // Dev-only escape hatch: reach live map instances from the console/tests.
    if (import.meta.env.DEV) {
      const w = window as unknown as { __onnoMaps?: MlMap[] };
      w.__onnoMaps = [...(w.__onnoMaps ?? []).filter((m) => m !== map), map];
    }

    // Before the first style loads, so first-render events (styleimagemissing) aren't missed.
    onInitRef.current?.(map);

    map.on("style.load", () => onStyleRef.current?.(map));
    map.once("load", () => onReadyRef.current?.(map));

    // Maps often mount in a hidden/animated island (tabbed surfaces, a freshly portaled widget);
    // resize on any container change so it never renders at zero size.
    const ro = new ResizeObserver(() => map.resize());
    ro.observe(containerRef.current);

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
    // Intentionally once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Theme flip → swap the basemap; the style.load handler re-adds the overlay with fresh colors.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    map.setStyle(resolveBasemap(dark, styleOverride), { diff: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dark]);

  return { containerRef, mapRef };
}
