import {
  cloneElement,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
} from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  LabelList,
  Line,
  LineChart,
  Pie,
  PieChart,
  ReferenceArea,
  ReferenceLine,
  Tooltip,
  XAxis,
  YAxis,
  type MouseHandlerDataParam,
} from "recharts";
import { useQueries } from "@tanstack/react-query";
import { format } from "date-fns";
import { useLocation } from "react-router";
import { formatCompact, formatNumber, toNumber } from "../../lib/format";
import { resolveColors } from "../../lib/chart-colors";
import { Segmented } from "../ui/segmented";
import { AnimatedNumber } from "../ui/animated-number";
import {
  applyScale,
  ISO_KEY,
  SCALE_LABELS,
  seriesFromBuckets,
  SINGLE_SERIES,
  type AggregateBuckets,
  type GroupByDate,
  type Metric,
  type ScaleMode,
  type SeriesData,
} from "../../lib/aggregate-data";
import { aggregateParams, type GroupSpec, type MeasureSpec } from "../../lib/measure";
import { api } from "../../lib/api";
import { queryClient, queryKeys } from "../../lib/query";
import { findEntity, useModel } from "../../lib/model-store";
import { autoGranularityForSpan, presetsFromConfig, resolveRange } from "../../lib/time-range";
import { useTimeRange } from "../../providers/time-range-provider";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { TimeRangeFacet } from "../lists/date-range-facet";
import { Boundary } from "../pages/boundary";
import { useMessages } from "../../providers/messages-provider";
import { logicalEntityKey } from "../../lib/entity-keys";
import { seriesFromRows, type ChartRow } from "../../lib/row-series";

/**
 * The compact figure beside a chart title must be a valid summary of the rendered data. Count and
 * sum are additive across buckets; avg/min/max are not. A non-additive measure remains meaningful
 * only when the response is one unsplit aggregate rather than a grouped series.
 */
export function chartHeadlineValue(aggregation: string, data: SeriesData): number | null {
  const additive = aggregation === "count" || aggregation === "sum";
  const singleAggregate = data.rows.length === 1 && data.seriesKeys.length === 1;
  return additive || singleAggregate ? data.total : null;
}

// "pie" is a donut with a zero inner radius (a filled circle).
export type ChartType = "bar" | "line" | "area" | "donut" | "pie";
const CHART_TYPES: ChartType[] = ["bar", "line", "area", "donut", "pie"];

/** A stroke style for lines, areas and threshold lines. */
export type ChartLineStyle = "solid" | "dashed" | "dotted";

/** A horizontal reference line. */
export interface ChartThreshold {
  /** The y value the line is drawn at. */
  value: number;
  /** Text drawn at the line's right end. */
  label?: string;
  /** A theme token (`"success"`, `"chart-2"`) or CSS color (default: destructive). */
  color?: string;
  /** Default `"dashed"`. */
  style?: ChartLineStyle;
  /** Which axis the value is on; `"right"` applies only when a series is on the right axis (default `"left"`). */
  axis?: "left" | "right";
  /** Stroke width, 1–12 (default 2). */
  width?: number;
}

/** One option of the `"series"` control. */
export interface ChartSeriesOption {
  /** The field to split by; `""` (or `"none"`) is the single unsplit series. */
  value: string;
  label: string;
}

/** One y axis. */
export interface ChartAxis {
  /** Lower bound (default auto). */
  min?: number;
  /** Upper bound (default auto). */
  max?: number;
  /** Axis title. */
  label?: string;
  /** Default `"linear"`. */
  scale?: "linear" | "log";
  /** Show the axis (default true). */
  visible?: boolean;
}

/**
 * One measured series of a multi-series chart. Styling props absent here fall back to the chart's
 * own (`type`, `lineStyle`, `strokeWidth`, `opacity`, `currency`, `unit`, `format`).
 */
export interface ChartSeries {
  /** What the series plots: `sum("Orders", "total", { posted: true })`. */
  measure?: MeasureSpec;
  /** With `data`: the key of the number this series plots in each row (instead of `measure`). */
  value?: string;
  /** Mark type (default: the chart's `type`, else `"bar"`); a pie/donut type plots as bars here. */
  type?: ChartType;
  /** Legend/tooltip name (default: the chart title for the first series, else the measured field). */
  label?: string;
  /** A theme token or CSS color (default: the palette, in series order). */
  color?: string;
  /** Which y axis the series is plotted on (default `"left"`). */
  axis?: "left" | "right";
  lineStyle?: ChartLineStyle;
  /** Line/area stroke width, 1–12 (default 2). */
  strokeWidth?: number;
  /** 0–1 (default 1). */
  opacity?: number;
  currency?: string;
  unit?: string;
  format?: "integer" | "decimal";
}

/**
 * A chart of one or more measures grouped by a field or a date bucket, following the page's shared
 * time range:
 *
 *   <Chart title="Revenue by day" type="area" measure={sum("Orders", "total", { posted: true })} by={{ date: "day" }} />
 *   <Chart title="Orders by status" type="pie" measure={count("Orders")} by="status" />
 *
 * Or of rows the page already holds (`data`), plotted as they are — no entity, no request, no
 * shared time range; every other prop works the same:
 *
 *   <Chart title="Reach" data={days} x="day" value="reach" />
 *   <Chart title="Leads by source" type="donut" data={sources} x="source" value="leads" />
 *   <Chart title="Reach and chats" data={days} x="day" series={[{ value: "reach" }, { value: "chats", type: "line", axis: "right" }]} />
 */
export interface ChartProps {
  /**
   * Rows to plot instead of measuring an entity. `x` names the x value (a category, or a date as an
   * ISO string or `Date`, bucketed by `bucket`); `value` (or each series' `value`) the number; rows
   * with the same x are added up. `splitBy` names a key to split by.
   */
  data?: ChartRow[];
  /** With `data`: the key of the x value (default `"label"`). */
  x?: string;
  /** With `data`: the key of the single series' number (default `"value"`). */
  value?: string;
  /** With `data` and a date x: the bucket (default by span: day up to 120 days, week up to 500, else month). */
  bucket?: GroupByDate;
  /**
   * With `data`: the figure in the header — the sum of the plotted values (default), the last
   * bucket's (`"last"`, for levels such as a follower count), or none.
   */
  headline?: "total" | "last" | "none";
  /** Card title; also the single series' name unless `label` is set. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** The one-series shorthand: what to plot. Ignored when `series` is set. */
  measure?: MeasureSpec;
  /** Several measures on one chart (one or two y axes); takes precedence over `measure`. */
  series?: ChartSeries[];
  /** Chart type (default `"bar"`); an unknown type warns and falls back to bar. Pie/donut need a single series. */
  type?: ChartType | (string & {});
  /**
   * The x axis: a field (`"status"` — reference/enum buckets show their labels) or a date field in
   * buckets (`{ date: "week" }`). Default: the measure's date field (`"date"`, `"_period"` for
   * registers) by day. A date grouping also windows by that field unless the measure names its own.
   */
  by?: GroupSpec;
  /** `"fixed"` keeps the bucket of a date `by`; `"auto"` (default) follows the shared range. */
  bucketMode?: "fixed" | "auto";
  /** Name of the single series (default: the title). */
  label?: string;
  /** Color of the single series — a theme token or CSS color. */
  color?: string;
  /** Palette override: theme tokens / CSS colors, in series order. */
  colors?: string[];
  /** Split the single measure into one series per value of this field. */
  splitBy?: string;
  /** Color per split value, e.g. `{ Wholesale: "#7c3aed" }`. */
  seriesColors?: Record<string, string>;
  /** Most split series shown before the rest fold into "Other" (default 8). */
  maxSeries?: number;
  /** Stack split series (bar/area). */
  stacked?: boolean;
  /** Line/area stroke width, 1–12 (default 2). */
  strokeWidth?: number;
  /** Line/area stroke style (default `"solid"`). */
  lineStyle?: ChartLineStyle;
  /** Mark opacity, 0–1 (default 1). */
  opacity?: number;
  /** ISO 4217 code, e.g. `"USD"`. */
  currency?: string;
  /** A free unit label instead of a currency (`"kg"`, `"pcs"`). */
  unit?: string;
  unitPosition?: "prefix" | "suffix";
  /** Number format (default `"integer"` for counts). */
  format?: "integer" | "decimal";
  /** BCP 47 locale for number formatting. */
  locale?: string;
  /** The y axes; `right` is used by series with `axis: "right"`. */
  axes?: { left?: ChartAxis; right?: ChartAxis };
  /** Horizontal reference lines. */
  thresholds?: ChartThreshold[];
  /** Legend placement (default `"bottom"`); `"hidden"`/`"none"` drops it. */
  legend?: "top" | "right" | "bottom" | "left" | "hidden" | "none";
  /** Line/area interpolation (default `"monotone"`, also `"smooth"`). */
  curve?: "monotone" | "smooth" | "linear" | "step";
  /** Value labels on the marks (default `"hidden"`; `"auto"` only when few points). */
  dataLabels?: "hidden" | "auto" | "all";
  /** Dots on line/area points. */
  points?: boolean;
  /** Background grid (default true). */
  grid?: boolean;
  /** Plot height in px, 120–800 (default 210; 230 for a pie/donut). */
  height?: number;
  /** Max bar width in px, 2–160 (default 48). */
  barSize?: number;
  /** Donut inner radius as a percent, 0–90 (default 63). */
  donutHole?: number;
  /** Per-chart controls in the header (single-series charts); `"all"` = type + scale. */
  controls?: ("type" | "scale" | "series" | "all")[];
  /** Options of the `"series"` control (values are `splitBy` fields). */
  seriesOptions?: ChartSeriesOption[];
}

