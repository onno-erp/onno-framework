import { useMemo } from "react";
import { format } from "date-fns";
import { ArrowDownRight, ArrowRight, ArrowUpRight } from "lucide-react";
import { formatCompact, formatNumber } from "../../lib/format";
import { resolveColor } from "../../lib/chart-colors";
import {
  seriesFromBuckets,
  useAggregateBuckets,
  type GroupByDate,
  type Metric,
  type SeriesData,
} from "../../lib/aggregate-data";
import { rangeSpanDays, resolveRange } from "../../lib/time-range";
import { useTimeRange } from "../../providers/time-range-provider";
import { useEntitySource, type EntitySource } from "../../lib/entity-source";
import { aggregateParams, groupParams, type DateBucket, type GroupSpec, type MeasureSpec } from "../../lib/measure";
import { Boundary } from "../pages/boundary";
import { Card, CardContent } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { TrendLine } from "./trend-line";
import { cn } from "../../lib/utils";
import { AnimatedNumber } from "../ui/animated-number";
import { useMessages } from "../../providers/messages-provider";

const toLocalIso = (ms: number) => format(new Date(ms), "yyyy-MM-dd'T'HH:mm:ss");
const EMPTY_BUCKETS = { buckets: [], truncated: false };

/** A time window in epoch ms; an open side is ±Infinity. */
export type MeasureWindow = { from: number; to: number };

/**
 * The aggregate endpoint's query for a measure: {@link aggregateParams} (metric, field, grouping,
 * `where` JSON) plus the time window on `dateField` when one is bounded.
 */
export function measureParams(
  measure: MeasureSpec,
  options: { by?: GroupSpec; dateField?: string; window?: MeasureWindow | null } = {}
): Record<string, string> {
  const params = aggregateParams(measure, options.by);
  const { dateField, window } = options;
  if (dateField && window && (window.from !== -Infinity || window.to !== Infinity)) {
    params.dateField = dateField;
    if (window.from !== -Infinity) params.from = toLocalIso(window.from);
    if (window.to !== Infinity) params.to = toLocalIso(window.to);
  }
  return params;
}

/** The same-length window right before `window`, or null when it is open-ended. */
export function previousWindow(window: MeasureWindow): MeasureWindow | null {
  if (window.from === -Infinity || window.to === Infinity) return null;
  const span = window.to - window.from;
  if (!Number.isFinite(span) || span <= 0) return null;
  return { from: window.from - span, to: window.from };
}

/** Sparkline buckets that keep a trend readable over the selected span. */
export function granularityForSpan(spanDays: number): DateBucket {
  if (spanDays <= 2) return "hour";
  if (spanDays <= 60) return "day";
  if (spanDays <= 240) return "week";
  return "month";
}

/**
 * One measure as a series, aggregated by the server (#199, `where` sent as JSON) — a register's over
 * its movements. `by` groups explicitly; otherwise `dateBucket` groups by the measure's date field
 * (when the entity has one: a document's `date`, a register's `period`). A null measure is idle.
 * Shared by the stat, sparkline and gauge.
 */
export function useMeasureSeries(
  measure: MeasureSpec | null | undefined,
  options: { by?: GroupSpec; dateBucket?: DateBucket; window?: MeasureWindow | null } = {}
): { source: EntitySource | null; series: SeriesData } {
  const source = useEntitySource(measure?.entity, { dateField: measure?.dateField, where: measure?.where });
  const isRegister = source?.entityType === "register";
  // Catalogs have no date system field: without an explicit dateField they neither window nor
  // group by date (the default degrades to one grand-total bucket).
  const dated = !!measure && (isRegister || source?.entityType === "document" || !!measure.dateField);
  const dateField = measure?.dateField || (isRegister ? "period" : "date");
  const { dateBucket, window } = options;
  const by: GroupSpec | undefined = options.by ?? (dateBucket && dated ? { [dateField]: dateBucket } : undefined);
  const byKey = by ? JSON.stringify(by) : "";
  const fromMs = window?.from;
  const toMs = window?.to;

  const measureKey = measure ? JSON.stringify(measure) : "";
  const params = useMemo(() => {
    if (!measureKey || !source) return null;
    const m = JSON.parse(measureKey) as MeasureSpec;
    const win = fromMs == null || toMs == null ? null : { from: fromMs, to: toMs };
    return measureParams(m, {
      by: byKey ? (JSON.parse(byKey) as GroupSpec) : undefined,
      dateField: dated ? dateField : undefined,
      window: win,
    });
  }, [measureKey, source, byKey, dated, dateField, fromMs, toMs]);
  const resp = useAggregateBuckets(source, params);

  const metric = (measure?.aggregation ?? "count") as Metric;
  const metricField = measure?.field;
  const series = useMemo(() => {
    const group = groupParams(byKey ? (JSON.parse(byKey) as GroupSpec) : undefined);
    return seriesFromBuckets(resp ?? EMPTY_BUCKETS, {
      groupBy: group.groupBy ?? "",
      groupByDate: group.groupByDate as GroupByDate | undefined,
      metric,
      metricField,
    });
  }, [resp, byKey, metric, metricField]);
  return { source, series };
}

