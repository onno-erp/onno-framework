import {
  cloneElement,
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
  Cell,
  ComposedChart,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ReferenceArea,
  Tooltip,
  XAxis,
  YAxis,
  type MouseHandlerDataParam,
} from "recharts";
import { format } from "date-fns";
import { formatCompact, formatNumber, toNumber } from "@/lib/format";
import { resolveColors } from "@/lib/chart-colors";
import { Segmented } from "@/components/ui/segmented";
import {
  applyScale,
  buildCombo,
  buildSeries,
  comboFromBuckets,
  filterWindow,
  ISO_KEY,
  SCALE_LABELS,
  seriesFromBuckets,
  SINGLE_SERIES,
  useWidgetBuckets,
  useWidgetRows,
  type AggregateBuckets,
  type ComboData,
  type GroupByDate,
  type Metric,
  type ScaleMode,
  type SeriesData,
} from "@/lib/widget-data";
import { presetsFromConfig, resolveRange } from "@/lib/time-range";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { useTimeRange } from "@/providers/time-range-provider";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { TimeRangeFacet } from "@/components/date-range-facet";
import { cn } from "@/lib/utils";
import { useMessages } from "@/providers/messages-provider";

interface ChartWidgetProps {
  widget: DashboardWidgetMeta;
}

// "pie" is an alias of "donut" with a zero inner radius (a filled circle).
type ChartKind = "bar" | "line" | "area" | "donut" | "pie";
const CHART_KINDS: ChartKind[] = ["bar", "line", "area", "donut", "pie"];

interface ChartConfig {
  groupBy: string;
  groupByDate?: GroupByDate;
  seriesBy?: string;
  metric: Metric;
  metricField?: string;
  kind: ChartKind;
  stacked: boolean;
  colors?: string;
  currency?: string;
  unit?: string;
  unitPosition?: string;
  format?: string;
  locale?: string;
  // Dual-axis (combo): a second measure on its own right-hand Y axis. Set via config("measure2", …).
  combo: boolean;
  secondaryMetric: Metric;
  secondaryField?: string;
  secondaryKind: ChartKind;
  primaryLabel: string;
  secondaryLabel: string;
}

function readConfig(widget: DashboardWidgetMeta): ChartConfig {
  const cfg = widget.extraConfig ?? {};
  const explicit = (cfg.kind as ChartKind | undefined) ?? "bar";
  let kind: ChartKind = explicit;
  if (!CHART_KINDS.includes(explicit)) {
    // Don't silently coerce an unknown kind — surface it, then fall back.
    console.warn(`[onno chart] unknown kind "${explicit}" for "${widget.title}"; falling back to "bar"`);
    kind = "bar";
  }
  const metric = (cfg.metric as Metric) ?? "count";
  const secondaryMetric = cfg.measure2 as Metric | undefined; // presence turns the chart into a combo
  return {
    groupBy: cfg.groupBy ?? "_date",
    groupByDate:
      (cfg.groupByDate as GroupByDate | undefined) ??
      (cfg.groupBy === "_date" || !cfg.groupBy ? "day" : undefined),
    seriesBy: cfg.seriesBy || undefined,
    metric,
    metricField: cfg.metricField,
    kind,
    stacked: cfg.stacked === "true",
    colors: cfg.colors,
    currency: cfg.currency,
    unit: cfg.unit,
    unitPosition: cfg.unitPosition,
    // Counts read better as integers on the axis/tooltip unless the author overrides.
    format: cfg.format ?? (metric === "count" ? "integer" : undefined),
    locale: cfg.locale,
    combo: !!secondaryMetric,
    secondaryMetric: secondaryMetric ?? "count",
    secondaryField: cfg.field2,
    secondaryKind: ((cfg.kind2 as ChartKind) ?? "bar"),
    primaryLabel: cfg.label ?? widget.title ?? "Primary",
    secondaryLabel: cfg.label2 ?? "Secondary",
  };
}

// ----- interactive controls -------------------------------------------------------------------

const TYPE_OPTIONS: ChartKind[] = ["bar", "line", "area"];
const TYPE_LABELS: Record<string, string> = { bar: "Bar", line: "Line", area: "Area" };
const SCALE_OPTIONS: ScaleMode[] = ["absolute", "indexed", "normalized"];