/** One series with every default applied. `key` is its data key in the chart rows. */
export interface SeriesConfig {
  key: string;
  /** What an entity series measures; absent for a `data` series. */
  measure?: MeasureSpec;
  /** The row key a `data` series plots. */
  value?: string;
  type: ChartType;
  label: string;
  color?: string;
  axis: "left" | "right";
  lineStyle: ChartLineStyle;
  strokeWidth: number;
  opacity: number;
  currency?: string;
  unit?: string;
  format?: string;
}

export interface ChartConfig {
  /** The plotted measures; one for the single-series (splittable, pie-capable) chart. */
  series: SeriesConfig[];
  /** True with two or more series: every series is its own measure on the left or right axis. */
  multi: boolean;
  /** The first series' type — the single-series chart's type. */
  type: ChartType;
  /** The grouping field as authored; undefined groups each series by its own date field. */
  groupBy?: string;
  /** The date bucket of a date grouping (seed for the automatic bucket). */
  groupByDate?: GroupByDate;
  splitBy?: string;
  stacked: boolean;
  colors?: string;
  seriesColors: Record<string, string>;
  unitPosition?: string;
  locale?: string;
  maxSeries: number;
  bucketMode: "auto" | "fixed";
  height: number;
  barSize: number;
  donutHole: number;
  grid: boolean;
  points: boolean;
  curve: "monotone" | "linear" | "step";
  dataLabels: "hidden" | "auto" | "all";
  legend: "top" | "right" | "bottom" | "left" | "hidden";
  axes: { left: AxisConfig; right: AxisConfig };
  thresholds: ThresholdConfig[];
}

interface AxisConfig {
  min?: number;
  max?: number;
  label?: string;
  scale: "linear" | "log";
  visible: boolean;
}

interface ThresholdConfig {
  value: number;
  label?: string;
  color?: string;
  axis: "left" | "right";
  style: ChartLineStyle;
  width: number;
}

const finite = (value: number | undefined) => (value != null && Number.isFinite(value) ? value : undefined);
const clamp = (value: number | undefined, fallback: number, min: number, max: number) =>
  Math.min(max, Math.max(min, finite(value) ?? fallback));

const lineStyle = (value: string | undefined): ChartLineStyle =>
  value === "dashed" || value === "dotted" ? value : "solid";

const dashArray = (style: ChartLineStyle) =>
  style === "solid" ? undefined : style === "dotted" ? "2 4" : "6 4";

const LEGENDS = ["top", "right", "bottom", "left", "hidden"];

const DATE_FIELDS = new Set(["date", "_period"]);

function chartType(value: string | undefined, title: string | undefined): ChartType {
  const explicit = value ?? "bar";
  if (CHART_TYPES.includes(explicit as ChartType)) return explicit as ChartType;
  // Don't silently coerce an unknown type — surface it, then fall back.
  console.warn(`[onno chart] unknown type "${explicit}" for "${title}"; falling back to "bar"`);
  return "bar";
}

/** True when every number under `key` in the rows is whole (ignoring missing ones). */
function wholeNumbers(rows: ChartRow[], key: string): boolean {
  return rows.every((r) => {
    const v = r[key];
    return v == null || v === "" || Number.isInteger(typeof v === "number" ? v : Number(v));
  });
}

/** The resolved chart configuration for typed props, with every default applied. */
export function chartConfig(props: ChartProps): ChartConfig {
  const rows = Array.isArray(props.data);
  const authored: ChartSeries[] = (props.series?.length
    ? props.series
    : rows
      ? [{ value: props.value ?? "value", label: props.label, color: props.color }]
      : props.measure
        ? [{ measure: props.measure, label: props.label, color: props.color }]
        : []
  // A data chart plots row keys, an entity chart measures: a series without its kind's source is dropped.
  ).filter((s) => (rows ? !!(s.value ?? (props.series?.length ? undefined : "value")) : !!s.measure));
  const multi = authored.length > 1;
  const series = authored.map((s, i): SeriesConfig => {
    let type = chartType(s.type ?? props.type, props.title);
    // A pie/donut plots one measure; beside others it has no axis to share, so it plots as bars.
    if (multi && (type === "pie" || type === "donut")) type = "bar";
    const field = s.measure?.field ?? s.value;
    return {
      key: multi ? `s${i}` : SINGLE_SERIES,
      measure: rows ? undefined : s.measure,
      value: rows ? s.value ?? props.value ?? "value" : undefined,
      type,
      label: s.label ?? (i === 0 ? props.title : undefined) ?? field ?? s.measure?.entity ?? "",
      color: s.color,
      axis: s.axis === "right" ? "right" : "left",
      lineStyle: lineStyle(s.lineStyle ?? props.lineStyle),
      strokeWidth: clamp(s.strokeWidth ?? props.strokeWidth, 2, 1, 12),
      opacity: clamp(s.opacity ?? props.opacity, 1, 0, 1),
      currency: s.currency ?? props.currency,
      unit: s.unit ?? props.unit,
      // Counts read better as integers; so do data series whose numbers are all whole.
      format: s.format ?? props.format ?? (s.measure?.aggregation === "count" || (rows && wholeNumbers(props.data!, s.value ?? props.value ?? "value")) ? "integer" : undefined),
    };
  });

  let groupBy: string | undefined;
  let groupByDate: GroupByDate | undefined;
  if (typeof props.by === "string") {
    groupBy = props.by;
    // The system date fields group by day unless bucketed explicitly.
    if (DATE_FIELDS.has(props.by)) groupByDate = "day";
  } else if (props.by) {
    const [field, bucket] = Object.entries(props.by)[0] ?? [];
    groupBy = field;
    groupByDate = field ? (bucket as GroupByDate) : undefined;
  }
  if (!groupBy) groupByDate = "day";

  const legend = props.legend === "none"
    ? "hidden"
    : (LEGENDS.includes(props.legend ?? "") ? props.legend : "bottom") as ChartConfig["legend"];
  const axis = (a: ChartAxis | undefined): AxisConfig => ({
    min: finite(a?.min),
    max: finite(a?.max),
    label: a?.label,
    scale: a?.scale === "log" ? "log" : "linear",
    visible: a?.visible !== false,
  });
  return {
    series,
    multi,
    type: series[0]?.type ?? chartType(props.type, props.title),
    groupBy,
    groupByDate,
    splitBy: props.splitBy,
    stacked: props.stacked === true,
    colors: props.colors?.length ? props.colors.join(",") : undefined,
    seriesColors: { ...props.seriesColors },
    unitPosition: props.unitPosition,
    locale: props.locale,
    maxSeries: Math.max(1, Math.floor(finite(props.maxSeries) ?? 8)),
    bucketMode: props.bucketMode === "fixed" ? "fixed" : "auto",
    height: Math.floor(clamp(props.height, 210, 120, 800)),
    barSize: Math.floor(clamp(props.barSize, 48, 2, 160)),
    donutHole: clamp(props.donutHole, 63, 0, 90),
    grid: props.grid !== false,
    points: props.points === true,
    curve: props.curve === "linear" ? "linear" : props.curve === "step" ? "step" : "monotone",
    dataLabels: props.dataLabels === "all" || props.dataLabels === "auto" ? props.dataLabels : "hidden",
    legend,
    axes: { left: axis(props.axes?.left), right: axis(props.axes?.right) },
    thresholds: (props.thresholds ?? []).flatMap((threshold) => {
      const value = finite(threshold.value);
      if (value == null) return [];
      return [{
        value,
        label: threshold.label,
        color: threshold.color,
        axis: threshold.axis === "right" ? "right" as const : "left" as const,
        style: threshold.style === "dotted" || threshold.style === "solid" ? threshold.style : "dashed" as const,
        width: clamp(threshold.width, 2, 1, 12),
      }];
    }),
  };
}

