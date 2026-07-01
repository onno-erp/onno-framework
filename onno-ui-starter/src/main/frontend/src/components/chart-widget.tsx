import {
  cloneElement,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { CalendarRange, Maximize2, X } from "lucide-react";
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
} from "recharts";
import { formatCompact, formatNumber, toNumber } from "@/lib/format";
import { resolveColors } from "@/lib/chart-colors";
import {
  applyScale,
  buildCombo,
  buildSeries,
  filterWindow,
  ISO_KEY,
  SCALE_LABELS,
  SINGLE_SERIES,
  useWidgetRows,
  type ComboData,
  type GroupByDate,
  type Metric,
  type ScaleMode,
  type SeriesData,
} from "@/lib/widget-data";
import { presetsFromConfig, sameRange } from "@/lib/time-range";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { useTimeRange } from "@/providers/time-range-provider";
import { parseDate } from "@internationalized/date";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { DateRangeInput } from "@/components/ui/date-input";
import { cn } from "@/lib/utils";

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

const GRANULARITY_OPTIONS: GroupByDate[] = ["minute", "hour", "day", "week", "month"];
const GRANULARITY_LABELS: Record<GroupByDate, string> = {
  minute: "Min",
  hour: "Hour",
  day: "Day",
  week: "Week",
  month: "Month",
};
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
 * The shared chart data pipeline used by both the dashboard card and the explore view: window the
 * rows (done by the caller), bucket into a series (or a dual-axis combo), and rescale. Centralizing
 * it keeps the card and the explore view showing exactly the same numbers.
 */