/** Props of {@link Stat}. */
export interface StatProps {
  /** Card heading. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** The headline number, e.g. `count("Orders", { posted: true })` or `sum("Orders", "total")`. */
  measure?: MeasureSpec;
  /**
   * A figure the page already has, instead of `measure`: shown as is (no request, no shared time
   * range). `previous` adds the change against it, `points` the sparkline.
   *
   *   <Stat title="Followers" value={47656} previous={47279} points={[47279, 47310, 47656]} />
   */
  value?: number | null;
  /** With `value`: the figure to compare with (the change badge / arrow). */
  previous?: number | null;
  /** With `value`: the sparkline's points, oldest first. */
  points?: number[];
  /**
   * A denominator: the headline becomes `measure` ÷ `ratioTo` × 100 over the selected window. A
   * ratio tile is headline-only (no trend/sparkline).
   */
  ratioTo?: MeasureSpec;
  /** Show the change against the previous period with a sparkline (default true). */
  trend?: boolean;
  /**
   * Show the sparkline (default true); `false` also turns the trend off. A grouping (`{ date: "week" }`)
   * picks the sparkline's buckets instead of the span-based default.
   */
  sparkline?: boolean | GroupSpec;
  /** On a headline-only tile, show a badge comparing with the preceding equal-length period. */
  compare?: boolean | "previous";
  /** Caption of the comparison (default: the `widget.stat.comparison` message). */
  compareLabel?: string;
  /** Sparkline shape (default `area`). */
  type?: "line" | "area";
  /** Accent: palette tokens (`"chart-2"`, `"success"`) or CSS colors; the first is used. */
  colors?: string[];
  /** ISO 4217 code, e.g. `"USD"`. */
  currency?: string;
  /** A free unit label instead of a currency (`"kg"`, `"pcs"`). */
  unit?: string;
  /** Which side of the number the unit goes (default suffix). */
  unitPosition?: "prefix" | "suffix";
  /** Number style (default `integer` for `count`). */
  format?: "integer" | "decimal";
  /** BCP 47 locale for number formatting (default: the browser's). */
  locale?: string;
}

/**
 * A KPI tile with momentum: the headline figure of `measure` over the page's time range, the
 * change versus the previous equal-length period, and a sparkline of the series. `ratioTo` makes
 * it a percentage of a second measure; `compare` adds the period badge to a headline-only tile.
 */
export function Stat(props: StatProps) {
  return (
    <Boundary label={props.title || "Stat"}>
      {props.measure ? <StatBody {...props} measure={props.measure} /> : <StatValue {...props} />}
    </Boundary>
  );
}

/** A stat of a figure the page holds: the same tile, without requests. */
function StatValue(props: StatProps) {
  const value = props.value ?? null;
  const previous = props.previous ?? null;
  const delta = value != null && previous != null && previous !== 0 ? (value - previous) / Math.abs(previous) : null;
  const showTrend = props.trend !== false && props.sparkline !== false && (props.points?.length ?? 0) > 1;
  return (
    <StatCard
      props={props}
      headline={value}
      delta={delta}
      points={(props.points ?? []).map((v) => ({ value: Number.isFinite(v) ? v : 0 }))}
      showTrend={showTrend}
      showComparison={props.compare === true || props.compare === "previous" || (!showTrend && previous != null)}
      integer={value != null && Number.isInteger(value) && (props.points ?? []).every(Number.isInteger)}
    />
  );
}

