import { useEffect, useState } from "react";
import { format, parseISO } from "date-fns";
import { api } from "@/lib/api";
import { toSnakeCase } from "@/lib/utils";
import { toNumber } from "@/lib/format";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";

/**
 * Shared data plumbing for the dashboard data widgets (chart, stat, sparkline, gauge): one place
 * that fetches a widget's rows and turns them into single-number aggregates or time-bucketed,
 * optionally multi-series datasets. Keeping it here means every widget buckets, sums and orders
 * identically, and the chart-specific React stays about rendering.
 */

// Turnover/balance need a period window; a register-backed widget sums across all of time unless
// the author scopes it, so we ask for an unbounded range.
const ALL_TIME_FROM = "1970-01-01T00:00:00";
const ALL_TIME_TO = "2999-12-31T23:59:59";

export type GroupByDate = "day" | "week" | "month";
export type Metric = "count" | "sum";

/** Fetch the rows backing a widget: a document/catalog list, or a register's server-side turnover. */
export function useWidgetRows(widget: DashboardWidgetMeta): EntityRecord[] {
  const [items, setItems] = useState<EntityRecord[]>([]);
  useEffect(() => {
    let alive = true;
    const name = toSnakeCase(widget.entityName);
    // An authored config("filter", …) (a WidgetFilter predicate) scopes the widget's rows
    // server-side — e.g. a revenue chart that excludes DRAFT/CANCELED bookings.
    const filter = widget.extraConfig?.filter || undefined;
    const set = (rows: EntityRecord[]) => alive && setItems(rows);
    const fail = () => alive && setItems([]);
    if (widget.entityType === "document") api.listDocuments(name, undefined, undefined, filter).then(set).catch(fail);
    else if (widget.entityType === "catalog") api.listCatalog(name, filter).then(set).catch(fail);
    else if (widget.entityType === "register") api.getTurnover(name, ALL_TIME_FROM, ALL_TIME_TO).then(set).catch(fail);
    else fail();
    return () => {
      alive = false;
    };
  }, [widget]);
  return items;
}

export interface AggregateSpec {
  metric: Metric;
  metricField?: string;
}

/** Reduce every row to one number: a count of rows, or the sum of `metricField`. */
export function aggregate(rows: EntityRecord[], spec: AggregateSpec): number {
  if (spec.metric === "count") return rows.length;
  if (!spec.metricField) return 0;
  let sum = 0;
  for (const row of rows) {
    const v = toNumber(row[spec.metricField]);
    if (v != null) sum += v;
  }
  return sum;
}

/** A label for one bucket value — formatted dates for time buckets, else the raw value. */
export function bucketLabel(value: unknown, groupByDate?: GroupByDate): string {
  if (typeof value === "string" && groupByDate) {
    try {
      const d = parseISO(value);
      if (groupByDate === "day") return format(d, "MMM d");
      if (groupByDate === "week") return `Wk ${format(d, "II")}`;
      if (groupByDate === "month") return format(d, "MMM yyyy");
    } catch {
      // fall through
    }
  }
  if (typeof value === "boolean") return value ? "Posted" : "Draft";
  if (value === null || value === undefined || value === "") return "—";
  return String(value);
}

// A sortable key + display label for a bucket. Date buckets get a zero-padded key (yyyy-MM-dd,
// yyyy-MM, ISO week-year) so chronological order is plain string order; everything else keys on
// its own label and keeps insertion order.
function bucketKey(value: unknown, groupByDate?: GroupByDate): { key: string; label: string } {
  if (typeof value === "string" && groupByDate) {
    try {
      const d = parseISO(value);
      if (groupByDate === "day") return { key: format(d, "yyyy-MM-dd"), label: format(d, "MMM d") };
      if (groupByDate === "week") return { key: format(d, "RRRR-'W'II"), label: `Wk ${format(d, "II")}` };
      if (groupByDate === "month") return { key: format(d, "yyyy-MM"), label: format(d, "MMM yyyy") };
    } catch {
      // fall through
    }
  }
  const label = bucketLabel(value, undefined);
  return { key: label, label };
}

export interface SeriesSpec extends AggregateSpec {
  /** Field that defines the x-axis buckets. */
  groupBy: string;
  /** Date bucketing granularity when `groupBy` holds a date. */
  groupByDate?: GroupByDate;
  /** Optional field that splits each bucket into multiple colored series. */
  seriesBy?: string;
  /** Cap on distinct series; the rest fold into an "Other" series. */
  maxSeries?: number;
}

export interface SeriesData {
  /** Wide rows for recharts: `{ label, [seriesKey]: number, ... }`, one per x bucket, x-ordered. */
  rows: Array<Record<string, number | string>>;
  /** Series keys in stable order; `["value"]` (the single-series sentinel) when not split. */
  seriesKeys: string[];
  /** The grand total across every bucket and series. */
  total: number;
}

/** The single-series sentinel key used when `seriesBy` is unset. */
export const SINGLE_SERIES = "value";

/**
 * Bucket rows into an x-ordered, optionally multi-series dataset. With no `seriesBy` it yields one
 * `"value"` series; with `seriesBy` it splits each bucket by that field, ordering series by total
 * (largest first) and folding the long tail beyond `maxSeries` into "Other".
 */
export function buildSeries(rows: EntityRecord[], spec: SeriesSpec): SeriesData {
  const maxSeries = spec.maxSeries ?? 8;
  const buckets = new Map<string, { sortKey: string; label: string; series: Map<string, number> }>();
  const seriesTotals = new Map<string, number>();
  let total = 0;

  for (const row of rows) {
    const { key, label } = bucketKey(row[spec.groupBy], spec.groupByDate);
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = { sortKey: key, label, series: new Map() };
      buckets.set(key, bucket);
    }
    const seriesKey = spec.seriesBy ? bucketLabel(row[spec.seriesBy]) : SINGLE_SERIES;
    const inc =
      spec.metric === "count" ? 1 : spec.metricField ? toNumber(row[spec.metricField]) ?? 0 : 0;
    bucket.series.set(seriesKey, (bucket.series.get(seriesKey) ?? 0) + inc);
    seriesTotals.set(seriesKey, (seriesTotals.get(seriesKey) ?? 0) + inc);
    total += inc;
  }

  let seriesKeys: string[];
  if (!spec.seriesBy) {
    seriesKeys = [SINGLE_SERIES];
  } else {
    const ranked = [...seriesTotals.entries()].sort((a, b) => b[1] - a[1]).map(([k]) => k);
    if (ranked.length > maxSeries) {
      const keep = new Set(ranked.slice(0, maxSeries - 1));
      for (const bucket of buckets.values()) {
        let other = 0;
        for (const [k, v] of bucket.series) {
          if (!keep.has(k)) {
            other += v;
            bucket.series.delete(k);
          }
        }
        if (other) bucket.series.set("Other", (bucket.series.get("Other") ?? 0) + other);
      }
      seriesKeys = [...ranked.slice(0, maxSeries - 1), "Other"];
    } else {
      seriesKeys = ranked;
    }
  }

  const ordered = [...buckets.values()];
  if (spec.groupByDate) {
    ordered.sort((a, b) => (a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0));
  }

  const out = ordered.map((bucket) => {
    const wide: Record<string, number | string> = { label: bucket.label };
    for (const key of seriesKeys) wide[key] = bucket.series.get(key) ?? 0;
    return wide;
  });

  return { rows: out, seriesKeys, total };
}
