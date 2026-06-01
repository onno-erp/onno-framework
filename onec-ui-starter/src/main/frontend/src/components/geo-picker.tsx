import { useState, useCallback, useRef } from "react";
import Map, { Marker, type MapRef, type MapLayerMouseEvent } from "react-map-gl/maplibre";
import { MapPin } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import "maplibre-gl/dist/maplibre-gl.css";

const MAP_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";
const DARK_MAP_STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";

function isDark() {
  return document.documentElement.classList.contains("dark");
}

function parseCoords(value: string): { lat: number; lng: number } | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value);
    if (parsed.lat != null && parsed.lng != null) return parsed;
  } catch {
    const parts = value.split(",").map((s) => parseFloat(s.trim()));
    if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
      return { lat: parts[0], lng: parts[1] };
    }
  }
  return null;
}

interface GeoPickerProps {
  value: string;
  onChange: (val: string) => void;
}

export function GeoPicker({ value, onChange }: GeoPickerProps) {
  const coords = parseCoords(value);
  const [lat, setLat] = useState(coords?.lat?.toString() ?? "");
  const [lng, setLng] = useState(coords?.lng?.toString() ?? "");
  const mapRef = useRef<MapRef>(null);

  const emit = useCallback(
    (newLat: number, newLng: number) => {
      onChange(JSON.stringify({ lat: newLat, lng: newLng }));
      setLat(newLat.toFixed(6));
      setLng(newLng.toFixed(6));
    },
    [onChange]
  );

  const handleMapClick = useCallback(
    (e: MapLayerMouseEvent) => {
      emit(e.lngLat.lat, e.lngLat.lng);
    },
    [emit]
  );

  const handleCoordsBlur = () => {
    const la = parseFloat(lat);
    const ln = parseFloat(lng);
    if (!isNaN(la) && !isNaN(ln)) {
      emit(la, ln);
      mapRef.current?.flyTo({ center: [ln, la], zoom: 12 });
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <Input
          placeholder="Latitude"
          value={lat}
          onChange={(e) => setLat(e.target.value)}
          onBlur={handleCoordsBlur}
          className="flex-1"
        />
        <Input
          placeholder="Longitude"
          value={lng}
          onChange={(e) => setLng(e.target.value)}
          onBlur={handleCoordsBlur}
          className="flex-1"
        />
      </div>
      <div className="rounded-md border overflow-hidden h-[240px]">
        <Map
          ref={mapRef}
          initialViewState={{
            latitude: coords?.lat ?? 48.8566,
            longitude: coords?.lng ?? 2.3522,
            zoom: coords ? 12 : 3,
          }}
          style={{ width: "100%", height: "100%" }}
          mapStyle={isDark() ? DARK_MAP_STYLE : MAP_STYLE}
          onClick={handleMapClick}
          cursor="crosshair"
        >
          {coords && (
            <Marker latitude={coords.lat} longitude={coords.lng} anchor="bottom">
              <MapPin className="h-6 w-6 text-destructive fill-destructive/20" />
            </Marker>
          )}
        </Map>
      </div>
      {coords && (
        <p className="text-xs text-muted-foreground">
          {coords.lat.toFixed(6)}, {coords.lng.toFixed(6)}
        </p>
      )}
    </div>
  );
}
