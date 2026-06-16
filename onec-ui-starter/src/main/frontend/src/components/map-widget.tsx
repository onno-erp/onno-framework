import { useMemo } from "react";
import type { Feature } from "geojson";
import { useWidgetRows } from "@/lib/widget-data";
import { featuresFromRow, geoSourceFrom, hasGeoSource } from "@/lib/geo";
import { toSnakeCase } from "@/lib/utils";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { MapView } from "@/components/map-view";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";

/**
 * The {@code map} dashboard widget: plots an entity's records on a themed MapLibre map. Each record
 * contributes a marker from its point field ({@code geoField} or {@code latField}/{@code lngField})
 * and/or a shape (points/paths/areas) from a {@code geoJsonField} (see {@link geoSourceFrom}); rows
 * with no geometry are skipped. A feature's popup shows the {@code titleField} and links to the record.
 *
 * <pre>
 *   b.widget("Stores").type("map").width("full").catalog(Store.class)
 *     .config("geoField", "location")        // marker point ("lat,lng")
 *     .config("geoJsonField", "service_area") // optional shape (GeoJSON)
 *     .titleField("name");
 * </pre>
 */

const MAP_HEIGHT = 360;

/** A record's marker/shape label: the configured title field, else a system identifier. */
function labelFor(row: EntityRecord, titleField: string): string {
  const candidates = [
    titleField && (row[`${titleField}_display`] ?? row[titleField]),
    row._description,
    row._number,
    row._code,
    row.name,
  ];
  for (const c of candidates) {
    if (c != null && String(c).trim() !== "") return String(c);
  }
  return "";
}

export function MapWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const rows = useWidgetRows(widget);
  const source = useMemo(() => geoSourceFrom(widget.extraConfig), [widget.extraConfig]);

  const features = useMemo<Feature[]>(() => {
    if (!hasGeoSource(source)) return [];
    const route = toSnakeCase(widget.entityName);
    const out: Feature[] = [];
    for (const row of rows) {
      const href =
        widget.entityType && row._id ? `onec://${widget.entityType}s/${route}/${row._id}` : undefined;
      const props = { label: labelFor(row, widget.titleField), href };
      out.push(...featuresFromRow(row, source, props));
    }
    return out;
  }, [rows, source, widget.entityName, widget.entityType, widget.titleField]);

  const misconfigured = !hasGeoSource(source);

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-center justify-between space-y-0 p-4 pb-2">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
          <HintIcon text={widget.hint} size={13} />
        </div>
        {!misconfigured && features.length > 0 ? (
          <span className="text-[13px] font-semibold tabular-nums text-foreground">{features.length}</span>
        ) : null}
      </CardHeader>
      <CardContent className="p-4 pt-0">
        {misconfigured ? (
          <div className="flex h-[210px] items-center justify-center px-4 text-center text-xs text-muted-foreground">
            Set a geo source: <code className="mx-1 font-mono">.config("geoField", "…")</code>, a
            <code className="mx-1 font-mono">latField</code>/<code className="font-mono">lngField</code> pair, or a
            <code className="ml-1 font-mono">geoJsonField</code>.
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