interface ControlsSpec {
  enabled: Set<string>;
  /** Series-split options for the "series" control: `{value, label}` ("" = the single unsplit series). */
  series: { value: string; label: string }[];
  defaultSeries: string;
}

/**
 * Which per-chart controls a chart exposes, from `config("controls", "granularity,type,scale,series")`
 * ("all" = granularity,type,scale). The date range is the <em>shared</em> dashboard time picker, not a
 * per-chart control. Charts that declare no controls render exactly as before. The explore view shows
 * every control regardless. `config("seriesOptions", "field:Label,…")` tunes the series control.
 */
function readControls(widget: DashboardWidgetMeta, base: ChartConfig): ControlsSpec {
  const cfg = widget.extraConfig ?? {};
  let raw = (cfg.controls ?? "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
  if (raw.includes("all")) raw = ["granularity", "type", "scale"];
  const enabled = new Set(raw);
  const series = (cfg.seriesOptions ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((tok) => {
      const i = tok.indexOf(":");
      const value = (i >= 0 ? tok.slice(0, i) : tok).trim();
      const label = (i >= 0 ? tok.slice(i + 1) : tok).trim();
      return { value: value === "none" ? "" : value, label };
    });
  const defaultSeries = base.seriesBy ?? series[0]?.value ?? "";
  return { enabled, series, defaultSeries };
}

/**
 * The register (rows) half of the chart data pipeline: window the rows (done by the caller),
 * bucket into a series (or a dual-axis combo), and rescale. Catalog/document charts build the
 * same shapes from server buckets instead — see {@link chartDataFromBuckets}. Both the dashboard
 * card and the explore view go through whichever half matches, so they show identical numbers.
 */
function buildChartData(
  config: ChartConfig,
  rows: EntityRecord[],
  opts: { granularity?: GroupByDate; kind: ChartKind; seriesBy?: string; scale: ScaleMode }
): { data: SeriesData; comboData: ComboData | null } {
  if (config.combo) {
    return {
      data: { rows: [], seriesKeys: [SINGLE_SERIES], total: 0 },
      comboData: buildCombo(rows, {
        groupBy: config.groupBy,
        groupByDate: opts.granularity,
        primary: { metric: config.metric, metricField: config.metricField },
        secondary: { metric: config.secondaryMetric, metricField: config.secondaryField },
      }),
    };
  }
  const round = opts.kind === "donut" || opts.kind === "pie";
  const built = buildSeries(rows, {
    groupBy: config.groupBy,
    groupByDate: opts.granularity,
    seriesBy: round ? undefined : opts.seriesBy,
    metric: config.metric,
    metricField: config.metricField,
  });
  return { data: applyScale(built, round ? "absolute" : opts.scale), comboData: null };
}

/**
 * The catalog/document half of the chart data pipeline (#199): shape the server's pre-aggregated
 * buckets into the same {@link SeriesData}/{@link ComboData} the row half builds, then rescale.
 * The grouping/series/measure already happened in SQL, so this is pure shaping — no re-bucketing.
 */
function chartDataFromBuckets(
  config: ChartConfig,
  resp: AggregateBuckets | null,
  opts: { granularity?: GroupByDate; kind: ChartKind; seriesBy?: string; scale: ScaleMode }
): { data: SeriesData; comboData: ComboData | null } {
  const buckets = resp ?? { buckets: [], truncated: false };
  if (config.combo) {
    return {
      data: { rows: [], seriesKeys: [SINGLE_SERIES], total: 0 },
      comboData: comboFromBuckets(buckets, { groupByDate: opts.granularity }),
    };
  }
  const round = opts.kind === "donut" || opts.kind === "pie";
  // A pie/donut has no axis: the server's zero-filled empty periods (#246) would only add
  // zero-value slices to the legend, so drop them here.
  const shaped = round
    ? { ...buckets, buckets: buckets.buckets.filter((b) => b.value !== 0 || (b.value2 ?? 0) !== 0) }
    : buckets;
  const built = seriesFromBuckets(shaped, {
    groupBy: config.groupBy,
    groupByDate: opts.granularity,
    seriesBy: round ? undefined : opts.seriesBy,
    metric: config.metric,
    metricField: config.metricField,
  });
  return { data: applyScale(built, round ? "absolute" : opts.scale), comboData: null };
}

interface DragProps {
  style: React.CSSProperties;
  onMouseDown: (e: MouseHandlerDataParam) => void;
  onMouseMove: (e: MouseHandlerDataParam) => void;
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
  const dragging = useRef(false);

  useEffect(() => {
    const isoAt = (idx: number) => String(rows[idx]?.[ISO_KEY] ?? "");
    const up = () => {
      if (!dragging.current) return;
      dragging.current = false;
      setSel((s) => {
        if (s.left != null && s.right != null && s.left !== s.right) {
          let a = isoAt(s.left);
          let b = isoAt(s.right);
          if (a && b) {
            if (a > b) [a, b] = [b, a];
            onZoom(a, b);
          }
        }
        return { left: null, right: null };
      });
    };
    window.addEventListener("mouseup", up);
    return () => window.removeEventListener("mouseup", up);
  }, [rows, onZoom]);

  const idxOf = (e: MouseHandlerDataParam | undefined | null) => {
    const i = e?.activeTooltipIndex;
    return typeof i === "number" && i >= 0 && i < rows.length ? i : null;
  };
  const dragProps: DragProps = {
    // crosshair signals the region-select affordance; userSelect:none stops the drag from
    // highlighting axis labels mid-gesture.
    style: { cursor: "crosshair", userSelect: "none" },
    onMouseDown: (e) => {
      const i = idxOf(e);
      if (i != null) {
        dragging.current = true;
        setSel({ left: i, right: i });
      }
    },
    onMouseMove: (e) => {
      const i = idxOf(e);
      if (i != null && dragging.current) setSel((s) => (s.left != null ? { ...s, right: i } : s));
    },
  };
  // ReferenceArea still addresses the category axis by label — indexes resolve to the (now
  // unambiguous) labels only at render time.
  const refArea =
    sel.left != null && sel.right != null && sel.left !== sel.right
      ? { x1: String(rows[sel.left]?.label ?? ""), x2: String(rows[sel.right]?.label ?? "") }
      : null;
  return { dragProps, refArea };
}

/** Min buckets a chart should show before stepping up to a coarser (week/month) granularity. */
const MIN_POINTS = 10;

/**
 * Pick the bucket size for a span (in days) of the data actually in the window: the COARSEST
 * granularity that still yields at least {@link MIN_POINTS} bars. Sizing off the data present (not
 * the nominal window length) keeps a wide range over sparse data readable — a 1-year range over
 * ~3 months of data shows weekly bars, not 3 monthly ones. Below week, "day" is the floor for any
 * multi-day span regardless of {@link MIN_POINTS} — a 4-day window must show 4 daily bars, not
 * 96 hourly slivers; hour/minute apply only once the span itself is sub-day-scale.
 */
function granularityForSpan(days: number): GroupByDate {
  if (days / 30 >= MIN_POINTS) return "month";
  if (days / 7 >= MIN_POINTS) return "week";
  if (days >= 2) return "day";
  if (days * 24 >= 2) return "hour";
  return "minute";
}

// Existing dashboards author config("groupBy", "status_display") because the old row path read the
// ref-resolved display column off each row. The aggregate endpoint groups by the REAL column and
// resolves labels itself, so the suffix is stripped from the request only (configs stay authored as-is).
const stripDisplay = (column: string) => column.replace(/_display$/, "");

/** Epoch millis → the ISO local-datetime the aggregate endpoint's window expects ("2026-01-01T00:00:00"). */
const toLocalIso = (ms: number) => format(new Date(ms), "yyyy-MM-dd'T'HH:mm:ss");

/** The span (in days) covered by a chart's rows on its date field — for sizing "all"-range buckets. */
function dataSpanDays(rows: EntityRecord[], dateField: string): number {
  let min = Infinity;
  let max = -Infinity;
  for (const r of rows) {
    const v = r[dateField];
    if (typeof v !== "string") continue;
    const t = Date.parse(v);
    if (Number.isNaN(t)) continue;
    if (t < min) min = t;
    if (t > max) max = t;
  }
  return max > min ? (max - min) / 86_400_000 + 1 : 1;
}

export function ChartWidget({ widget }: ChartWidgetProps) {
  const t = useMessages();
  const config = useMemo(() => readConfig(widget), [widget]);
  const controls = useMemo(() => readControls(widget, config), [widget, config]);
  const { range, setAbsolute } = useTimeRange(); // the shared dashboard time window
  // Catalog/document charts fetch pre-aggregated buckets (#199); registers keep fetching turnover
  // rows and bucketing client-side (no aggregate endpoint exists for them).
  const isRegister = widget.entityType === "register";
  const isDocument = widget.entityType === "document";
  const windowField = widget.dateField || (isRegister ? "_period" : "_date");

  // The absolute window, resolved once per range change: resolveRange anchors a relative range to
  // "now", so resolving inline every render would shift the fetch params and never let them settle.
  const windowRange = useMemo(() => resolveRange(range), [range]);
  const registerTurnoverRange = useMemo(
    () =>
      windowRange.from === -Infinity && windowRange.to === Infinity
        ? undefined
        : {
            from: toLocalIso(windowRange.from === -Infinity ? Date.parse("1970-01-01T00:00:00") : windowRange.from),
            to: toLocalIso(windowRange.to === Infinity ? Date.parse("2999-12-31T23:59:59") : windowRange.to),
          },
    [windowRange]
  );

  // Register rows path. Buckets replace the row fetch for catalog/document, so hand useWidgetRows
  // an entityType it ignores — the hook must still be called (rules of hooks), just never fetch.
  const rowsWidget = useMemo(() => (isRegister ? widget : { ...widget, entityType: "" }), [isRegister, widget]);
  const items = useWidgetRows(rowsWidget, registerTurnoverRange);
  // The shared range windows the rows by the document's date BEFORE bucketing — so it applies to
  // every chart (a status pie filters to in-range orders too), not just time series.
  const ranged = useMemo(
    () => (isRegister ? items : filterWindow(items, windowField, range)),
    [isRegister, items, windowField, range]
  );

  // Granularity auto-follows the data actually inside the window (see the span effect below), so a
  // range change re-applies it and the user can still override until the next change.
  const [granularity, setGranularity] = useState<GroupByDate>(config.groupByDate ?? "day");

  // Local control state, seeded from the authored config; applied only when its control is enabled.
  const [kind, setKind] = useState<ChartKind>(config.kind);
  const [scale, setScale] = useState<ScaleMode>("absolute");
  const [seriesBy, setSeriesBy] = useState<string>(controls.defaultSeries);
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
  // chart (groupByDate set) always uses the auto/overridden granularity.
  const effKind = controls.enabled.has("type") ? kind : config.kind;
  const effGranularity = config.groupByDate != null ? granularity : config.groupByDate;
  const effSeriesBy = controls.enabled.has("series") ? seriesBy || undefined : config.seriesBy;
  const effScale: ScaleMode = controls.enabled.has("scale") ? scale : "absolute";
  // Matches what the data pipeline computes for itself (a combo is never round).
  const round = !config.combo && (effKind === "donut" || effKind === "pie");

  // The aggregate request for a catalog/document chart (null for a register — no fetch). Everything
  // the old row pipeline did client-side — filter, window, group, series-split, measure — travels as
  // query params and comes back as ready buckets. A control change lands here, so it refetches.
  const aggParams = useMemo(() => {
    if (isRegister) return null;
    const p: Record<string, string> = { metric: config.metric };
    if (config.metricField) p.field = config.metricField;
    if (config.combo) {
      p.metric2 = config.secondaryMetric;
      if (config.secondaryField) p.field2 = config.secondaryField;
    }
    // Catalogs have no _date system column (documents do), so the "_date" defaults degrade instead
    // of failing the query: an un-grouped catalog chart takes the endpoint's single grand-total
    // bucket, and the window/span ride only on an author-named date field.
    const groupBy = stripDisplay(config.groupBy);
    if (isDocument || groupBy !== "_date") {
      p.groupBy = groupBy;
      if (effGranularity) p.groupByDate = effGranularity;
    }
    // Pies/donuts don't series-split; a combo's second dimension is its second measure.
    const split = round || config.combo ? undefined : effSeriesBy;
    if (split) p.seriesBy = stripDisplay(split);
    const filter = widget.extraConfig?.filter;
    if (filter) p.filter = filter;
    if (isDocument || windowField !== "_date") {
      // Send the date field even for an unbounded window — the auto-granularity needs `span` back.
      p.dateField = windowField;
      if (windowRange.from !== -Infinity) p.from = toLocalIso(windowRange.from);
      if (windowRange.to !== Infinity) p.to = toLocalIso(windowRange.to);
    }
    return p;
  }, [isRegister, isDocument, config, round, effGranularity, effSeriesBy, widget, windowField, windowRange]);
  const bucketResp = useWidgetBuckets(widget, aggParams);

  // Granularity auto-follows the span of the data actually inside the window (not the nominal range
  // length): the register path measures its rows, the bucket path reads the response's `span`. A
  // granularity change is itself a fetch param, so the first response at the seeded size may correct
  // itself with one follow-up fetch — expected, and it converges.
  const spanDays = useMemo(() => {
    if (isRegister) return dataSpanDays(ranged, windowField);
    const span = bucketResp?.span;
    return span ? (Date.parse(span.max) - Date.parse(span.min)) / 86_400_000 + 1 : 1;
  }, [isRegister, ranged, windowField, bucketResp]);
  const autoGran = useMemo(() => granularityForSpan(spanDays), [spanDays]);
  useEffect(() => setGranularity(autoGran), [autoGran]);

  const fmts = useChartFormatters(config);
  const { fmt, fmtAxis } = fmts;

  const { data, comboData } = useMemo(
    () =>
      isRegister
        ? buildChartData(config, ranged, { granularity: effGranularity, kind: effKind, seriesBy: effSeriesBy, scale: effScale })
        : chartDataFromBuckets(config, bucketResp, { granularity: effGranularity, kind: effKind, seriesBy: effSeriesBy, scale: effScale }),
    [isRegister, config, ranged, bucketResp, effGranularity, effKind, effSeriesBy, effScale]
  );

  const colors = useMemo(
    () => resolveColors(round ? data.rows.length : data.seriesKeys.length, config.colors),
    [round, data, config.colors]
  );
  const comboColors = useMemo(() => resolveColors(2, config.colors), [config.colors]);

  // A unique gradient-id prefix per widget instance — SVG ids are document-global, so two area
  // charts on one page would otherwise share (and clobber) the same <linearGradient>.
  const gradientPrefix = `chart-${useId().replace(/:/g, "")}`;

  // Once rescaled the grand total mixes units / is relative — hide the header figure then. A combo
  // chart has two measures, so no single grand total applies either.
  const showTotal = effScale === "absolute" && data.rows.length > 0 && !config.combo;

  // Drag-select a region to set the shared absolute range (Grafana-style zoom).
  const zoomRows = config.combo ? comboData?.rows ?? [] : data.rows;
  const { dragProps, refArea } = useDragZoom(zoomRows, setAbsolute);

  // Granularity is automatic on the card (it follows the shared range) — no button. A manual
  // override lives in the explore view. Series / type / scale don't apply to a dual-axis combo.
  const controlNodes: React.ReactNode[] = [];
  if (controls.enabled.has("series") && controls.series.length > 0 && !config.combo)
    controlNodes.push(<Segmented key="series" size="sm" value={seriesBy} onChange={setSeriesBy} options={controls.series} />);
  if (controls.enabled.has("type") && !config.combo)
    controlNodes.push(
      <Segmented key="type" size="sm" value={kind} onChange={setKind} options={TYPE_OPTIONS.map((k) => ({ value: k, label: TYPE_LABELS[k] }))} />
    );
  if (controls.enabled.has("scale") && !round && !config.combo)
    controlNodes.push(
      <Segmented key="scale" size="sm" value={scale} onChange={setScale} options={SCALE_OPTIONS.map((s) => ({ value: s, label: SCALE_LABELS[s] }))} />
    );

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-start justify-between gap-3 space-y-0 p-4 pb-1">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
          <HintIcon text={widget.hint} size={13} />
        </div>
        <div className="flex items-center gap-1.5">
          {/* The controls live where the grand-total figure would be; a chart with no controls keeps
              the figure there instead. */}
          {controlNodes.length > 0 ? (
            <div className="flex flex-wrap items-center justify-end gap-1.5">{controlNodes}</div>
          ) : showTotal ? (
            <span className="text-[13px] font-semibold tabular-nums text-foreground">{fmt(data.total)}</span>
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="p-4 pt-2">
        {(config.combo ? (comboData?.rows.length ?? 0) === 0 : data.rows.length === 0) ? (
          <div className="flex h-[210px] items-center justify-center text-xs text-muted-foreground">{t("empty.noData")}</div>
        ) : (
          <ResponsiveChart height={round && !config.combo ? 230 : 210}>
            {config.combo && comboData
              ? renderCombo(comboData, config, fmt, fmtAxis, fmts.fmtSecondary, fmts.fmtSecondaryAxis, comboColors, gradientPrefix, dragProps, refArea)
              : renderChart(effKind, data, colors, fmt, fmtAxis, config.stacked, gradientPrefix, hidden, toggleSeries, config.primaryLabel, dragProps, refArea)}
          </ResponsiveChart>
        )}
      </CardContent>
    </Card>
  );
}

interface ChartFormatters {
  fmt: (n: number) => string;
  fmtAxis: (n: number) => string;
  fmtSecondary: (n: number) => string;
  fmtSecondaryAxis: (n: number) => string;
}

/** Primary (full + compact) and secondary (integer) number formatters for a chart's config. */
function useChartFormatters(config: ChartConfig): ChartFormatters {
  const numberOpts = useMemo(
    () => ({ currency: config.currency, unit: config.unit, unitPosition: config.unitPosition, format: config.format, locale: config.locale }),
    [config]
  );
  const secondaryOpts = useMemo(() => ({ format: "integer" }), []);
  return useMemo(
    () => ({
      // Full figures in the tooltip/header; compact on the axis, where a full currency value clips.
      fmt: (n: number) => formatNumber(n, numberOpts),
      fmtAxis: (n: number) => formatCompact(n, numberOpts),
      fmtSecondary: (n: number) => formatNumber(n, secondaryOpts),
      fmtSecondaryAxis: (n: number) => formatCompact(n, secondaryOpts),
    }),
    [numberOpts, secondaryOpts]
  );
}

// recharts paints the Legend wrapper after the Tooltip wrapper, so without this the hover tooltip
// stacks *under* the legend. Lift it above.
const TOOLTIP_WRAPPER = { zIndex: 50, outline: "none" } as const;

interface TooltipItem {
  name?: string | number;
  value?: unknown;
  color?: string;
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
  format: (value: unknown, name: unknown) => string;
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
                {format(item.value, item.name)}
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
 * intermittently collapses to an 8×8 surface for charts that carry a Legend when mounted inside our
 * DivKit portals — it reads a 0 size before layout settles and never recovers. Measuring ourselves is
 * reliable, and skipping its internal observer is marginally cheaper.
 */
function ResponsiveChart({ height, children }: { height: number; children: ReactElement }) {
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
  kind: ChartKind,
  data: SeriesData,
  colors: string[],
  fmt: (n: number) => string,
  fmtAxis: (n: number) => string,
  stacked: boolean,
  gradientPrefix: string,
  hidden: Set<string>,
  onToggle: (key: string) => void,
  seriesName: string,
  dragProps?: DragProps,
  refArea?: RefAreaSel | null
): React.ReactElement {
  const tooltipValue = (value: unknown) => fmt(toNumber(value) ?? 0);
  const { seriesKeys } = data;
  const multi = seriesKeys.length > 1 || seriesKeys[0] !== SINGLE_SERIES;
  const axis = { stroke: "hsl(var(--muted-foreground))", fontSize: 11, tickLine: false, axisLine: false } as const;

  // Clicking a legend item toggles its series off/on. We pass an explicit, stable `payload` so a
  // hidden series keeps its (greyed, `inactive`) legend entry to click back on — relying on a child's
  // `hide` alone would drop it from the auto-legend and there'd be nothing left to click.
  const onLegendClick = (e: { dataKey?: unknown }) => {
    const key = String(e?.dataKey ?? "");
    if (key) onToggle(key);
  };
  // recharts keeps a hidden series in the auto-legend (the canonical toggle pattern), so we just
  // click-to-toggle and grey the hidden ones via the formatter.
  const seriesLegend = multi ? (
    <Legend
      wrapperStyle={{ fontSize: 11, paddingTop: 6, cursor: "pointer" }}
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
    const pieRows = data.rows.map((r) =>
      hidden.has(String(r.label)) ? { ...r, [SINGLE_SERIES]: 0 } : r
    );
    // Slice tooltips summarize value + share of the visible ring (hidden slices excluded).
    const pieTotal = pieRows.reduce((s, r) => s + (toNumber(r[SINGLE_SERIES]) ?? 0), 0);
    const pieValue = (value: unknown) => {
      const n = toNumber(value) ?? 0;
      return pieTotal > 0 ? `${fmt(n)} · ${Math.round((n / pieTotal) * 100)}%` : fmt(n);
    };
    return (
      <PieChart>
        <Tooltip wrapperStyle={TOOLTIP_WRAPPER} content={<ChartTooltipContent format={pieValue} />} />
        <Legend
          wrapperStyle={{ fontSize: 11, cursor: "pointer" }}
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
        />
        <Pie
          data={pieRows}
          dataKey={SINGLE_SERIES}
          nameKey="label"
          innerRadius={kind === "pie" ? 0 : 52}
          outerRadius={82}
          paddingAngle={kind === "pie" ? 0 : 2}
          stroke="hsl(var(--card))"
          strokeWidth={2}
          // recharts' Pie enter-animation is fragile — a re-render during it (e.g. a width change)
          // can drop every sector and never restore them. The slices matter more than the flourish.
          isAnimationActive={false}
        >
          {pieRows.map((_, i) => (
            <Cell key={i} fill={colors[i % colors.length]} />
          ))}
        </Pie>
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
                <stop offset="5%" stopColor={colors[i]} stopOpacity={0.3} />
                <stop offset="95%" stopColor={colors[i]} stopOpacity={0.02} />
              </linearGradient>
            ))}
          </defs>
        )}
        <CartesianGrid stroke="hsl(var(--border))" strokeOpacity={0.6} strokeDasharray="3 3" />
        <XAxis dataKey="label" {...axis} />
        <YAxis {...axis} width={40} tickFormatter={fmtAxis} />
        <Tooltip wrapperStyle={TOOLTIP_WRAPPER} cursor={{ stroke: "hsl(var(--border))" }} content={<ChartTooltipContent format={tooltipValue} fallbackName={seriesName} />} />
        {seriesLegend}
        {seriesKeys.map((key, i) =>
          kind === "line" ? (
            <Line
              key={key}
              type="monotone"
              dataKey={key}
              name={key === SINGLE_SERIES ? undefined : key}
              stroke={colors[i]}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4 }}
              hide={hidden.has(key)}
            />
          ) : (
            <Area
              key={key}
              type="monotone"
              dataKey={key}
              name={key === SINGLE_SERIES ? undefined : key}
              stroke={colors[i]}
              strokeWidth={2}
              fill={`url(#${gradientPrefix}-${i})`}
              stackId={stacked ? "stack" : undefined}
              dot={false}
              hide={hidden.has(key)}
            />
          )
        )}
      </ChartImpl>
    );
  }

  return (
    <BarChart data={data.rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }} {...dragProps}>
      {refArea && <ReferenceArea x1={refArea.x1} x2={refArea.x2} strokeOpacity={0} fill="hsl(var(--primary))" fillOpacity={0.12} />}
      <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
      <XAxis dataKey="label" {...axis} />
      <YAxis {...axis} width={52} tickFormatter={fmtAxis} />
      <Tooltip wrapperStyle={TOOLTIP_WRAPPER} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} content={<ChartTooltipContent format={tooltipValue} fallbackName={seriesName} />} />
      {seriesLegend}
      {seriesKeys.map((key, i) => {
        // Round only the exposed top: the last series in a stack, or every bar when not stacked.
        const top = !stacked || i === seriesKeys.length - 1;
        return (
          <Bar
            key={key}
            dataKey={key}
            name={key === SINGLE_SERIES ? undefined : key}
            fill={colors[i]}
            stackId={stacked ? "stack" : undefined}
            radius={top ? [3, 3, 0, 0] : [0, 0, 0, 0]}
            maxBarSize={48}
            hide={hidden.has(key)}
          />
        );
      })}
    </BarChart>
  );
}

