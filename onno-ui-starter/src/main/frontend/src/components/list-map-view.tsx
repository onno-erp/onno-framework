import { useEffect, useMemo, useState } from "react";
import { Loader2 } from "lucide-react";
import type { Feature } from "geojson";
import { featuresFromRow, geoSourceFrom, type GeoSource } from "@/lib/geo";
import { MapView } from "@/components/map-view";
import { useMessages } from "@/providers/messages-provider";
import { cn } from "@/lib/utils";
import type { EntityRecord, UiEvent } from "@/lib/types";

/**
 * The map alternative to the list grid (see {@link EntityListWidget}): fetches the entity's rows and
 * plots the ones with geometry as clustered markers/shapes on a themed MapLibre map. The geo columns
 * come from the list's resolved map config — a point ({@code geoField} or {@code latField}/
 * {@code lngField}) and/or a {@code geoJsonField} (see {@link geoSourceFrom}). Features link back to
 * each record; live data events refetch.
 *
 * <p>Rows are pulled in server-page-sized batches (the list endpoint clamps {@code limit} to 500)
 * up to a hard cap — clustering keeps thousands of markers legible, so the cap is generous. A
 * floating chip over the map reports how many records are placed, and says so when the entity has
 * more rows than the cap.</p>
 *
 * <p>On a route surface ({@code fill}) the map takes the full height the table would have; embedded
 * in a dashboard it keeps a fixed height and flows with the host page.</p>
 */

export type ListMapConfig = {
  geoField?: string;
  latField?: string;
  lngField?: string;
  geoJsonField?: string;
  labelField?: string;
  defaultView?: boolean;
};

/** Most rows the map will pull; the server clamps each page to 500, so this is fetched in batches. */
const CAP = 4000;
const PAGE = 500;

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

/** A secondary popup line: the record's code/number, unless it already serves as the label. */
function sublabelFor(row: EntityRecord, label: string): string | undefined {
  for (const c of [row._code, row._number]) {
    const s = c != null ? String(c).trim() : "";
    if (s && s !== label) return s;
  }
  return undefined;
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
  fill = false,
}: {
  kind: "catalogs" | "documents";
  name: string;
  config: ListMapConfig;
  /** Fixed height when embedded; ignored on a route surface ({@code fill}). */
  height?: number;
  /** Fill the flexed route surface instead of a fixed height. */
  fill?: boolean;
}) {
  const [rows, setRows] = useState<EntityRecord[] | null>(null);
  const [total, setTotal] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);

  const source = useMemo<GeoSource>(() => geoSourceFrom(config), [config]);
  const t = useMessages();

  // Pull up to CAP rows in PAGE-sized batches (the server clamps a single page), then hand the map
  // one complete set — a single setData + fit, no camera jumps while batches stream.
  useEffect(() => {
    let alive = true;
    setRows(null);
    (async () => {
      const all: EntityRecord[] = [];
      let totalRows = 0;
      try {
        while (all.length < CAP) {
          const params = new URLSearchParams({ limit: String(PAGE), offset: String(all.length) });
          const r = await fetch(`/api/list/${kind}/${name}?${params.toString()}`, { credentials: "include" });
          if (!r.ok) throw new Error(`HTTP ${r.status}`);
          const data: { total: number; rows: EntityRecord[] } = await r.json();
          if (!alive) return;
          const batch = data.rows ?? [];
          all.push(...batch);
          totalRows = data.total ?? all.length;
          if (batch.length < PAGE || all.length >= totalRows) break;
        }
      } catch {
        // Fall through with whatever loaded — an empty map beats a spinner that never resolves.
      }
      if (!alive) return;
      setRows(all);
      setTotal(Math.max(totalRows, all.length));
    })();
    return () => {
      alive = false;
    };
  }, [kind, name, reloadKey]);

  useEffect(() => {
    const onData = (e: Event) => {
      if (eventMatches((e as CustomEvent).detail as UiEvent, kind, name)) setReloadKey((k) => k + 1);
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
  }, [kind, name]);

  const features = useMemo<Feature[]>(() => {
    if (!rows) return [];
    const out: Feature[] = [];
    for (const row of rows) {
      const href = row._id ? `onno://${kind}/${name}/${row._id}` : undefined;
      const label = labelFor(row, config.labelField);
      out.push(...featuresFromRow(row, source, { label, sublabel: sublabelFor(row, label), href }));
    }
    return out;
  }, [rows, source, kind, name, config.labelField]);

  if (rows === null) {
    return (
      <div
        className={cn(
          "flex items-center justify-center rounded-card border border-border bg-card text-sm text-muted-foreground",
          fill && "h-full min-h-[320px]"
        )}
        style={fill ? undefined : { height }}
      >
        <Loader2 className="mr-2 size-4 animate-spin" /> {t("loading.generic")}
      </div>
    );
  }

  // A rough record count for the chip (one row can yield a marker + a shape).
  const placed = new Set(features.map((f) => String(f.properties?.href ?? ""))).size;
  const caption =
    features.length === 0
      ? t("map.noRecords")
      : t("map.count", { count: placed }) +
        (total > rows.length ? ` · ${t("map.showingFirst", { shown: rows.length, total })}` : "");

  return (
    <div className={cn("relative", fill && "h-full min-h-[320px]")}>
      <MapView
        features={features}
        height={height}
        fill={fill}
        interactive
        className="h-full w-full overflow-hidden rounded-card border border-border"
      />
      <div className="pointer-events-none absolute bottom-3 left-3 z-10 rounded-full border border-border bg-popover/85 px-3 py-1 text-xs text-muted-foreground shadow-sm backdrop-blur">
        {caption}
      </div>
    </div>
  );
}
