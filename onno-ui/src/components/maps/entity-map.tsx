import { useMemo, useRef } from "react";
import type { Feature } from "geojson";
import { useEntityRows } from "../../lib/aggregate-data";
import { useEntitySource } from "../../lib/entity-source";
import { featuresFromRow, hasGeoSource, type GeoSource } from "../../lib/geo";
import type { WhereClause } from "../../lib/measure";
import { toSnakeCase } from "../../lib/utils";
import type { EntityRecord } from "../../lib/types";
import { Boundary } from "../pages/boundary";
import { MapView } from "./map-view";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";

/**
 * Plots an entity's records on a themed MapLibre map. Each record contributes a marker from
 * `lat`/`lng` and/or geometry from a `geoJson` field; rows with no geometry are skipped. A
 * feature's popup shows `label(record)` and links to the record.
 *
 * ```tsx
 * <EntityMap title="Stores" entity="Stores" geoJson="serviceArea" label={(s) => s.name} />
 * ```
 */
export interface EntityMapProps {
  /** Card title. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** The entity whose records are plotted, by logical name (`"Stores"`). */
  entity?: string;
  /** Which records, e.g. `{ active: true }`. */
  where?: WhereClause;
  /** At most this many records are plotted. */
  limit?: number;
  /** The latitude field of a marker (paired with `lng`). */
  lat?: string;
  /** The longitude field of a marker (paired with `lat`). */
  lng?: string;
  /** A field holding GeoJSON geometry (point, path or area). */
  geoJson?: string;
  /** Each marker/shape's label; default the record's description/number/code/name. */
  label?: (record: EntityRecord) => string | null | undefined;
}

const MAP_HEIGHT = 360;

/** The default marker/shape label: a system identifier. */
export function defaultMapLabel(row: EntityRecord): string {
  for (const c of [row.description, row.number, row.code, row.name]) {
    if (c != null && String(c).trim() !== "") return String(c);
  }
  return "";
}

export function EntityMap(props: EntityMapProps) {
  return (
    <Boundary label={props.title || "Map"}>
      <EntityMapCard {...props} />
    </Boundary>
  );
}

function EntityMapCard({ title, hint, entity, where, limit, lat, lng, geoJson, label }: EntityMapProps) {
  const entitySource = useEntitySource(entity, { where });
  const rows = useEntityRows(entitySource);
  const source = useMemo<GeoSource>(
    () => ({ latField: lat || undefined, lngField: lng || undefined, geoJsonField: geoJson || undefined }),
    [lat, lng, geoJson]
  );

  // Read through a ref: an inline `label` arrow is new on every parent render, and new features
  // would re-frame the map each time.
  const labelRef = useRef(label);
  labelRef.current = label;

  const features = useMemo<Feature[]>(() => {
    if (!hasGeoSource(source) || !entitySource) return [];
    const route = toSnakeCase(entitySource.entityName);
    const labelOf = labelRef.current ?? defaultMapLabel;
    const out: Feature[] = [];
    for (const row of limit ? rows.slice(0, limit) : rows) {
      const href = row.id ? `onno://${entitySource.entityType}s/${route}/${row.id}` : undefined;
      const props = { label: labelOf(row) ?? "", href };
      out.push(...featuresFromRow(row, source, props));
    }
    return out;
  }, [rows, source, entitySource, limit]);

  const misconfigured = !hasGeoSource(source);

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-center justify-between space-y-0 p-4 pb-2">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{title}</CardTitle>
          <HintIcon text={hint} size={13} />
        </div>
        {!misconfigured && features.length > 0 ? (
          <span className="text-[13px] font-semibold tabular-nums text-foreground">{features.length}</span>
        ) : null}
      </CardHeader>
      <CardContent className="p-4 pt-0">
        {misconfigured ? (
          <div className="flex h-[210px] items-center justify-center px-4 text-center text-xs text-muted-foreground">
            Set a geo source: a <code className="mx-1 font-mono">lat</code>/
            <code className="font-mono">lng</code> pair or a
            <code className="ml-1 font-mono">geoJson</code> field.
          </div>
        ) : features.length === 0 ? (
          <div className="flex h-[210px] items-center justify-center text-xs text-muted-foreground">
            No locations yet
          </div>
        ) : (
          <MapView features={features} height={MAP_HEIGHT} interactive />
        )}
      </CardContent>
    </Card>
  );
}