// A dual-axis combo chart: a primary measure on the left axis and a secondary on the right, each
// with its own kind (bar/line/area), so two very different magnitudes (e.g. revenue vs order count)
// read cleanly on one chart. Bars render behind lines/areas.
function renderCombo(
  data: ComboData,
  config: ChartConfig,
  fmtPrimary: (n: number) => string,
  fmtPrimaryAxis: (n: number) => string,
  fmtSecondary: (n: number) => string,
  fmtSecondaryAxis: (n: number) => string,
  colors: string[],
  gradientPrefix: string,
  dragProps?: DragProps,
  refArea?: RefAreaSel | null
): React.ReactElement {
  const axis = { stroke: "hsl(var(--muted-foreground))", fontSize: 11, tickLine: false, axisLine: false } as const;
  const [pColor, sColor] = colors;
  const measures = [
    { id: "left", key: "primary", kind: config.kind, color: pColor, name: config.primaryLabel },
    { id: "right", key: "secondary", kind: config.secondaryKind, color: sColor, name: config.secondaryLabel },
  ];
  // Bars behind lines/areas so a line isn't hidden by the other measure's bars.
  const ordered = [...measures].sort((a, b) => (a.kind === "bar" ? 0 : 1) - (b.kind === "bar" ? 0 : 1));
  const renderMeasure = (m: (typeof measures)[number]) => {
    if (m.kind === "bar") {
      return <Bar key={m.key} yAxisId={m.id} dataKey={m.key} name={m.name} fill={m.color} radius={[3, 3, 0, 0]} maxBarSize={36} />;
    }
    if (m.kind === "area") {
      return (
        <Area key={m.key} yAxisId={m.id} type="monotone" dataKey={m.key} name={m.name} stroke={m.color} strokeWidth={2}
          fill={`url(#${gradientPrefix}-combo-${m.key})`} dot={false} />
      );
    }
    return <Line key={m.key} yAxisId={m.id} type="monotone" dataKey={m.key} name={m.name} stroke={m.color} strokeWidth={2} dot={false} activeDot={{ r: 4 }} />;
  };
  const tooltipValue = (value: unknown, name: unknown) =>
    name === config.secondaryLabel ? fmtSecondary(toNumber(value) ?? 0) : fmtPrimary(toNumber(value) ?? 0);
  return (
    <ComposedChart data={data.rows} margin={{ top: 4, right: 4, left: 0, bottom: 0 }} {...dragProps}>
      {refArea && <ReferenceArea yAxisId="left" x1={refArea.x1} x2={refArea.x2} strokeOpacity={0} fill="hsl(var(--primary))" fillOpacity={0.12} />}
      <defs>
        <linearGradient id={`${gradientPrefix}-combo-primary`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="5%" stopColor={pColor} stopOpacity={0.3} />
          <stop offset="95%" stopColor={pColor} stopOpacity={0.02} />
        </linearGradient>
        <linearGradient id={`${gradientPrefix}-combo-secondary`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="5%" stopColor={sColor} stopOpacity={0.3} />
          <stop offset="95%" stopColor={sColor} stopOpacity={0.02} />
        </linearGradient>
      </defs>
      <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
      <XAxis dataKey="label" {...axis} />
      <YAxis yAxisId="left" {...axis} width={40} tickFormatter={fmtPrimaryAxis} />
      <YAxis yAxisId="right" orientation="right" {...axis} width={36} tickFormatter={fmtSecondaryAxis} />
      <Tooltip wrapperStyle={TOOLTIP_WRAPPER} cursor={{ fill: "hsl(var(--accent) / 0.2)" }} content={<ChartTooltipContent format={tooltipValue} />} />
      <Legend wrapperStyle={{ fontSize: 11, paddingTop: 6 }} iconType="circle" iconSize={8} />
      {ordered.map(renderMeasure)}
    </ComposedChart>
  );
}

// ----- shared time range + explore view -------------------------------------------------------

/**
 * The shared dashboard time picker (widget type "timeRange"): the {@link TimeRangeFacet} chip —
 * the same filter-chip family as the list toolbar. The server folds it into the page header's
 * title row on desktop (see PageDivBuilder), so it renders right-aligned beside the title; on
 * mobile it keeps its own full-width row. Authors tune it per dashboard:
 * {@code config("presets", "15m,1h,24h,7d,30d")} (a comma-separated list of duration ids — any
 * `<n><unit>`, plus `all`) and {@code config("default", "30d")}.
 */
export function TimeRangeWidget({ widget }: ChartWidgetProps) {
  const t = useMessages();
  const { range, presets, setPreset, setAbsolute, configure } = useTimeRange();
  const presetsCsv = widget.extraConfig?.presets;
  const defaultId = widget.extraConfig?.default;
  useEffect(() => {
    configure({ presets: presetsFromConfig(presetsCsv), defaultRangeId: defaultId });
  }, [configure, presetsCsv, defaultId]);
  return (
    <div className="flex items-center justify-end">
      <TimeRangeFacet
        label={t("timeRange.dateRange")}
        presets={presets}
        range={range}
        onPreset={setPreset}
        onAbsolute={setAbsolute}
      />
    </div>
  );
}
