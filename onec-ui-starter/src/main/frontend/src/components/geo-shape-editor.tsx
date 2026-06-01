import { useState, useCallback, useRef } from "react";
import Map, { Source, Layer, type MapRef, type MapLayerMouseEvent } from "react-map-gl/maplibre";
import { Trash2, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import "maplibre-gl/dist/maplibre-gl.css";

const MAP_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";
const DARK_MAP_STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";

function isDark() {
  return document.documentElement.classList.contains("dark");
}

type GeoJSON = {
  type: "Feature";
  geometry: {
    type: "Polygon" | "LineString";
    coordinates: number[][] | number[][][];
  };
  properties: Record<string, unknown>;
};

function parseGeoJson(value: string): GeoJSON | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value);
    if (parsed.type === "Feature" && parsed.geometry) return parsed;
    if (parsed.type === "Polygon" || parsed.type === "LineString") {
      return { type: "Feature", geometry: parsed, properties: {} };
    }
  } catch {}
  return null;
}

function computeCenter(coords: number[][]): [number, number] {
  if (coords.length === 0) return [2.3522, 48.8566];
  const sum = coords.reduce(
    (acc, c) => [acc[0] + c[0], acc[1] + c[1]],
    [0, 0]
  );
  return [sum[0] / coords.length, sum[1] / coords.length];
}

interface GeoShapeEditorProps {
  value: string;
  onChange: (val: string) => void;
}

export function GeoShapeEditor({ value, onChange }: GeoShapeEditorProps) {
  const feature = parseGeoJson(value);
  const [points, setPoints] = useState<number[][]>(() => {
    if (!feature) return [];
    const geom = feature.geometry;
    if (geom.type === "LineString") return geom.coordinates as number[][];
    if (geom.type === "Polygon") return (geom.coordinates as number[][][])[0]?.slice(0, -1) ?? [];
    return [];
  });
  const [mode, setMode] = useState<"polygon" | "line">("polygon");
  const [showJson, setShowJson] = useState(false);
  const mapRef = useRef<MapRef>(null);

  const emitGeoJson = useCallback(
    (pts: number[][], m: "polygon" | "line") => {
      if (pts.length < 2) {
        onChange("");
        return;
      }
      const geojson: GeoJSON =
        m === "polygon" && pts.length >= 3
          ? {
              type: "Feature",
              geometry: {
                type: "Polygon",
                coordinates: [[...pts, pts[0]]],
              },
              properties: {},
            }
          : {
              type: "Feature",
              geometry: {
                type: "LineString",
                coordinates: pts,
              },
              properties: {},
            };
      onChange(JSON.stringify(geojson));
    },
    [onChange]
  );

  const handleMapClick = useCallback(
    (e: MapLayerMouseEvent) => {
      const newPoints = [...points, [e.lngLat.lng, e.lngLat.lat]];
      setPoints(newPoints);
      emitGeoJson(newPoints, mode);
    },
    [points, mode, emitGeoJson]
  );

  const handleClear = () => {
    setPoints([]);
    onChange("");
  };

  const displayData =
    mode === "polygon" && points.length >= 3
      ? {
          type: "Feature" as const,
          geometry: {
            type: "Polygon" as const,
            coordinates: [[...points, points[0]]],
          },
          properties: {},
        }
      : points.length >= 2
        ? {
            type: "Feature" as const,
            geometry: {
              type: "LineString" as const,
              coordinates: points,
            },
            properties: {},
          }
        : null;

  const center = computeCenter(points);

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <div className="flex rounded-md border border-input overflow-hidden text-[13px]">
          <button
            type="button"
            className={`px-3 py-1.5 transition-colors ${mode === "polygon" ? "bg-accent text-accent-foreground" : "hover:bg-accent/50"}`}
            onClick={() => setMode("polygon")}
          >
            Polygon
          </button>
          <button
            type="button"
            className={`px-3 py-1.5 transition-colors ${mode === "line" ? "bg-accent text-accent-foreground" : "hover:bg-accent/50"}`}
            onClick={() => setMode("line")}
          >
            Line
          </button>
        </div>
        <Button type="button" variant="ghost" size="sm" onClick={handleClear}>
          <Trash2 className="h-3.5 w-3.5 mr-1" />
          Clear
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={() => setShowJson(!showJson)}>
          {showJson ? "Hide" : "Show"} JSON
        </Button>
        <span className="text-xs text-muted-foreground ml-auto">
          {points.length} point{points.length !== 1 ? "s" : ""} — click map to add
        </span>
      </div>
      <div className="rounded-md border overflow-hidden h-[300px]">
        <Map
          ref={mapRef}
          initialViewState={{
            latitude: points.length > 0 ? center[1] : 48.8566,
            longitude: points.length > 0 ? center[0] : 2.3522,
            zoom: points.length > 0 ? 10 : 3,
          }}
          style={{ width: "100%", height: "100%" }}
          mapStyle={isDark() ? DARK_MAP_STYLE : MAP_STYLE}
          onClick={handleMapClick}
          cursor="crosshair"
        >
          {displayData && (
            <Source type="geojson" data={displayData}>
              {displayData.geometry.type === "Polygon" && (
                <Layer
                  id="shape-fill"
                  type="fill"
                  paint={{
                    "fill-color": isDark() ? "#fff" : "#000",
                    "fill-opacity": 0.1,
                  }}
                />
              )}
              <Layer
                id="shape-line"
                type="line"
                paint={{
                  "line-color": isDark() ? "#fff" : "#000",
                  "line-width": 2,
                }}
              />
            </Source>
          )}
          {points.map((pt, i) => (
            <Source
              key={i}
              type="geojson"
              data={{
                type: "Feature",
                geometry: { type: "Point", coordinates: pt },
                properties: {},
              }}
            >
              <Layer
                id={`point-${i}`}
                type="circle"
                paint={{
                  "circle-radius": 5,
                  "circle-color": isDark() ? "#fff" : "#000",
                  "circle-stroke-width": 2,
                  "circle-stroke-color": isDark() ? "#333" : "#fff",
                }}
              />
            </Source>
          ))}
        </Map>
      </div>
      {showJson && value && (
        <Textarea
          readOnly
          rows={4}
          value={JSON.stringify(JSON.parse(value), null, 2)}
          className="font-mono text-xs"
        />
      )}
    </div>
  );
}