function StatBody(props: StatProps & { measure: MeasureSpec }) {
  const { measure, ratioTo } = props;
  // A ratio is calculated from two independently scoped aggregates. It deliberately renders as a
  // headline-only tile: combining two bucket series into a meaningful trend needs a shared
  // grouping contract, while the KPI itself only needs the selected window's two totals.
  const showTrend = !ratioTo && props.trend !== false && props.sparkline !== false;
  const showComparison = props.compare === true || props.compare === "previous";
  const { range } = useTimeRange();
  const window = useMemo(() => resolveRange(range), [range]);
  const previous = useMemo(
    () => (showTrend || showComparison ? previousWindow(window) : null),
    [showTrend, showComparison, window]
  );
  const spanDays = useMemo(() => rangeSpanDays(range, 365), [range]);
  // A headline-only stat is one grand-total bucket — this matters for non-additive measures such
  // as avg/max (summing daily averages is not the overall average). Trend cards keep time buckets.
  const by = showTrend && typeof props.sparkline === "object" ? props.sparkline : undefined;
  const dateBucket = showTrend && !by ? granularityForSpan(spanDays) : undefined;

  const current = useMeasureSeries(measure, { by, dateBucket, window });
  const before = useMeasureSeries(previous ? measure : null, { by, dateBucket, window: previous });
  const ratio = useMeasureSeries(ratioTo ?? null, { window });
  const ratioBefore = useMeasureSeries(ratioTo && previous ? ratioTo : null, { window: previous });

  const headline = (value: number, denominator: number) =>
    ratioTo ? (denominator !== 0 ? (value / denominator) * 100 : 0) : value;
  const headlineValue = headline(current.series.total, ratio.series.total);
  const previousValue = headline(before.series.total, ratioBefore.series.total);
  const points = current.series.rows.map((r) => ({ value: Number(r.value) || 0 }));
  const delta = previous && previousValue !== 0 ? (headlineValue - previousValue) / previousValue : null;

  return (
    <StatCard
      props={props}
      headline={current.source ? headlineValue : null}
      delta={previous ? delta : null}
      points={points}
      showTrend={showTrend}
      showComparison={showComparison}
      integer={measure.aggregation === "count" && !ratioTo}
    />
  );
}

/** The stat tile itself, for both a measured and a given figure. */
function StatCard({ props, headline, delta, points, showTrend, showComparison, integer }: {
  props: StatProps;
  headline: number | null;
  delta: number | null;
  points: Array<{ value: number }>;
  showTrend: boolean;
  showComparison: boolean;
  integer: boolean;
}) {
  const t = useMessages();
  const { title, hint, compareLabel } = props;
  const color = resolveColor(props.colors?.join(","));
  const fmtNum = (n: number) => {
    const options = {
      currency: props.currency,
      unit: props.unit,
      unitPosition: props.unitPosition,
      format: props.format ?? (integer ? "integer" : undefined),
      locale: props.locale,
    } as const;
    // A trend card needs a compact headline to leave room for its delta and sparkline; a
    // headline-only KPI has the space to show the exact value.
    return showTrend ? formatCompact(n, options) : formatNumber(n, options);
  };

  const Arrow = delta == null || delta === 0 ? ArrowRight : delta > 0 ? ArrowUpRight : ArrowDownRight;
  const deltaClass =
    delta == null || delta === 0 ? "text-muted-foreground" : delta > 0 ? "text-success" : "text-destructive";

  return (
    <Card className="overflow-hidden">
      <CardContent
        className={cn(
          "p-4",
          !showTrend && "grid min-h-[128px] grid-rows-[auto_1fr] gap-2"
        )}
      >
        <div className="flex items-center gap-1.5">
          <span className="text-[13px] font-medium text-muted-foreground">{title}</span>
          <HintIcon text={hint} size={13} />
        </div>
        <div className={cn("mt-1 flex items-baseline gap-2", !showTrend && "mt-0 flex-col items-center justify-center gap-2 text-center")}>
          <div className="flex items-baseline gap-2">
            <AnimatedNumber
              value={headline != null ? fmtNum(headline) : "—"}
              className={cn("font-semibold leading-none tabular-nums", showTrend ? "text-[28px]" : "text-[46px] tracking-normal")}
            />
            {showTrend && delta != null && (
              <span className={`flex items-center gap-0.5 text-xs font-medium ${deltaClass}`}>
                <Arrow size={13} />
                {Math.abs(delta * 100).toFixed(1)}%
              </span>
            )}
          </div>
          {!showTrend && showComparison && delta != null && (
            <span className={`flex items-center gap-1 rounded-control border border-border/70 bg-muted/30 px-2 py-0.5 text-[11px] font-medium tabular-nums ${deltaClass}`}>
              <Arrow size={12} />
              {delta > 0 ? "+" : delta < 0 ? "−" : ""}{Math.abs(delta * 100).toFixed(1)}%
              <span className="text-muted-foreground">{compareLabel ?? t("widget.stat.comparison")}</span>
            </span>
          )}
        </div>
        {showTrend ? (
          <div className="mt-2.5">
            <TrendLine data={points} color={color} kind={props.type === "line" ? "line" : "area"} height={44} />
          </div>
        ) : null}
        {showTrend && delta != null && (
          <div className="mt-1 text-[11px] text-muted-foreground">
            {compareLabel ?? t("widget.stat.comparison")}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
