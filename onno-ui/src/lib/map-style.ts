import { useEffect, useState } from "react";
import type { StyleSpecification } from "maplibre-gl";

/**
 * Theme glue for the MapLibre surfaces: a minimal monochrome basemap that flips light/dark with the
 * app theme, and a reader that resolves the app's CSS theme tokens to concrete colors the map's
 * paint properties (and our overlay layers) can use. Keeping it here means every map — widget, list,
 * detail, editor — is tinted from the same palette and reacts to a theme toggle identically.
 */

// A keyless, minimal monochrome raster basemap (CARTO light/dark). Vector tiles aren't required for
// a clean monochrome look, and a raster source has no schema to track. Apps that want a fully
// recolored vector basemap (or an offline self-hosted Protomaps style) can pass `styleOverride` to
// the map components instead — both accept a style URL or a full MapLibre style object.
const SUBDOMAINS = ["a", "b", "c"];
const ATTRIBUTION =
  '<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap</a>, ' +
  '<a href="https://carto.com/attributions" target="_blank" rel="noopener">© CARTO</a>';

/** The built-in monochrome basemap style for the given mode. */
export function basemapStyle(dark: boolean): StyleSpecification {
  const variant = dark ? "dark_all" : "light_all";
  return {
    version: 8,
    sources: {
      basemap: {
        type: "raster",
        // @2x (512px) tiles at tileSize 256 → rendered at 2× density, so the basemap stays crisp on
        // hi-dpi/retina screens instead of the soft look of plain 256px raster.
        tiles: SUBDOMAINS.map((s) => `https://${s}.basemaps.cartocdn.com/${variant}/{z}/{x}/{y}@2x.png`),
        tileSize: 256,
        attribution: ATTRIBUTION,
      },
    },
    layers: [{ id: "basemap", type: "raster", source: "basemap" }],
  };
}

/** Resolve a basemap style input to a value MapLibre accepts (a URL string or a style object). */
export function resolveBasemap(
  dark: boolean,
  override?: string | StyleSpecification
): string | StyleSpecification {
  return override ?? basemapStyle(dark);
}

/** Whether the app is currently in dark mode (the theme provider toggles `dark` on <html>). */
export function isDarkNow(): boolean {
  return typeof document !== "undefined" && document.documentElement.classList.contains("dark");
}

/** Re-render when the app theme flips light/dark (observes the <html> class the theme provider sets). */
export function useIsDark(): boolean {
  const [dark, setDark] = useState(isDarkNow);
  useEffect(() => {
    const root = document.documentElement;
    const obs = new MutationObserver(() => setDark(root.classList.contains("dark")));
    obs.observe(root, { attributes: true, attributeFilter: ["class"] });
    setDark(root.classList.contains("dark"));
    return () => obs.disconnect();
  }, []);
  return dark;
}

// A single hidden probe to resolve any CSS color expression (including `hsl(var(--token))`, whose
// space-separated channels MapLibre's color parser can't read directly) to a concrete rgb string.
let probe: HTMLDivElement | null = null;
function resolveCssColor(expr: string, fallback: string): string {
  if (typeof document === "undefined") return fallback;
  if (!probe) {
    probe = document.createElement("div");
    probe.style.display = "none";
    document.body.appendChild(probe);
  }
  probe.style.color = "";
  probe.style.color = expr;
  const resolved = getComputedStyle(probe).color;
  return resolved || fallback;
}

/** Resolve a theme token (`--primary`, `--foreground`, …) to a concrete rgb color. */
function token(name: string, fallback: string): string {
  return resolveCssColor(`hsl(var(${name}))`, fallback);
}

export interface MapTheme {
  /** Brand accent — fills/strokes for data geometry and draw handles. */
  primary: string;
  /** Readable-on-primary — marker glyphs, handle centers. */
  onPrimary: string;
  /** Default text color — popups. */
  text: string;
  /** Muted lines/labels. */
  muted: string;
  /** Hairline borders. */
  border: string;
  /** Card/surface background — popup background, vertex fill. */
  surface: string;
}

/** The current theme's colors, resolved to concrete rgb. Recompute when {@code dark} changes. */
export function readMapTheme(): MapTheme {
  return {
    primary: token("--primary", "rgb(37, 99, 235)"),
    onPrimary: token("--primary-foreground", "rgb(255, 255, 255)"),
    text: token("--foreground", "rgb(17, 24, 39)"),
    muted: token("--muted-foreground", "rgb(107, 114, 128)"),
    border: token("--border", "rgb(229, 231, 235)"),
    surface: token("--card", "rgb(255, 255, 255)"),
  };
}
