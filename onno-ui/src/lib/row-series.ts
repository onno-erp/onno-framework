import { format } from "date-fns";
import { assembleSeries, bucketKey, bucketLabel, SINGLE_SERIES, type BucketAcc, type GroupByDate, type SeriesData } from "./aggregate-data";

/**
 * Chart data a page already holds — rows from its own endpoint, a computed table, anything — instead of
 * an entity measure the server aggregates. One row is one observation; the chart reads its x value,
 * the plotted numbers and, optionally, a key to split by, all by key:
 *
 *   [{ day: "2026-11-01", reach: 1820, chats: 2 }, …]
 */
export type ChartRow = Record<string, unknown>;

/** How rows become one series: what is on the x axis, which number is plotted, and an optional split. */
export interface RowSeriesSpec {
  /** Key of the x value: a category (kept in first-seen order) or a date (ISO string or `Date`, ordered). */
  x: string;
  /** Key of the plotted number; rows with the same x (and split) are added up. */
  value: string;
  /** Key whose values become one series each (largest first, the tail folded into "Other"). */
  splitBy?: string;
  /** Date bucket for a date x; default by span (day up to 120 days, week up to 500, else month). */
  bucket?: GroupByDate;
  /** Most split series before the rest fold into "Other" (default 8). */
  maxSeries?: number;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?$/;

const present = (v: unknown) => v !== null && v !== undefined && v !== "";

/** True when every present x value is a date: an ISO date/date-time string or a `Date`. */
export function rowsAreDated(rows: ChartRow[], x: string): boolean {
  let any = false;
  for (const row of rows) {
    const v = row[x];
    if (!present(v)) continue;
    if (!(v instanceof Date && !Number.isNaN(v.getTime())) && !(typeof v === "string" && ISO_DATE.test(v))) return false;
    any = true;
  }
  return any;
}

const isoOf = (v: unknown) => (v instanceof Date ? format(v, "yyyy-MM-dd'T'HH:mm:ss") : String(v));

/** The bucket a date x defaults to: one bar per day for a few months, then weeks, then months. */
export function rowBucket(rows: ChartRow[], x: string, bucket?: GroupByDate): GroupByDate {
  if (bucket) return bucket;
  let min = Infinity;
  let max = -Infinity;
  for (const row of rows) {
    const v = row[x];
    if (!present(v)) continue;
    const t = v instanceof Date ? v.getTime() : Date.parse(String(v));
    if (Number.isNaN(t)) continue;
    min = Math.min(min, t);
    max = Math.max(max, t);
  }
  const days = max > min ? (max - min) / 86_400_000 : 0;
  return days <= 120 ? "day" : days <= 500 ? "week" : "month";
}

const toNumber = (v: unknown): number | null => {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
};

/**
 * Rows → the wide chart dataset the entity charts use, so tooltips, legends, scales and controls
 * behave the same: one row per x bucket, a key per series. A row with an x but no number still makes
 * its bucket (an empty day reads 0); a row without an x is skipped.
 */
export function seriesFromRows(rows: ChartRow[], spec: RowSeriesSpec): SeriesData {
  const dated = rowsAreDated(rows, spec.x);
  const groupByDate = dated ? rowBucket(rows, spec.x, spec.bucket) : undefined;
  const buckets = new Map<string, BucketAcc>();
  const seriesTotals = new Map<string, number>();
  let total = 0;
  for (const row of rows) {
    const xv = row[spec.x];
    if (!present(xv)) continue;
    const { key, label, iso } = bucketKey(dated ? isoOf(xv) : xv, groupByDate);
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = { sortKey: key, label, iso, series: new Map() };
      buckets.set(key, bucket);
    }
    const value = toNumber(row[spec.value]);
    if (value == null) continue;
    const series = spec.splitBy ? bucketLabel(row[spec.splitBy]) : SINGLE_SERIES;
    bucket.series.set(series, (bucket.series.get(series) ?? 0) + value);
    seriesTotals.set(series, (seriesTotals.get(series) ?? 0) + value);
    total += value;
  }
  return assembleSeries(buckets, seriesTotals, total, !!spec.splitBy, Math.max(1, spec.maxSeries ?? 8), groupByDate);
}