// ----- requests ---------------------------------------------------------------------------------

export type ChartEntityType = "catalog" | "document" | "register";

/** What one series reads: the entity's server-aggregated buckets. */
export type ChartRequest = { entityType: ChartEntityType; entityName: string; params: Record<string, string> };

/** Epoch millis → the ISO local-datetime the data endpoints' windows expect ("2026-01-01T00:00:00"). */
const toLocalIso = (ms: number) => format(new Date(ms), "yyyy-MM-dd'T'HH:mm:ss");

/** The entity's own date: a document's `date`, a register's `period` (a catalog has none). */
const entityDate = (entityType: ChartEntityType) => (entityType === "register" ? "period" : "date");

/** The field a series groups by: the authored `by`, else its entity's date field. */
export function seriesGroupField(config: ChartConfig, entityType: ChartEntityType): string {
  return logicalEntityKey(config.groupBy ?? entityDate(entityType));
}

/**
 * The date field the shared time range windows a series by: the measure's own `dateField`, else the
 * field of a date grouping, else the entity's date (`"date"`, `"period"` for registers).
 */
export function seriesWindowField(series: SeriesConfig, config: ChartConfig, entityType: ChartEntityType): string {
  if (series.measure?.dateField) return logicalEntityKey(series.measure.dateField);
  if (config.groupBy && config.groupByDate) return logicalEntityKey(config.groupBy);
  return entityDate(entityType);
}

/**
 * The request one series sends for the effective grouping and window: the aggregate endpoint
 * returns ready buckets — measure, `where` (as JSON), grouping, split and window all travel as
 * parameters. A register's measure reads its movements.
 */
export function chartRequest(
  series: SeriesConfig,
  config: ChartConfig,
  entityType: ChartEntityType,
  entityName: string,
  opts: { granularity?: GroupByDate; splitBy?: string; window: { from: number; to: number } },
): ChartRequest {
  const { window } = opts;
  // Documents and registers have a date of their own; catalogs don't, so the date default degrades
  // instead of failing the query: an un-grouped catalog chart takes the endpoint's single
  // grand-total bucket, and the window/span ride only on an author-named date field.
  const dated = entityType !== "catalog";
  const source = series.measure!;
  const measure: MeasureSpec = { ...source, field: source.field ? logicalEntityKey(source.field) : undefined };
  const groupField = seriesGroupField(config, entityType);
  let by: GroupSpec | undefined;
  if (dated || groupField !== "date") {
    const bucket = config.groupByDate ? opts.granularity ?? config.groupByDate : undefined;
    by = bucket ? { [groupField]: bucket as never } : groupField;
  }
  const params = aggregateParams(measure, by);
  if (opts.splitBy) params.seriesBy = logicalEntityKey(opts.splitBy);
  const windowField = seriesWindowField(series, config, entityType);
  if (dated || windowField !== "date") {
    // Send the date field even for an unbounded window — the auto-granularity needs `span` back.
    params.dateField = windowField;
    if (window.from !== -Infinity) params.from = toLocalIso(window.from);
    if (window.to !== Infinity) params.to = toLocalIso(window.to);
  }
  return { entityType, entityName, params };
}

// ----- data shaping -----------------------------------------------------------------------------

const NO_BUCKETS: AggregateBuckets = { buckets: [], truncated: false };

/**
 * One series' data from its server buckets (#199). Pies/donuts drop the server's zero-filled empty
 * periods (#246), which would only add zero-value slices to the legend.
 */
export function seriesData(
  series: SeriesConfig,
  config: ChartConfig,
  entityType: ChartEntityType,
  result: AggregateBuckets | null,
  opts: { granularity?: GroupByDate; splitBy?: string; round: boolean },
): SeriesData {
  const granularity = config.groupByDate ? opts.granularity ?? config.groupByDate : undefined;
  const spec = {
    groupBy: seriesGroupField(config, entityType),
    groupByDate: granularity,
    seriesBy: opts.splitBy ? logicalEntityKey(opts.splitBy) : undefined,
    metric: (series.measure?.aggregation === "count" ? "count" : "sum") as Metric,
    metricField: series.measure?.field ? logicalEntityKey(series.measure.field) : undefined,
    maxSeries: config.maxSeries,
  };
  const buckets = result ?? NO_BUCKETS;
  const shaped = opts.round ? { ...buckets, buckets: buckets.buckets.filter((b) => b.value !== 0) } : buckets;
  return seriesFromBuckets(shaped, spec);
}

/**
 * Join single-series datasets onto one x axis, each under its own key (`keys[i]`): date buckets join
 * on their start date and sort chronologically, categories on their label in first-seen order. A
 * bucket missing from one series reads 0 there. The total is meaningless across measures (0).
 */
export function mergeSeries(parts: SeriesData[], keys: string[]): SeriesData {
  const merged = new Map<string, Record<string, number | string>>();
  let dated = false;
  parts.forEach((part, i) => {
    const key = keys[i];
    for (const row of part.rows) {
      const iso = String(row[ISO_KEY] ?? "");
      if (iso) dated = true;
      const id = iso || `label:${row.label}`;
      let out = merged.get(id);
      if (!out) {
        out = { label: row.label, [ISO_KEY]: iso };
        for (const k of keys) out[k] = 0;
        merged.set(id, out);
      } else if (String(row.label).length > String(out.label).length) {
        // Prefer the widest (disambiguated) label for the same bucket.
        out.label = row.label;
      }
      out[key] = (Number(out[key]) || 0) + (Number(row[SINGLE_SERIES]) || 0);
    }
  });
  const rows = [...merged.values()];
  if (dated) rows.sort((a, b) => String(a[ISO_KEY]).localeCompare(String(b[ISO_KEY])));
  return { rows, seriesKeys: keys, total: 0 };
}

const requestKey = (r: ChartRequest) => queryKeys.aggregate(r.entityName, r.params);

async function fetchChartResult(r: ChartRequest): Promise<AggregateBuckets> {
  try {
    return await api.aggregate(`${r.entityType}s`, r.entityName, r.params);
  } catch {
    // A failed fetch shows "no data" rather than stale data.
    return NO_BUCKETS;
  }
}

// Module-level so TanStack keeps the combined array stable while no query's data changed.
const combineResults = (results: Array<{ data?: AggregateBuckets }>): Array<AggregateBuckets | null> => results.map((r) => r.data ?? null);

/**
 * Fetch every series' data, live: one TanStack query per request on the shell's client, keyed under
 * the entity so the server's change events refetch it.
 */
function useChartResults(requests: ChartRequest[]): Array<AggregateBuckets | null> {
  const results = useQueries({
    queries: requests.map((r) => ({
      queryKey: requestKey(r),
      queryFn: () => fetchChartResult(r),
      placeholderData: (previous: AggregateBuckets | undefined) => previous,
    })),
    combine: combineResults,
  }, queryClient);
  return results;
}

// ----- interactive controls -------------------------------------------------------------------

const TYPE_OPTIONS: ChartType[] = ["bar", "line", "area"];
const TYPE_LABELS: Record<string, string> = { bar: "Bar", line: "Line", area: "Area" };
const SCALE_OPTIONS: ScaleMode[] = ["absolute", "indexed", "normalized"];

