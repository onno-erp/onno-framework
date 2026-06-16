import { useEffect, useMemo, useState } from "react";
import { Loader2 } from "lucide-react";
import type { Feature } from "geojson";
import { featuresFromRow, geoSourceFrom, type GeoSource } from "@/lib/geo";
import { MapView } from "@/components/map-view";
import type { EntityRecord, UiEvent } from "@/lib/types";

/**
 * The map alternative to the list grid (see {@link EntityListWidget}): fetches the entity's rows and
 * plots the ones with geometry as markers/shapes on a themed MapLibre map. The geo columns come from
 * the list's resolved map config — a point ({@code geoField} or {@code latField}/{@code lngField})
 * and/or a {@code geoJsonField} (see {@link geoSourceFrom}). Features link back to each record; live
 * data events refetch.
 *
 * <p>A map can't virtualize, so it caps how many rows it pulls and says so when the entity has more.</p>
 */

export type ListMapConfig = {
  geoField?: string;
  latField?: string;
  lngField?: string;
  geoJsonField?: string;
  labelField?: string;
  defaultView?: boolean;
};

const CAP = 1000;

/** A record's feature label: the configured label column, else a system identifier. */
function labelFor(row: EntityRecord, labelField?: string): string {
  const candidates = [
    labelField && (row[`${labelField}_display`] ?? row[labelField]),
    row._description,
    row._number,
    row._code,
  ];
  for (const c of candidates) {
    if (c != null && String(c).trim() !== "") return String(c);
  }
  return "";
}

function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}
function eventMatches(event: UiEvent, kind: string, name: string): boolean {
  if (!event || event.type === "ready") return false;
  const singular = kind === "catalogs" ? "catalog" : "document";
  return event.entityType === singular && (event.entityName === "*" || toSnake(event.entityName ?? "") === name);
}

export function ListMapView({
  kind,
  name,
  config,
  height = 540,
}: {
  kind: "catalogs" | "documents";
  name: string;
  config: ListMapConfig;
  height?: number;
}) {
  const [rows, setRows] = useState<EntityRecord[] | null>(null);
  const [total, setTotal] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);

  const source = useMemo<GeoSource>(() => geoSourceFrom(config), [config]);

  useEffect(() => {
    let alive = true;
    setRows(null);
    const params = new URLSearchParams({ limit: String(CAP), offset: "0" });
    fetch(`/api/list/${kind}/${name}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { total: number; rows: EntityRecord[] }) => {
        if (!alive) return;
        setRows(data.rows ?? []);
        setTotal(data.total ?? data.rows?.length ?? 0);
      })
      .catch(() => alive && setRows([]));
    return () => {
      alive = false;
    };
  }, [kind, name, reloadKey]);

  useEffect(() => {
    const onData = (e: Event) => {
      if (eventMatches((e as CustomEvent).detail as UiEvent, kind, name)) setReloadKey((k) => k + 1);
    };
    window.addEventListener("onec:dataevent", onData);
    return () => window.removeEventListener("onec:dataevent", onData);
  }, [kind, name]);

  const features = useMemo<Feature[]>(() => {
    if (!rows) return [];
    const out: Feature[] = [];
    for (const row of rows) {
      const href = row._id ? `onec://${kind}/${name}/${row._id}` : undefined;
      out.push(...featuresFromRow(row, source, { label: labelFor(row, config.labelField), href }));
    }
    return out;
  }, [rows, source, kind, name, config.labelField]);

  if (rows === null) {
    return (
      <div
        className="flex items-center justify-center rounded-2xl border border-border bg-card text-sm text-muted-foreground"
        style={{ height }}
      >
        <Loader2 className="mr-2 size-4 animate-spin" /> Loading map…
      </div>
    );
  }

  // A rough record count for the caption (one row can yield a marker + a shape).
  const placed = new Set(features.map((f) => String(f.properties?.href ?? ""))).size;

  return (
    <div className="grid gap-1.5">
      <MapView
        features={features}
        height={height}
        interactive
        className="w-full overflow-hidden rounded-2xl border border-border"
      />
      <p className="text-xs text-muted-foreground">
        {features.length === 0
          ? "No records with a location."
          : `${placed} ${placed === 1 ? "record" : "records"} on the map`}
        {total > rows.length ? ` · showing first ${rows.length} of ${total} rows` : null}
      </p>
    </div>
  );
}