function buildChartData(
  config: ChartConfig,
  rows: EntityRecord[],
  opts: { granularity?: GroupByDate; kind: ChartKind; seriesBy?: string; scale: ScaleMode }
): { data: SeriesData; comboData: ComboData | null; round: boolean } {
  if (config.combo) {
    return {
      data: { rows: [], seriesKeys: [SINGLE_SERIES], total: 0 },
      comboData: buildCombo(rows, {
        groupBy: config.groupBy,
        groupByDate: opts.granularity,
        primary: { metric: config.metric, metricField: config.metricField },
        secondary: { metric: config.secondaryMetric, metricField: config.secondaryField },
      }),
      round: false,
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
  return { data: applyScale(built, round ? "absolute" : opts.scale), comboData: null, round };
}

interface DragProps {
  style: React.CSSProperties;
  onMouseDown: (e: { activeLabel?: string | number } | null) => void;
  onMouseMove: (e: { activeLabel?: string | number } | null) => void;
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
  const [sel, setSel] = useState<{ left: string | null; right: string | null }>({ left: null, right: null });
  const dragging = useRef(false);

  useEffect(() => {
    const isoFor = (label: string) => {
      const r = rows.find((row) => String(row.label) === label);
      return r ? String(r[ISO_KEY] ?? "") : "";
    };
    const up = () => {
      if (!dragging.current) return;
      dragging.current = false;
      setSel((s) => {
        if (s.left && s.right && s.left !== s.right) {
          let a = isoFor(s.left);
          let b = isoFor(s.right);
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

  const dragProps: DragProps = {
    // crosshair signals the region-select affordance; userSelect:none stops the drag from
    // highlighting axis labels mid-gesture.
    style: { cursor: "crosshair", userSelect: "none" },
    onMouseDown: (e) => {
      const l = e?.activeLabel;
      if (l != null) {
        dragging.current = true;
        setSel({ left: String(l), right: String(l) });
      }
    },
    onMouseMove: (e) => {
      const l = e?.activeLabel;
      if (l != null && dragging.current) setSel((s) => (s.left ? { ...s, right: String(l) } : s));
    },
  };
  const refArea = sel.left && sel.right && sel.left !== sel.right ? { x1: sel.left, x2: sel.right } : null;
  return { dragProps, refArea };
}

/** Min buckets a chart should show before stepping up to a coarser granularity. */
const MIN_POINTS = 10;

/**
 * Pick the bucket size for a span (in days) of the data actually in the window: the COARSEST
 * granularity that still yields at least {@link MIN_POINTS} bars, stepping down to hour/minute for
 * sub-day spans. Sizing off the data present (not the nominal window length) keeps a wide range over
 * sparse data readable — a 1-year range over ~3 months of data shows weekly bars, not 3 monthly ones.
 */
function granularityForSpan(days: number): GroupByDate {
  if (days / 30 >= MIN_POINTS) return "month";
  if (days / 7 >= MIN_POINTS) return "week";
  if (days >= MIN_POINTS) return "day";
  if (days * 24 >= MIN_POINTS) return "hour";
  return "minute";
}

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

/** A segmented toggle for the control rows. `size="md"` is the chunkier, tactile time-range variant. */
function Segmented<T extends string>({
  value,
  options,
  onChange,
  size = "sm",
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  size?: "sm" | "md";
}) {
  const md = size === "md";
  return (
    <div
      className={cn(
        "inline-flex items-center rounded-lg border border-border bg-muted/50 shadow-inner",
        md ? "gap-0.5 p-1" : "p-0.5"
      )}
    >
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={cn(
            "font-medium leading-none transition-all duration-150 active:scale-[0.96]",
            md ? "rounded-md px-3 py-1.5 text-[13px]" : "rounded px-1.5 py-0.5 text-[11px]",
            value === o.value
              ? "bg-background text-foreground shadow-sm ring-1 ring-border/60"
              : "text-muted-foreground hover:bg-background/50 hover:text-foreground"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function ChartWidget({ widget }: ChartWidgetProps) {
  const config = useMemo(() => readConfig(widget), [widget]);
  const controls = useMemo(() => readControls(widget, config), [widget, config]);
  const { range, setAbsolute } = useTimeRange(); // the shared dashboard time window
  const items = useWidgetRows(widget);
  // The shared range windows the rows by the document's date BEFORE bucketing — so it applies to
  // every chart (a status pie filters to in-range orders too), not just time series.
  const windowField = widget.dateField || "_date";
  const ranged = useMemo(() => filterWindow(items, windowField, range), [items, windowField, range]);

  // Granularity auto-follows the data actually inside the window (not the nominal range length), so a
  // range change re-applies it and the user can still override until the next change.
  const spanDays = useMemo(() => dataSpanDays(ranged, windowField), [ranged, windowField]);
  const autoGran = useMemo(() => granularityForSpan(spanDays), [spanDays]);
  const [granularity, setGranularity] = useState<GroupByDate>(autoGran);
  useEffect(() => setGranularity(autoGran), [autoGran]);

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
  const [explore, setExplore] = useState(false);

  // Effective settings: control state when that control is on, else the authored config. A time
  // chart (groupByDate set) always uses the auto/overridden granularity.
  const effKind = controls.enabled.has("type") ? kind : config.kind;
  const effGranularity = config.groupByDate != null ? granularity : config.groupByDate;
  const effSeriesBy = controls.enabled.has("series") ? seriesBy || undefined : config.seriesBy;
  const effScale: ScaleMode = controls.enabled.has("scale") ? scale : "absolute";

  const fmts = useChartFormatters(config);
  const { fmt, fmtAxis } = fmts;

  const { data, comboData, round } = useMemo(
    () => buildChartData(config, ranged, { granularity: effGranularity, kind: effKind, seriesBy: effSeriesBy, scale: effScale }),
    [config, ranged, effGranularity, effKind, effSeriesBy, effScale]
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
    controlNodes.push(<Segmented key="series" value={seriesBy} onChange={setSeriesBy} options={controls.series} />);
  if (controls.enabled.has("type") && !config.combo)
    controlNodes.push(
      <Segmented key="type" value={kind} onChange={setKind} options={TYPE_OPTIONS.map((k) => ({ value: k, label: TYPE_LABELS[k] }))} />
    );
  if (controls.enabled.has("scale") && !round && !config.combo)
    controlNodes.push(
      <Segmented key="scale" value={scale} onChange={setScale} options={SCALE_OPTIONS.map((s) => ({ value: s, label: SCALE_LABELS[s] }))} />
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
          <button
            type="button"
            onClick={() => setExplore(true)}
            title="Explore"
            aria-label="Explore"
            className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <Maximize2 size={13} />
          </button>
        </div>
      </CardHeader>
      <CardContent className="p-4 pt-2">
        {(config.combo ? (comboData?.rows.length ?? 0) === 0 : data.rows.length === 0) ? (
          <div className="flex h-[210px] items-center justify-center text-xs text-muted-foreground">No data yet</div>
        ) : (
          <ResponsiveChart height={round && !config.combo ? 230 : 210}>
            {config.combo && comboData
              ? renderCombo(comboData, config, fmt, fmtAxis, fmts.fmtSecondary, fmts.fmtSecondaryAxis, comboColors, gradientPrefix, dragProps, refArea)
              : renderChart(effKind, data, colors, fmt, fmtAxis, config.stacked, gradientPrefix, hidden, toggleSeries, dragProps, refArea)}
          </ResponsiveChart>
        )}
      </CardContent>
      {explore && <ExploreModal widget={widget} config={config} controls={controls} items={items} onClose={() => setExplore(false)} />}
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

const tooltipStyle = {
  background: "hsl(var(--popover))",
  border: "1px solid hsl(var(--border))",
  borderRadius: 6,
  fontSize: 12,
  color: "hsl(var(--popover-foreground))",
};

// recharts paints the Legend wrapper after the Tooltip wrapper, so without this the hover tooltip
// stacks *under* the legend. Lift it above.
const TOOLTIP_WRAPPER = { zIndex: 50 } as const;

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
  dragProps?: DragProps,
  refArea?: RefAreaSel | null
): React.ReactElement {
  // Recharts' Formatter passes a wide value type; coerce and render a single string. Blank the
  // name for the single-series sentinel so its tooltip shows just the value, not "value: …".
  const tooltipFormatter = (value: unknown, name: unknown) => [
    fmt(toNumber(value) ?? 0),
    name === SINGLE_SERIES ? "" : (name as string),
  ];
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
    return (
      <PieChart>
        <Tooltip wrapperStyle={TOOLTIP_WRAPPER} contentStyle={tooltipStyle} itemStyle={{ color: "hsl(var(--popover-foreground))" }} formatter={(v: unknown) => fmt(toNumber(v) ?? 0)} />
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
        <Tooltip wrapperStyle={TOOLTIP_WRAPPER} contentStyle={tooltipStyle} cursor={{ stroke: "hsl(var(--border))" }} formatter={tooltipFormatter} />
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
      <Tooltip wrapperStyle={TOOLTIP_WRAPPER} contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} formatter={tooltipFormatter} />
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
  const tooltipFormatter = (value: unknown, name: unknown) => [
    name === config.secondaryLabel ? fmtSecondary(toNumber(value) ?? 0) : fmtPrimary(toNumber(value) ?? 0),
    name as string,
  ];
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
      <Tooltip wrapperStyle={TOOLTIP_WRAPPER} contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.2)" }} formatter={tooltipFormatter} />
      <Legend wrapperStyle={{ fontSize: 11, paddingTop: 6 }} iconType="circle" iconSize={8} />
      {ordered.map(renderMeasure)}
    </ComposedChart>
  );
}

// ----- shared time range + explore view -------------------------------------------------------

/** A from/to bound the date-only custom picker can display; a datetime bound (drag-zoom) can't. */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** Preset quick-picks + an absolute From/To, bound to the shared dashboard time range. */
function TimeRangeControls() {
  const { range, presets, setPreset, setAbsolute } = useTimeRange();
  const absolute = range.kind === "absolute";
  // Light up the preset whose window matches the live selection; "custom" once an absolute window is set.
  const activeId = absolute ? "custom" : presets.find((p) => sameRange(p.range, range))?.id ?? "";
  const options = [
    ...presets.map((p) => ({ value: p.id, label: p.label })),
    ...(absolute ? [{ value: "custom", label: "Custom" }] : []),
  ];
  const dateOnly =
    absolute && range.from && range.to && DATE_ONLY.test(range.from) && DATE_ONLY.test(range.to);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Segmented
        size="md"
        value={activeId}
        onChange={(v) => {
          if (v !== "custom") setPreset(v);
        }}
        options={options}
      />
      <div className="w-[260px]">
        <DateRangeInput
          aria-label="Date range"
          value={dateOnly ? { start: parseDate(range.from!), end: parseDate(range.to!) } : null}
          onChange={(v) => setAbsolute(v?.start?.toString(), v?.end?.toString())}
        />
      </div>
    </div>
  );
}

/**
 * The shared dashboard time picker, placed once at the top of a dashboard (widget type "timeRange").
 * Authors tune it per dashboard: {@code config("presets", "15m,1h,24h,7d,30d")} (a comma-separated
 * list of duration ids — any `<n><unit>`, plus `all`) and {@code config("default", "30d")}.
 */
export function TimeRangeWidget({ widget }: ChartWidgetProps) {
  const { configure } = useTimeRange();
  const presetsCsv = widget.extraConfig?.presets;
  const defaultId = widget.extraConfig?.default;
  useEffect(() => {
    configure({ presets: presetsFromConfig(presetsCsv), defaultRangeId: defaultId });
  }, [configure, presetsCsv, defaultId]);
  return (
    <Card className="overflow-hidden">
      <CardContent className="flex flex-wrap items-center justify-between gap-2 p-3">
        <div className="flex items-center gap-1.5 text-[12px] font-medium text-muted-foreground">
          <CalendarRange size={14} />
          {widget.title || "Time range"}
        </div>
        <TimeRangeControls />
      </CardContent>
    </Card>
  );
}

/** A lightweight modal (no dialog primitive in the kit); closes on backdrop click or Escape. */
function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        className="flex max-h-[90vh] w-full max-w-4xl flex-col overflow-hidden rounded-lg border border-border bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <h2 className="text-sm font-semibold">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <X size={16} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto">{children}</div>
      </div>
    </div>,
    document.body
  );
}

/** The explore view: a bigger chart with every control (incl. the shared range) + the data table. */
function ExploreModal({
  widget,
  config,
  controls,
  items,
  onClose,
}: {
  widget: DashboardWidgetMeta;
  config: ChartConfig;
  controls: ControlsSpec;
  items: EntityRecord[];
  onClose: () => void;
}) {
  const { range, setAbsolute } = useTimeRange();
  const windowField = widget.dateField || "_date";
  const ranged = useMemo(() => filterWindow(items, windowField, range), [items, windowField, range]);
  const spanDays = useMemo(() => dataSpanDays(ranged, windowField), [ranged, windowField]);
  const autoGran = useMemo(() => granularityForSpan(spanDays), [spanDays]);
  const [granularity, setGranularity] = useState<GroupByDate>(autoGran);
  useEffect(() => setGranularity(autoGran), [autoGran]);
  const [kind, setKind] = useState<ChartKind>(config.kind);
  const [scale, setScale] = useState<ScaleMode>("absolute");
  const [seriesBy, setSeriesBy] = useState<string>(controls.defaultSeries);
  const [hidden, setHidden] = useState<Set<string>>(() => new Set());
  const toggle = (k: string) =>
    setHidden((p) => {
      const n = new Set(p);
      if (n.has(k)) n.delete(k);
      else n.add(k);
      return n;
    });

  const fmts = useChartFormatters(config);
  const { data, comboData, round } = useMemo(
    () => buildChartData(config, ranged, { granularity, kind, seriesBy: seriesBy || undefined, scale }),
    [config, ranged, granularity, kind, seriesBy, scale]
  );
  const colors = useMemo(
    () => resolveColors(round ? data.rows.length : data.seriesKeys.length, config.colors),
    [round, data, config.colors]
  );
  const comboColors = useMemo(() => resolveColors(2, config.colors), [config.colors]);
  const gradientPrefix = `explore-${useId().replace(/:/g, "")}`;
  const empty = config.combo ? (comboData?.rows.length ?? 0) === 0 : data.rows.length === 0;
  const zoomRows = config.combo ? comboData?.rows ?? [] : data.rows;
  const { dragProps, refArea } = useDragZoom(zoomRows, setAbsolute);

  return (
    <Modal title={widget.title} onClose={onClose}>
      {/* The shared range + every per-chart control, regardless of what the card exposes. */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2.5">
        <TimeRangeControls />
        {!round && (
          <Segmented value={granularity} onChange={setGranularity} options={GRANULARITY_OPTIONS.map((g) => ({ value: g, label: GRANULARITY_LABELS[g] }))} />
        )}
        {!config.combo && controls.series.length > 0 && (
          <Segmented value={seriesBy} onChange={setSeriesBy} options={controls.series} />
        )}
        {!config.combo && (
          <Segmented value={kind} onChange={setKind} options={TYPE_OPTIONS.map((k) => ({ value: k, label: TYPE_LABELS[k] }))} />
        )}
        {!config.combo && !round && (
          <Segmented value={scale} onChange={setScale} options={SCALE_OPTIONS.map((s) => ({ value: s, label: SCALE_LABELS[s] }))} />
        )}
      </div>
      <div className="px-4 pt-4">
        {empty ? (
          <div className="flex h-[320px] items-center justify-center text-xs text-muted-foreground">No data in range</div>
        ) : (
          <ResponsiveChart height={320}>
            {config.combo && comboData
              ? renderCombo(comboData, config, fmts.fmt, fmts.fmtAxis, fmts.fmtSecondary, fmts.fmtSecondaryAxis, comboColors, gradientPrefix, dragProps, refArea)
              : renderChart(kind, data, colors, fmts.fmt, fmts.fmtAxis, config.stacked, gradientPrefix, hidden, toggle, dragProps, refArea)}
          </ResponsiveChart>
        )}
      </div>
      {!empty && <DataTable config={config} data={data} comboData={comboData} fmt={fmts.fmt} fmtSecondary={fmts.fmtSecondary} />}
    </Modal>
  );
}

/** The numbers behind the chart — one row per x bucket, one column per series/measure. */
function DataTable({
  config,
  data,
  comboData,
  fmt,
  fmtSecondary,
}: {
  config: ChartConfig;
  data: SeriesData;
  comboData: ComboData | null;
  fmt: (n: number) => string;
  fmtSecondary: (n: number) => string;
}) {
  const combo = config.combo && comboData != null;
  const rows = combo ? comboData!.rows : data.rows;
  const keys = combo ? ["primary", "secondary"] : data.seriesKeys;
  const headers = combo
    ? [config.primaryLabel, config.secondaryLabel]
    : data.seriesKeys.map((k) => (k === SINGLE_SERIES ? config.primaryLabel || "Value" : k));
  return (
    <div className="px-4 py-4">
      <div className="mb-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Data</div>
      <div className="max-h-64 overflow-y-auto rounded-md border border-border">
        <table className="w-full text-xs">
          <thead className="sticky top-0 bg-muted/60 backdrop-blur">
            <tr>
              <th className="px-3 py-1.5 text-left font-medium">Period</th>
              {headers.map((h, i) => (
                <th key={i} className="px-3 py-1.5 text-right font-medium">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, ri) => (
              <tr key={ri} className="border-t border-border">
                <td className="px-3 py-1.5 text-left text-muted-foreground">{String(row.label)}</td>
                {keys.map((k, ci) => (
                  <td key={ci} className="px-3 py-1.5 text-right tabular-nums">
                    {combo && k === "secondary" ? fmtSecondary(Number(row[k]) || 0) : fmt(Number(row[k]) || 0)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