interface ControlsSpec {
  enabled: Set<string>;
  /** Split options for the "series" control: `{value, label}` ("" = the single unsplit series). */
  series: { value: string; label: string }[];
  defaultSeries: string;
}

/**
 * Which per-chart controls a chart exposes, from `controls={["type", "scale", "series"]}`
 * (`"all"` = type,scale). The date range is the <em>shared</em> dashboard time picker, and date-bucket
 * sizing is either automatic or fixed by the author rather than another compact card control.
 * Charts that declare no controls render exactly as before. `seriesOptions` tunes the series control.
 */
function chartControls(props: ChartProps, base: ChartConfig): ControlsSpec {
  let raw: string[] = (props.controls ?? []).map((s) => s.trim().toLowerCase()).filter(Boolean);
  if (raw.includes("all")) raw = ["type", "scale"];
  const enabled = new Set(raw);
  const series = (props.seriesOptions ?? []).map(({ value, label }) => {
    const v = value.trim();
    return { value: v === "none" ? "" : v, label: label.trim() || v };
  });
  const defaultSeries = base.splitBy ?? series[0]?.value ?? "";
  return { enabled, series, defaultSeries };
}

/** Elapsed days for automatic buckets; empty/zero-length spans retain the one-day fallback. */
export function chartSpanDays(min: number, max: number): number {
  return max > min ? (max - min) / 86_400_000 : 1;
}

/** A chart grouped by a field or a date bucket, following the page's shared time range — or of `data` rows as given. */
export function Chart(props: ChartProps) {
  return (
    <Boundary label={props.title || "Chart"}>
      {Array.isArray(props.data) ? <ChartRows {...props} /> : <ChartSource {...props} />}
    </Boundary>
  );
}

/** A chart of the page's own rows: the config, then the same body without requests. */
function ChartRows(props: ChartProps) {
  // The rows change identity on every parent render; the config only depends on the other props.
  const { data: _rows, ...rest } = props;
  const restKey = JSON.stringify(rest);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const config = useMemo(() => chartConfig(props), [restKey]);
  return <ChartBody props={props} config={config} resolved={null} rows={props.data ?? []} />;
}

/**
 * One series of a `data` chart: its key summed per x bucket (split by `splitBy` when given). Pies drop
 * zero slices like the entity charts.
 */
export function rowSeriesData(series: SeriesConfig, props: ChartProps, config: ChartConfig, opts: { splitBy?: string; round: boolean }): SeriesData {
  const data = seriesFromRows(props.data ?? [], {
    x: props.x ?? "label",
    value: series.value ?? "value",
    splitBy: opts.splitBy,
    bucket: props.bucket,
    maxSeries: config.maxSeries,
  });
  if (!opts.round) return data;
  return { ...data, rows: data.rows.filter((r) => data.seriesKeys.some((k) => Number(r[k]) !== 0)) };
}

/** The header figure of a `data` chart: the plotted total, the last bucket, or none. */
export function rowHeadlineValue(mode: ChartProps["headline"], data: SeriesData): number | null {
  if (mode === "none" || data.rows.length === 0) return null;
  if (mode === "last") {
    const last = data.rows[data.rows.length - 1];
    return data.seriesKeys.reduce((sum, k) => sum + (Number(last[k]) || 0), 0);
  }
  return data.total;
}

type ResolvedSeries = { series: SeriesConfig; entityType: ChartEntityType; entityName: string };

/** Resolves every series' entity; until the model has them, a quiet empty card (no state is seeded yet). */
function ChartSource(props: ChartProps) {
  const model = useModel();
  // Props are plain data: key the derived config on their content, not the per-render object identity.
  const propsKey = JSON.stringify(props);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const config = useMemo(() => chartConfig(props), [propsKey]);
  const resolved = useMemo<ResolvedSeries[] | null>(() => {
    if (!config.series.length) return null;
    const out: ResolvedSeries[] = [];
    for (const series of config.series) {
      if (!series.measure) return null;
      const e = findEntity(model, series.measure.entity);
      if (!e) return null;
      const entityType = e.kind === "catalogs" ? "catalog" : e.kind === "documents" ? "document" : "register";
      out.push({ series, entityType, entityName: e.logicalName });
    }
    return out;
  }, [model, config]);
  if (!resolved) {
    return (
      <Card className="overflow-hidden">
        <CardHeader className="flex-row items-start justify-between gap-3 space-y-0 p-4 pb-1">
          <div className="flex items-center gap-1.5">
            <CardTitle className="text-[13px] font-medium">{props.title}</CardTitle>
            <HintIcon text={props.hint} size={13} />
          </div>
        </CardHeader>
        <CardContent className="p-4 pt-2">
          <div className="h-[210px]" />
        </CardContent>
      </Card>
    );
  }
  const key = resolved.map((r) => `${r.entityType}:${r.entityName}`).join("|");
  return <ChartBody key={key} props={props} config={config} resolved={resolved} />;
}

