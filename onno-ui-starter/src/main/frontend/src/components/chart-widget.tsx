import { useEffect, useId, useMemo, useState, type ReactNode } from "react";
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
  ResponsiveContainer,
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
  RANGE_DAYS,
  RANGE_LABELS,
  SCALE_LABELS,
  SINGLE_SERIES,
  useWidgetRows,
  type ComboData,
  type GroupByDate,
  type Metric,
  type RangeKey,
  type ScaleMode,
  type SeriesData,
} from "@/lib/widget-data";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { useTimeRange, type TimeRange } from "@/providers/time-range-provider";
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

const GRANULARITY_OPTIONS: GroupByDate[] = ["day", "week", "month"];
const GRANULARITY_LABELS: Record<GroupByDate, string> = { day: "Day", week: "Week", month: "Month" };
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
  onMouseDown: (e: { activeLabel?: string | number } | null) => void;
  onMouseMove: (e: { activeLabel?: string | number } | null) => void;
  onMouseUp: () => void;
  onMouseLeave: () => void;
}
interface RefAreaSel {
  x1: string;
  x2: string;
}

/**
 * Drag-select a region on a time chart to set an absolute range (Grafana-style zoom). Maps the
 * x-axis labels under the cursor back to their bucket dates ({@link ISO_KEY}) and calls {@code onZoom}.
 */
function useDragZoom(
  rows: Array<Record<string, number | string>>,
  onZoom: (fromIso: string, toIso: string) => void
): { dragProps: DragProps; refArea: RefAreaSel | null } {
  const [sel, setSel] = useState<{ left: string | null; right: string | null }>({ left: null, right: null });
  const isoFor = (label: string) => {
    const r = rows.find((row) => String(row.label) === label);
    return r ? String(r[ISO_KEY] ?? "") : "";
  };
  const dragProps: DragProps = {
    onMouseDown: (e) => {
      const l = e?.activeLabel;
      if (l != null) setSel({ left: String(l), right: String(l) });
    },
    onMouseMove: (e) => {
      const l = e?.activeLabel;
      if (l != null) setSel((s) => (s.left ? { ...s, right: String(l) } : s));
    },
    onMouseUp: () =>
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
      }),
    onMouseLeave: () => setSel({ left: null, right: null }),
  };
  const refArea = sel.left && sel.right && sel.left !== sel.right ? { x1: sel.left, x2: sel.right } : null;
  return { dragProps, refArea };
}

/** Pick a sensible bucket size for a time window: days for ≤1 month, weeks for ≤6 months, else months. */
function autoGranularity(range: TimeRange): GroupByDate {
  let days: number;
  if (range.from && range.to) {
    days = Math.max(1, (Date.parse(range.to) - Date.parse(range.from)) / 86_400_000);
  } else {
    const d = RANGE_DAYS[range.preset];
    days = d == null ? Infinity : d;
  }
  if (days <= 31) return "day";
  if (days <= 182) return "week";
  return "month";
}

/** A compact segmented toggle for the chart control row. */
function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex items-center rounded-md border border-border bg-muted/40 p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={cn(
            "rounded px-1.5 py-0.5 text-[11px] font-medium leading-none transition-colors",
            value === o.value
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground"
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

  // Granularity auto-follows the shared range (day → week → month); a range change re-applies it,
  // and the user can still override until the next change.
  const autoGran = useMemo(() => autoGranularity(range), [range]);
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

  const items = useWidgetRows(widget);

  // Effective settings: control state when that control is on, else the authored config. A time
  // chart (groupByDate set) always uses the auto/overridden granularity.
  const effKind = controls.enabled.has("type") ? kind : config.kind;
  const effGranularity = config.groupByDate != null ? granularity : config.groupByDate;
  const effSeriesBy = controls.enabled.has("series") ? seriesBy || undefined : config.seriesBy;
  const effScale: ScaleMode = controls.enabled.has("scale") ? scale : "absolute";

  const fmts = useChartFormatters(config);
  const { fmt, fmtAxis } = fmts;

  // The shared dashboard time range windows the rows by the document's date BEFORE bucketing — so it
  // applies to every chart (a status pie filters to in-range orders too), not just time series.
  const windowField = widget.dateField || "_date";
  const ranged = useMemo(() => filterWindow(items, windowField, range), [items, windowField, range]);
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

  const controlNodes: React.ReactNode[] = [];
  if (controls.enabled.has("granularity") && !round)
    controlNodes.push(
      <Segmented key="gran" value={granularity} onChange={setGranularity} options={GRANULARITY_OPTIONS.map((g) => ({ value: g, label: GRANULARITY_LABELS[g] }))} />
    );
  // Series / type / scale don't apply to a dual-axis combo (two fixed measures, two axes).
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
          <ResponsiveContainer width="100%" height={round && !config.combo ? 230 : 210}>
            {config.combo && comboData
              ? renderCombo(comboData, config, fmt, fmtAxis, fmts.fmtSecondary, fmts.fmtSecondaryAxis, comboColors, gradientPrefix, dragProps, refArea)
              : renderChart(effKind, data, colors, fmt, fmtAxis, config.stacked, gradientPrefix, hidden, toggleSeries, dragProps, refArea)}
          </ResponsiveContainer>
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

// A plain function (not a component) so it can be the direct child of ResponsiveContainer, which
// clones its single child to inject the measured width/height — a wrapper component would swallow
// them and the chart would render at 0×0.
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
        <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
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

const PRESETS: RangeKey[] = ["7d", "30d", "90d", "12m", "all"];

/** Preset buttons + absolute From/To, bound to the shared dashboard time range. */
function TimeRangeControls() {
  const { range, setPreset, setAbsolute } = useTimeRange();
  const absolute = !!(range.from || range.to);
  const options = [
    ...PRESETS.map((r) => ({ value: r as string, label: RANGE_LABELS[r] })),
    ...(absolute ? [{ value: "custom", label: "Custom" }] : []),
  ];
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Segmented
        value={absolute ? "custom" : range.preset}
        onChange={(v) => {
          if (v !== "custom") setPreset(v as RangeKey);
        }}
        options={options}
      />
      <div className="w-[260px]">
        <DateRangeInput
          aria-label="Date range"
          value={
            range.from && range.to
              ? { start: parseDate(range.from), end: parseDate(range.to) }
              : null
          }
          onChange={(v) => setAbsolute(v?.start?.toString(), v?.end?.toString())}
        />
      </div>
    </div>
  );
}

/** The shared dashboard time picker, placed once at the top of a dashboard (widget type "timeRange"). */
export function TimeRangeWidget({ widget }: ChartWidgetProps) {
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
  const autoGran = useMemo(() => autoGranularity(range), [range]);
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
  const windowField = widget.dateField || "_date";
  const ranged = useMemo(() => filterWindow(items, windowField, range), [items, windowField, range]);
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
          <ResponsiveContainer width="100%" height={320}>
            {config.combo && comboData
              ? renderCombo(comboData, config, fmts.fmt, fmts.fmtAxis, fmts.fmtSecondary, fmts.fmtSecondaryAxis, comboColors, gradientPrefix, dragProps, refArea)
              : renderChart(kind, data, colors, fmts.fmt, fmts.fmtAxis, config.stacked, gradientPrefix, hidden, toggle, dragProps, refArea)}
          </ResponsiveContainer>
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