function ChartBody({ props, config, resolved, rows }: { props: ChartProps; config: ChartConfig; resolved: ResolvedSeries[] | null; rows?: ChartRow[] }) {
  const t = useMessages();
  const { title, hint } = props;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const controls = useMemo(() => chartControls(props, config), [config]);
  const { range, setAbsolute, granularity: granularityMode } = useTimeRange(); // shared dashboard controls
  const multi = config.multi;

  // The absolute window, resolved once per range change: resolveRange anchors a relative range to
  // "now", so resolving inline every render would shift the fetch params and never let them settle.
  const windowRange = useMemo(() => resolveRange(range), [range]);
  // A bounded selection already tells us its ideal bucket size. Use it during the very first render
  // after the range changes instead of requesting with the previous granularity, waiting for the
  // response span, and immediately requesting the same chart a second time. All-time still sizes
  // itself from the data span because its nominal window is intentionally unbounded.
  const boundedGranularity = useMemo<GroupByDate | null>(() => {
    if (windowRange.from === -Infinity || windowRange.to === Infinity) return null;
    return autoGranularityForSpan(Math.max(1 / 1440, (windowRange.to - windowRange.from) / 86_400_000));
  }, [windowRange]);

  // Granularity auto-follows the data actually inside the window (see the span effect below), so a
  // range change re-applies it.
  const [granularity, setGranularity] = useState<GroupByDate>(config.groupByDate ?? "day");

  // Local control state, seeded from the authored config; applied only when its control is enabled.
  const [type, setType] = useState<ChartType>(config.type);
  const [scale, setScale] = useState<ScaleMode>("absolute");
  const [splitBy, setSplitBy] = useState<string>(controls.defaultSeries);
  // Series/slices the viewer has toggled off from the legend.
  const [hidden, setHidden] = useState<Set<string>>(() => new Set());
  const toggleSeries = (key: string) =>
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  // Effective settings: control state when that control is on, else the authored config. A time
  // chart (a date grouping) always uses the auto/overridden granularity unless fixed. Type, split
  // and scale apply to the single-series chart only.
  const effType = !multi && controls.enabled.has("type") ? type : config.type;
  const effGranularity = config.groupByDate != null
    ? (config.bucketMode === "fixed"
        ? config.groupByDate
        : granularityMode === "auto"
          ? boundedGranularity ?? granularity
          : granularityMode)
    : undefined;
  const round = !multi && (effType === "donut" || effType === "pie");
  const effSplitBy = multi || round ? undefined : controls.enabled.has("series") ? splitBy || undefined : config.splitBy;
  const effScale: ScaleMode = !multi && !round && controls.enabled.has("scale") ? scale : "absolute";

  // One request per series. A control or range change lands here, so it refetches.
  const requests = useMemo(
    () => (resolved ?? []).map((r) =>
      chartRequest(r.series, config, r.entityType, r.entityName, { granularity: effGranularity, splitBy: effSplitBy, window: windowRange })),
    [resolved, config, effGranularity, effSplitBy, windowRange],
  );
  const results = useChartResults(requests);

  // Granularity auto-follows the span of the data actually inside the window (not the nominal range
  // length), which every bucket response carries as `span`. A granularity change is
  // itself a fetch param, so the first response at the seeded size may correct itself with one
  // follow-up fetch — expected, and it converges.
  const spanDays = useMemo(() => {
    let min = Infinity;
    let max = -Infinity;
    for (const result of results) {
      if (!result?.span) continue;
      min = Math.min(min, Date.parse(result.span.min));
      max = Math.max(max, Date.parse(result.span.max));
    }
    return chartSpanDays(min, max);
  }, [results]);
  const autoGran = useMemo(() => autoGranularityForSpan(spanDays), [spanDays]);
  useEffect(() => {
    if (boundedGranularity == null) setGranularity(autoGran);
  }, [autoGran, boundedGranularity]);

  const fmts = useChartFormatters(config);

  const data = useMemo(() => {
    if (rows) {
      if (multi) return mergeSeries(config.series.map((s) => rowSeriesData(s, props, config, { round: false })), config.series.map((s) => s.key));
      return applyScale(rowSeriesData(config.series[0], props, config, { splitBy: effSplitBy, round }), effScale);
    }
    if (!resolved) return { rows: [], seriesKeys: [SINGLE_SERIES], total: 0 } as SeriesData;
    const opts = { granularity: effGranularity, round };
    if (multi) {
      const parts = resolved.map((r, i) => seriesData(r.series, config, r.entityType, results[i], opts));
      return mergeSeries(parts, config.series.map((s) => s.key));
    }
    const built = seriesData(resolved[0].series, config, resolved[0].entityType, results[0], { ...opts, splitBy: effSplitBy });
    return applyScale(built, effScale);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `props` matters only through `rows` and `config`
  }, [rows, multi, resolved, config, results, effGranularity, round, effSplitBy, effScale]);

  const colors = useMemo(() => {
    if (multi) {
      const defaults = resolveColors(config.series.length, config.colors);
      return config.series.map((s, i) => (s.color ? resolveColors(1, s.color)[0] : defaults[i]));
    }
    const keys = round ? data.rows.map((row) => String(row.label)) : data.seriesKeys;
    const defaults = resolveColors(keys.length, config.colors);
    const primaryColor = config.series[0]?.color;
    return keys.map((key, i) => {
      const override = config.seriesColors[key] ?? (keys.length === 1 ? primaryColor : undefined);
      return override ? resolveColors(1, override)[0] : defaults[i];
    });
  }, [multi, round, data, config]);

  // A unique gradient-id prefix per widget instance — SVG ids are document-global, so two area
  // charts on one page would otherwise share (and clobber) the same <linearGradient>.
  const gradientPrefix = `chart-${useId().replace(/:/g, "")}`;

  // Once rescaled the grand total mixes units / is relative — hide the header figure then. Several
  // measures have no single total either. Grouped avg/min/max measures are non-additive, so adding
  // their buckets would produce a misleading headline (#353).
  const headlineValue =
    effScale === "absolute" && data.rows.length > 0 && !multi
      ? rows
        ? rowHeadlineValue(props.headline, data)
        : chartHeadlineValue(config.series[0].measure?.aggregation ?? "sum", data)
      : null;

  // Drag-select a region to set the shared absolute range (Grafana-style zoom). A data chart has no
  // range to set: its rows are the page's, so it does not zoom.
  const zoom = useDragZoom(data.rows, setAbsolute);
  const dragProps = rows ? undefined : zoom.dragProps;
  const refArea = rows ? null : zoom.refArea;

  // Granularity follows the shared range unless the author fixed it. Split / type / scale don't
  // apply to a multi-measure chart.
  const controlNodes: React.ReactNode[] = [];
  if (controls.enabled.has("series") && controls.series.length > 0 && !multi)
    controlNodes.push(<Segmented key="series" size="sm" value={splitBy} onChange={setSplitBy} options={controls.series} />);
  if (controls.enabled.has("type") && !multi)
    controlNodes.push(
      <Segmented key="type" size="sm" value={type} onChange={setType} options={TYPE_OPTIONS.map((k) => ({ value: k, label: TYPE_LABELS[k] }))} />
    );
  if (controls.enabled.has("scale") && !round && !multi)
    controlNodes.push(
      <Segmented key="scale" size="sm" value={scale} onChange={setScale} options={SCALE_OPTIONS.map((s) => ({ value: s, label: SCALE_LABELS[s] }))} />
    );

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-start justify-between gap-3 space-y-0 p-4 pb-1">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{title}</CardTitle>
          <HintIcon text={hint} size={13} />
        </div>
        <div className="flex items-center gap-1.5">
          {/* The controls live where the grand-total figure would be; a chart with no controls keeps
              the figure there instead. */}
          {controlNodes.length > 0 ? (
            <div className="flex flex-wrap items-center justify-end gap-1.5">{controlNodes}</div>
          ) : headlineValue != null ? (
            <AnimatedNumber
              value={fmts[0].fmt(headlineValue)}
              className="text-[13px] font-semibold tabular-nums text-foreground"
            />
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="p-4 pt-2">
        {data.rows.length === 0 ? (
          <div className="flex h-[210px] items-center justify-center text-xs text-muted-foreground">{t("empty.noData")}</div>
        ) : (
          <ResponsiveChart height={round && props.height == null ? 230 : config.height}>
            {multi
              ? renderMulti(data, config, fmts, colors, gradientPrefix, hidden, toggleSeries, dragProps, refArea)
              : renderChart(effType, data, colors, fmts[0], config, gradientPrefix, hidden, toggleSeries, dragProps, refArea)}
          </ResponsiveChart>
        )}
      </CardContent>
    </Card>
  );
}

interface ChartFormatters {
  /** Full figures for the tooltip/header/labels. */
  fmt: (n: number) => string;
  /** Compact figures for the axis, where a full currency value clips. */
  fmtAxis: (n: number) => string;
}

/** One formatter pair per series, from its currency/unit/format and the chart's locale. */
function useChartFormatters(config: ChartConfig): ChartFormatters[] {
  return useMemo(
    () => {
      const list = config.series.length ? config.series : [undefined];
      return list.map((s) => {
        const opts = { currency: s?.currency, unit: s?.unit, unitPosition: config.unitPosition, format: s?.format, locale: config.locale };
        return { fmt: (n: number) => formatNumber(n, opts), fmtAxis: (n: number) => formatCompact(n, opts) };
      });
    },
    [config]
  );
}


interface DragProps {
  style: React.CSSProperties;
  onMouseDown: (e: MouseHandlerDataParam) => void;
  onMouseMove: (e: MouseHandlerDataParam) => void;
  onMouseUp: (e: MouseHandlerDataParam) => void;
}
interface RefAreaSel {
  x1: string;
  x2: string;
}

/**
 * Drag-select a region on a time chart to set an absolute range (Grafana-style zoom). Maps the
 * x-axis labels under the cursor back to their bucket dates ({@link ISO_KEY}) and calls {@code onZoom}.
 *
 * Release is committed from a window-level {@code mouseup}, not the chart's own, so a drag that ends
 * with the cursor off the plot (or past the last bar) still zooms instead of silently cancelling —
 * the previous version reset on {@code mouseleave}, which is what made it feel finicky to trigger.
 */
function useDragZoom(
  rows: Array<Record<string, number | string>>,
  onZoom: (fromIso: string, toIso: string) => void
): { dragProps: DragProps; refArea: RefAreaSel | null } {
  // Selection is tracked by tooltip INDEX, not by label: labels aren't guaranteed unique (the
  // shaping layer widens duplicates, but the index is unambiguous by construction) and the index
  // maps straight back to the bucket's ISO_KEY.
  const [sel, setSel] = useState<{ left: number | null; right: number | null }>({ left: null, right: null });
  const selRef = useRef(sel);
  const dragging = useRef(false);

  const finish = useCallback((right?: number | null) => {
    if (!dragging.current) return;
    dragging.current = false;
    const current = right == null ? selRef.current : { ...selRef.current, right };
    if (current.left != null && current.right != null && current.left !== current.right) {
      const first = Math.min(current.left, current.right);
      const last = Math.max(current.left, current.right);
      const a = String(rows[first]?.[ISO_KEY] ?? "");
      let b = String(rows[last]?.[ISO_KEY] ?? "");
      if (a && b) {
        // A datetime bound is exact/exclusive downstream. Advance it to the following bucket so
        // releasing on 18:00 includes the 18:00–19:00 bucket instead of redrawing only through
        // 17:00. Date-only day bounds are already expanded to end-of-day by resolveRange().
        if (b.includes("T")) {
          const next = String(rows[last + 1]?.[ISO_KEY] ?? "");
          if (next) b = next;
          else {
            const previous = String(rows[last - 1]?.[ISO_KEY] ?? "");
            const at = Date.parse(b);
            const before = Date.parse(previous);
            if (!Number.isNaN(at) && !Number.isNaN(before) && at > before) {
              b = format(new Date(at + (at - before)), "yyyy-MM-dd'T'HH:mm:ss");
            }
          }
        }
        onZoom(a, b);
      }
    }
    selRef.current = { left: null, right: null };
    setSel(selRef.current);
  }, [rows, onZoom]);

  useEffect(() => {
    const up = () => finish();
    window.addEventListener("mouseup", up);
    return () => window.removeEventListener("mouseup", up);
  }, [finish]);

  const idxOf = (e: MouseHandlerDataParam | undefined | null) => {
    // Recharts 3 stores the active tooltip index as a string ("14") even though some chart
    // callbacks still type it as a number. Accept either representation; rejecting the string is
    // what made drag-zoom silently stop working in 1.12.5.
    const raw = e?.activeTooltipIndex;
    if (raw == null || raw === "") return null;
    const i = Number(raw);
    return Number.isInteger(i) && i >= 0 && i < rows.length ? i : null;
  };
  const dragProps: DragProps = {
    // crosshair signals the region-select affordance; userSelect:none stops the drag from
    // highlighting axis labels mid-gesture.
    style: { cursor: "crosshair", userSelect: "none" },
    onMouseDown: (e) => {
      const i = idxOf(e);
      if (i != null) {
        dragging.current = true;
        selRef.current = { left: i, right: i };
        setSel(selRef.current);
      }
    },
    onMouseMove: (e) => {
      const i = idxOf(e);
      if (i != null && dragging.current && selRef.current.left != null) {
        selRef.current = { ...selRef.current, right: i };
        setSel(selRef.current);
      }
    },
    // Recharts can throttle onMouseMove during a quick gesture. Its mouse-up payload carries the
    // actual bucket under the released pointer, so use that as the authoritative right endpoint.
    onMouseUp: (e) => finish(idxOf(e)),
  };
  // ReferenceArea still addresses the category axis by label — indexes resolve to the (now
  // unambiguous) labels only at render time.
  const refArea =
    sel.left != null && sel.right != null && sel.left !== sel.right
      ? { x1: String(rows[sel.left]?.label ?? ""), x2: String(rows[sel.right]?.label ?? "") }
      : null;
  return { dragProps, refArea };
}

// recharts paints the Legend wrapper after the Tooltip wrapper, so without this the hover tooltip
// stacks *under* the legend. Lift it above.
const TOOLTIP_WRAPPER = { zIndex: 50, outline: "none" } as const;

interface TooltipItem {
  name?: string | number;
  value?: unknown;
  color?: string;
  dataKey?: unknown;
  payload?: { fill?: string };
}

/**
 * Custom recharts tooltip: a date/label header, one row per series with its color swatch and a
 * right-aligned tabular value. Pies have no axis label, so the slice's own name becomes the header.
 * Single-series charts have no per-series names, so {@code fallbackName} (the chart's own label)
 * fills the row — a lone unlabeled dot next to a number reads as broken.
 */
function ChartTooltipContent({
  active,
  payload,
  label,
  format,
  fallbackName,
}: {
  active?: boolean;
  payload?: TooltipItem[];
  label?: string | number;
  format: (value: unknown, name: unknown, dataKey: unknown) => string;
  fallbackName?: string;
}) {
  if (!active || !payload?.length) return null;
  const heading = label ?? (payload.length === 1 ? payload[0].name : undefined);
  return (
    <div className="min-w-32 rounded-lg border border-border/60 bg-popover/95 px-3 py-2 text-xs shadow-lg backdrop-blur-sm">
      {heading != null && heading !== "" && heading !== SINGLE_SERIES && (
        <div className="mb-1.5 font-medium text-popover-foreground">{String(heading)}</div>
      )}
      <div className="grid gap-1">
        {payload.map((item, i) => {
          const own = item.name === SINGLE_SERIES || item.name === heading ? "" : item.name;
          const name = own != null && own !== "" ? own : fallbackName;
          return (
            <div key={i} className="flex items-center justify-between gap-4">
              <span className="flex items-center gap-1.5 text-muted-foreground">
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ background: item.color ?? item.payload?.fill }}
                />
                {name != null && name !== "" && <span>{String(name)}</span>}
              </span>
              <span className="font-medium tabular-nums text-popover-foreground">
                {format(item.value, item.name, item.dataKey)}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * A drop-in replacement for recharts' {@code ResponsiveContainer} that measures its own width with a
 * ResizeObserver and hands the chart explicit numeric {@code width}/{@code height}. ResponsiveContainer
 * intermittently collapses to an 8×8 surface for charts that carry a Legend when mounted inside some
 * page layouts — it reads a 0 size before layout settles and never recovers. Measuring ourselves is
 * reliable, and skipping its internal observer is marginally cheaper.
 */
function ResponsiveChart({
  height,
  children,
}: {
  height: number;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- cloneElement of any Recharts chart
  children: ReactElement<any>;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    // Only publish a genuinely new width: ResizeObserver fires once on observe() (often with the same
    // size we just measured), and a redundant width update mid-animation aborts the pie's enter
    // transition — leaving the slices undrawn. Skipping no-op updates also saves a chart re-render.
    const measure = () => setWidth((prev) => (el.clientWidth > 0 && el.clientWidth !== prev ? el.clientWidth : prev));
    measure(); // synchronous first measure, before paint — no 0-width flash
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return (
    <div ref={ref} style={{ width: "100%", height }}>
      {width > 0 ? cloneElement(children, { width, height }) : null}
    </div>
  );
}


// A plain function (not a component) so it can be the direct child of ResponsiveChart, which clones
// its single child to inject the measured width/height — a wrapper component would swallow them and
// the chart would render at 0×0.
function renderChart(
  kind: ChartType,
  data: SeriesData,
  colors: string[],
  fmts: ChartFormatters,
  config: ChartConfig,
  gradientPrefix: string,
  hidden: Set<string>,
  onToggle: (key: string) => void,
  dragProps?: DragProps,
  refArea?: RefAreaSel | null
): React.ReactElement {
  const { fmt, fmtAxis } = fmts;
  const primary = config.series[0];
  const seriesName = primary.label;
  const tooltipValue = (value: unknown) => fmt(toNumber(value) ?? 0);
  const { seriesKeys } = data;
  const multi = seriesKeys.length > 1 || seriesKeys[0] !== SINGLE_SERIES;
  const axis = { stroke: "hsl(var(--muted-foreground))", fontSize: 12, tickLine: false, axisLine: false } as const;
  const stacked = config.stacked;
  const leftAxis = config.axes.left;
  const domain: [number | "auto", number | "auto"] = [leftAxis.min ?? "auto", leftAxis.max ?? "auto"];
  const showLabels = config.dataLabels === "all" ||
    (config.dataLabels === "auto" && data.rows.length <= 12 && seriesKeys.length === 1);
  const thresholdLines = config.thresholds.filter((threshold) => threshold.axis === "left").map((threshold, i) => (
    <ReferenceLine key={`threshold-${i}`} y={threshold.value} ifOverflow="extendDomain"
      stroke={threshold.color ? resolveColors(1, threshold.color)[0] : "hsl(var(--destructive))"}
      strokeWidth={threshold.width}
      strokeDasharray={dashArray(threshold.style)}
      label={threshold.label ? { value: threshold.label, position: "insideTopRight", fill: "hsl(var(--muted-foreground))", fontSize: 11 } : undefined} />
  ));

  // Clicking a legend item toggles its series off/on. We pass an explicit, stable `payload` so a
  // hidden series keeps its (greyed, `inactive`) legend entry to click back on — relying on a child's
  // `hide` alone would drop it from the auto-legend and there'd be nothing left to click.
  const onLegendClick = (e: { dataKey?: unknown }) => {
    const key = String(e?.dataKey ?? "");
    if (key) onToggle(key);
  };
  // recharts keeps a hidden series in the auto-legend (the canonical toggle pattern), so we just
  // click-to-toggle and grey the hidden ones via the formatter.
  const seriesLegend = multi && config.legend !== "hidden" ? (
    <Legend
      {...legendPlacement(config.legend)}
      wrapperStyle={{ fontSize: 12, paddingTop: config.legend === "bottom" ? 6 : 0, cursor: "pointer" }}
      iconType="circle"
      iconSize={8}
      onClick={onLegendClick}
      formatter={(value: unknown, entry?: { dataKey?: unknown }) => (
        <span style={{ opacity: hidden.has(String(entry?.dataKey ?? value ?? "")) ? 0.45 : 1 }}>
          {String(value)}
        </span>
      )}
    />
  ) : null;

  if (kind === "donut" || kind === "pie") {
    // A hidden slice keeps its legend entry but contributes 0 to the ring.
    const pieRows = data.rows.map((r, i) => ({
      ...r,
      [SINGLE_SERIES]: hidden.has(String(r.label)) ? 0 : r[SINGLE_SERIES],
      fill: colors[i % colors.length],
    }));
    // Slice tooltips summarize value + share of the visible ring (hidden slices excluded).
    const pieTotal = pieRows.reduce((s, r) => s + (toNumber(r[SINGLE_SERIES]) ?? 0), 0);
    const pieValue = (value: unknown) => {
      const n = toNumber(value) ?? 0;
      return pieTotal > 0 ? `${fmt(n)} · ${Math.round((n / pieTotal) * 100)}%` : fmt(n);
    };
    return (
      <PieChart>
        <Tooltip wrapperStyle={TOOLTIP_WRAPPER} content={<ChartTooltipContent format={pieValue} />} />
        {config.legend !== "hidden" && <Legend
          {...legendPlacement(config.legend)}
          wrapperStyle={{ fontSize: 12, cursor: "pointer" }}
          iconType="circle"
          iconSize={8}
          onClick={(e: { value?: unknown }) => {
            // A pie's slices share one dataKey, so identify the clicked slice by its label.
            const label = String(e?.value ?? "");
            if (label) onToggle(label);
          }}
          formatter={(value: unknown) => (
            <span style={{ opacity: hidden.has(String(value)) ? 0.45 : 1 }}>{String(value)}</span>
          )}
        />}
        <Pie
          data={pieRows}
          dataKey={SINGLE_SERIES}
          nameKey="label"
          innerRadius={kind === "pie" ? 0 : `${config.donutHole}%`}
          outerRadius={82}
          paddingAngle={kind === "pie" ? 0 : 2}
          stroke="hsl(var(--card))"
          strokeWidth={2}
          // recharts' Pie enter-animation is fragile — a re-render during it (e.g. a width change)
          // can drop every sector and never restore them. The slices matter more than the flourish.
          isAnimationActive={false}
          label={showLabels ? ({ name, percent }: { name?: string; percent?: number }) => `${name ?? ""} ${Math.round((percent ?? 0) * 100)}%` : false}
        />
      </PieChart>
    );
  }

  if (kind === "line" || kind === "area") {
    const ChartImpl = kind === "line" ? LineChart : AreaChart;
    return (
      <ChartImpl data={data.rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }} {...dragProps}>
        {refArea && <ReferenceArea x1={refArea.x1} x2={refArea.x2} strokeOpacity={0} fill="hsl(var(--primary))" fillOpacity={0.12} />}
        {kind === "area" && (
          <defs>
            {seriesKeys.map((_, i) => (
              <linearGradient key={i} id={`${gradientPrefix}-${i}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={colors[i]} stopOpacity={0.3 * primary.opacity} />
                <stop offset="95%" stopColor={colors[i]} stopOpacity={0.02 * primary.opacity} />
              </linearGradient>
            ))}
          </defs>
        )}
        {config.grid && <CartesianGrid stroke="hsl(var(--border))" strokeOpacity={0.6} strokeDasharray="3 3" />}
        <XAxis dataKey="label" {...axis} />
        {leftAxis.visible && <YAxis {...axis} width={40} tickFormatter={fmtAxis} domain={domain} scale={leftAxis.scale}
          label={leftAxis.label ? { value: leftAxis.label, angle: -90, position: "insideLeft" } : undefined} />}
        <Tooltip wrapperStyle={TOOLTIP_WRAPPER} cursor={{ stroke: "hsl(var(--border))" }} content={<ChartTooltipContent format={tooltipValue} fallbackName={seriesName} />} />
        {seriesLegend}
        {thresholdLines}
        {seriesKeys.map((key, i) =>
          kind === "line" ? (
            <Line
              key={key}
              type={config.curve}
              dataKey={key}
              name={key === SINGLE_SERIES ? undefined : key}
              stroke={colors[i]}
              strokeWidth={primary.strokeWidth}
              strokeDasharray={dashArray(primary.lineStyle)}
              opacity={primary.opacity}
              dot={config.points}
              activeDot={{ r: 4 }}
              hide={hidden.has(key)}
            >
              {showLabels && <LabelList dataKey={key} position="top" formatter={(value: unknown) => fmt(toNumber(value) ?? 0)} />}
            </Line>
          ) : (
            <Area
              key={key}
              type={config.curve}
              dataKey={key}
              name={key === SINGLE_SERIES ? undefined : key}
              stroke={colors[i]}
              strokeWidth={primary.strokeWidth}
              strokeDasharray={dashArray(primary.lineStyle)}
              opacity={primary.opacity}
              fill={`url(#${gradientPrefix}-${i})`}
              stackId={stacked ? "stack" : undefined}
              dot={config.points}
              hide={hidden.has(key)}
            >
              {showLabels && <LabelList dataKey={key} position="top" formatter={(value: unknown) => fmt(toNumber(value) ?? 0)} />}
            </Area>
          )
        )}
      </ChartImpl>
    );
  }

  return (
    <BarChart data={data.rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }} {...dragProps}>
      {refArea && <ReferenceArea x1={refArea.x1} x2={refArea.x2} strokeOpacity={0} fill="hsl(var(--primary))" fillOpacity={0.12} />}
      {config.grid && <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />}
      <XAxis dataKey="label" {...axis} />
      {leftAxis.visible && <YAxis {...axis} width={52} tickFormatter={fmtAxis} domain={domain} scale={leftAxis.scale}
        label={leftAxis.label ? { value: leftAxis.label, angle: -90, position: "insideLeft" } : undefined} />}
      <Tooltip wrapperStyle={TOOLTIP_WRAPPER} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} content={<ChartTooltipContent format={tooltipValue} fallbackName={seriesName} />} />
      {seriesLegend}
      {thresholdLines}
      {seriesKeys.map((key, i) => {
        // Round only the exposed top: the last series in a stack, or every bar when not stacked.
        const top = !stacked || i === seriesKeys.length - 1;
        return (
          <Bar
            key={key}
            dataKey={key}
            name={key === SINGLE_SERIES ? undefined : key}
            fill={colors[i]}
            fillOpacity={primary.opacity}
            stackId={stacked ? "stack" : undefined}
            radius={top ? [3, 3, 0, 0] : [0, 0, 0, 0]}
            maxBarSize={config.barSize}
            hide={hidden.has(key)}
          >
            {showLabels && <LabelList dataKey={key} position="top" formatter={(value: unknown) => fmt(toNumber(value) ?? 0)} />}
          </Bar>
        );
      })}
    </BarChart>
  );
}

function legendPlacement(position: ChartConfig["legend"]) {
  if (position === "top") return { verticalAlign: "top" as const, align: "center" as const };
  if (position === "left") return { verticalAlign: "middle" as const, align: "left" as const, layout: "vertical" as const };
  if (position === "right") return { verticalAlign: "middle" as const, align: "right" as const, layout: "vertical" as const };
  return { verticalAlign: "bottom" as const, align: "center" as const };
}

// A multi-measure chart: each series is its own measure with its own type (bar/line/area), on the
// left or right y axis, so very different magnitudes (e.g. revenue vs order count) read cleanly side
// by side. Bars render behind lines/areas. Clicking a legend entry toggles its series.
function renderMulti(
  data: SeriesData,
  config: ChartConfig,
  fmts: ChartFormatters[],
  colors: string[],
  gradientPrefix: string,
  hidden: Set<string>,
  onToggle: (key: string) => void,
  dragProps?: DragProps,
  refArea?: RefAreaSel | null
): React.ReactElement {
  const axis = { stroke: "hsl(var(--muted-foreground))", fontSize: 12, tickLine: false, axisLine: false } as const;
  const showLabels = config.dataLabels === "all" || (config.dataLabels === "auto" && data.rows.length <= 10);
  const series = config.series.map((s, i) => ({ ...s, color: colors[i], fmt: fmts[i] }));
  const sides = (["left", "right"] as const).filter((side) => series.some((s) => s.axis === side));
  // Each axis formats like the first series plotted on it.
  const axisFmt = (side: "left" | "right") => series.find((s) => s.axis === side)!.fmt.fmtAxis;
  // Bars behind lines/areas so a line isn't hidden by another measure's bars.
  const ordered = [...series].sort((a, b) => (a.type === "bar" ? 0 : 1) - (b.type === "bar" ? 0 : 1));
  const renderSeries = (s: (typeof series)[number]) => {
    const label = (value: unknown) => s.fmt.fmt(toNumber(value) ?? 0);
    const labels = showLabels && <LabelList dataKey={s.key} position="top" formatter={label} />;
    if (s.type === "bar") {
      return <Bar key={s.key} yAxisId={s.axis} dataKey={s.key} name={s.label} fill={s.color} fillOpacity={s.opacity}
        radius={[3, 3, 0, 0]} maxBarSize={config.barSize} hide={hidden.has(s.key)}>{labels}</Bar>;
    }
    if (s.type === "area") {
      return <Area key={s.key} yAxisId={s.axis} type={config.curve} dataKey={s.key} name={s.label} stroke={s.color}
        strokeWidth={s.strokeWidth} strokeDasharray={dashArray(s.lineStyle)} opacity={s.opacity}
        fill={`url(#${gradientPrefix}-multi-${s.key})`} dot={config.points} hide={hidden.has(s.key)}>{labels}</Area>;
    }
    return <Line key={s.key} yAxisId={s.axis} type={config.curve} dataKey={s.key} name={s.label} stroke={s.color}
      strokeWidth={s.strokeWidth} strokeDasharray={dashArray(s.lineStyle)} opacity={s.opacity} dot={config.points}
      activeDot={{ r: 4 }} hide={hidden.has(s.key)}>{labels}</Line>;
  };
  const byKey = new Map(series.map((s) => [s.key, s]));
  const tooltipValue = (value: unknown, _name: unknown, dataKey: unknown) =>
    (byKey.get(String(dataKey)) ?? series[0]).fmt.fmt(toNumber(value) ?? 0);
  return (
    <ComposedChart data={data.rows} margin={{ top: 4, right: 4, left: 0, bottom: 0 }} {...dragProps}>
      {refArea && <ReferenceArea yAxisId={sides[0]} x1={refArea.x1} x2={refArea.x2} strokeOpacity={0} fill="hsl(var(--primary))" fillOpacity={0.12} />}
      <defs>
        {series.map((s) => (
          <linearGradient key={s.key} id={`${gradientPrefix}-multi-${s.key}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor={s.color} stopOpacity={0.3 * s.opacity} />
            <stop offset="95%" stopColor={s.color} stopOpacity={0.02 * s.opacity} />
          </linearGradient>
        ))}
      </defs>
      {config.grid && <CartesianGrid yAxisId={sides[0]} stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />}
      <XAxis dataKey="label" {...axis} />
      {sides.map((side) => {
        const a = config.axes[side];
        // An invisible axis is still declared (hidden): its series reference it by id.
        return <YAxis key={side} yAxisId={side} orientation={side} hide={!a.visible} {...axis} width={side === "left" ? 40 : 36}
          tickFormatter={axisFmt(side)} domain={[a.min ?? "auto", a.max ?? "auto"]} scale={a.scale}
          label={a.label ? { value: a.label, angle: side === "left" ? -90 : 90, position: side === "left" ? "insideLeft" : "insideRight" } : undefined} />;
      })}
      <Tooltip wrapperStyle={TOOLTIP_WRAPPER} cursor={{ fill: "hsl(var(--accent) / 0.2)" }} content={<ChartTooltipContent format={tooltipValue} />} />
      {config.legend !== "hidden" && <Legend {...legendPlacement(config.legend)}
        wrapperStyle={{ fontSize: 12, paddingTop: config.legend === "bottom" ? 6 : 0, cursor: "pointer" }} iconType="circle" iconSize={8}
        onClick={(e: { dataKey?: unknown }) => {
          const key = String(e?.dataKey ?? "");
          if (key) onToggle(key);
        }}
        formatter={(value: unknown, entry?: { dataKey?: unknown }) => (
          <span style={{ opacity: hidden.has(String(entry?.dataKey ?? "")) ? 0.45 : 1 }}>{String(value)}</span>
        )} />}
      {/* A threshold on an axis no series uses has nothing to be drawn against. */}
      {config.thresholds.filter((threshold) => sides.includes(threshold.axis)).map((threshold, i) => (
        <ReferenceLine key={`threshold-${i}`} yAxisId={threshold.axis} y={threshold.value} ifOverflow="extendDomain"
          stroke={threshold.color ? resolveColors(1, threshold.color)[0] : "hsl(var(--destructive))"}
          strokeWidth={threshold.width}
          strokeDasharray={dashArray(threshold.style)}
          label={threshold.label ? { value: threshold.label, position: "insideTopRight", fill: "hsl(var(--muted-foreground))", fontSize: 11 } : undefined} />
      ))}
      {ordered.map(renderSeries)}
    </ComposedChart>
  );
}


// ----- shared time range ----------------------------------------------------------------------

/** The page-wide time-range picker every stat and chart on the page follows. */
export interface TimeRangeProps {
  /** Names the picker's error boundary; the picker renders no visible title. */
  title?: string;
  /** Accepted for parity with the other widgets; the picker renders no hint. */
  hint?: string;
  /** Duration ids offered, e.g. `["15m", "1h", "24h", "7d", "30d", "all"]` (any `<n><unit>`, plus `"all"`). */
  presets?: string[];
  /** The preset selected until the viewer picks another, e.g. `"30d"`. */
  default?: string;
  /** Key the viewer's choice is saved under (default: the page route); pages sharing an id share it. */
  id?: string;
}

/**
 * The shared dashboard time picker: the {@link TimeRangeFacet} chip — the same filter-chip family
 * as the list toolbar — right-aligned. Authors tune it per dashboard with `presets` and `default`;
 * the viewer's choice is saved per `id`, defaulting to the page route.
 */
export function TimeRange(props: TimeRangeProps) {
  return (
    <Boundary label={props.title || "Time range"}>
      <TimeRangeBody {...props} />
    </Boundary>
  );
}

function TimeRangeBody({ presets: presetIds, default: defaultId, id }: TimeRangeProps) {
  const t = useMessages();
  const {
    range,
    granularity,
    resolvedGranularity,
    presets,
    setGranularity,
    setPreset,
    setAbsolute,
    configure,
  } = useTimeRange();
  // A joined key so a fresh-but-equal array each render doesn't reconfigure the provider.
  const presetsCsv = presetIds?.length ? presetIds.join(",") : undefined;
  const { pathname } = useLocation();
  const scope = id || pathname;
  useEffect(() => {
    configure({ presets: presetsFromConfig(presetsCsv), defaultRangeId: defaultId, scope });
  }, [configure, presetsCsv, defaultId, scope]);
  return (
    <div className="flex items-center justify-end">
      <TimeRangeFacet
        label={t("timeRange.dateRange")}
        presets={presets}
        range={range}
        granularity={granularity}
        resolvedGranularity={resolvedGranularity}
        onGranularity={setGranularity}
        onPreset={setPreset}
        onAbsolute={setAbsolute}
      />
    </div>
  );
}
