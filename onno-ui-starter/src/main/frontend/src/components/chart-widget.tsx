import { useId, useMemo, useState } from "react";
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
  filterRange,
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
import type { DashboardWidgetMeta } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
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

const RANGE_OPTIONS: RangeKey[] = ["7d", "30d", "90d", "12m", "all"];
const GRANULARITY_OPTIONS: GroupByDate[] = ["day", "week", "month"];
const GRANULARITY_LABELS: Record<GroupByDate, string> = { day: "Day", week: "Week", month: "Month" };
const TYPE_OPTIONS: ChartKind[] = ["bar", "line", "area"];
const TYPE_LABELS: Record<string, string> = { bar: "Bar", line: "Line", area: "Area" };
const SCALE_OPTIONS: ScaleMode[] = ["absolute", "indexed", "normalized"];

interface ControlsSpec {
  enabled: Set<string>;
  defaultRange: RangeKey;
  /** Series-split options for the "series" control: `{value, label}` ("" = the single unsplit series). */
  series: { value: string; label: string }[];
  defaultSeries: string;
}

/**
 * Which interactive controls a chart exposes, from `config("controls", "range,granularity,type,scale,series")`
 * ("all" = range,granularity,type,scale). Everything stays opt-in, so charts that declare no controls
 * render exactly as before. `config("defaultRange", …)` and `config("seriesOptions", "field:Label,…")`
 * tune the range and series controls.
 */
function readControls(widget: DashboardWidgetMeta, base: ChartConfig): ControlsSpec {
  const cfg = widget.extraConfig ?? {};
  let raw = (cfg.controls ?? "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
  if (raw.includes("all")) raw = ["range", "granularity", "type", "scale"];
  const enabled = new Set(raw);
  const defaultRange = (RANGE_OPTIONS.includes(cfg.defaultRange as RangeKey) ? cfg.defaultRange : "all") as RangeKey;
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
  return { enabled, defaultRange, series, defaultSeries };
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

  // Local control state, seeded from the authored config; applied only when its control is enabled.
  const [range, setRange] = useState<RangeKey>(controls.defaultRange);
  const [granularity, setGranularity] = useState<GroupByDate>(config.groupByDate ?? "day");
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

  const items = useWidgetRows(widget);

  // Effective settings: control state when that control is on, else the authored config.
  const effKind = controls.enabled.has("type") ? kind : config.kind;
  const effGranularity = controls.enabled.has("granularity") ? granularity : config.groupByDate;
  const effSeriesBy = controls.enabled.has("series") ? seriesBy || undefined : config.seriesBy;
  const effScale: ScaleMode = controls.enabled.has("scale") ? scale : "absolute";
  const round = effKind === "donut" || effKind === "pie";

  const numberOpts = useMemo(
    () => ({
      currency: config.currency,
      unit: config.unit,
      unitPosition: config.unitPosition,
      format: config.format,
      locale: config.locale,
    }),
    [config]
  );
  // Full figures in the tooltip + header; compact ones on the axis, where a full currency value
  // would clip.
  const fmt = useMemo(() => (n: number) => formatNumber(n, numberOpts), [numberOpts]);
  const fmtAxis = useMemo(() => (n: number) => formatCompact(n, numberOpts), [numberOpts]);

  // The range control windows the rows by the group-by date before bucketing.
  const ranged = useMemo(
    () => (controls.enabled.has("range") ? filterRange(items, config.groupBy, range) : items),
    [items, controls, config.groupBy, range]
  );

  const data = useMemo<SeriesData>(() => {
    const built = buildSeries(ranged, {
      groupBy: config.groupBy,
      groupByDate: effGranularity,
      // A pie's slices ARE the buckets; splitting into series doesn't apply there.
      seriesBy: round ? undefined : effSeriesBy,
      metric: config.metric,
      metricField: config.metricField,
    });
    // Rescaling compares real series; a pie's slice set is left absolute.
    return applyScale(built, round ? "absolute" : effScale);
  }, [ranged, config, effGranularity, round, effSeriesBy, effScale]);

  // The secondary measure of a dual-axis chart counts events, so it reads as integers.
  const secondaryOpts = useMemo(() => ({ format: "integer" }), []);
  const fmtSecondary = useMemo(() => (n: number) => formatNumber(n, secondaryOpts), [secondaryOpts]);
  const fmtSecondaryAxis = useMemo(() => (n: number) => formatCompact(n, secondaryOpts), [secondaryOpts]);

  const comboData = useMemo<ComboData | null>(() => {
    if (!config.combo) return null;
    return buildCombo(ranged, {
      groupBy: config.groupBy,
      groupByDate: effGranularity,
      primary: { metric: config.metric, metricField: config.metricField },
      secondary: { metric: config.secondaryMetric, metricField: config.secondaryField },
    });
  }, [config, ranged, effGranularity]);

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

  const controlNodes: React.ReactNode[] = [];
  if (controls.enabled.has("range"))
    controlNodes.push(
      <Segmented key="range" value={range} onChange={setRange} options={RANGE_OPTIONS.map((r) => ({ value: r, label: RANGE_LABELS[r] }))} />
    );
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
        {/* The controls live where the grand-total figure would be; a chart with no controls keeps
            the figure there instead. */}
        {controlNodes.length > 0 ? (
          <div className="flex flex-wrap items-center justify-end gap-1.5">{controlNodes}</div>
        ) : showTotal ? (
          <span className="text-[13px] font-semibold tabular-nums text-foreground">{fmt(data.total)}</span>
        ) : null}
      </CardHeader>
      <CardContent className="p-4 pt-2">
        {(config.combo ? (comboData?.rows.length ?? 0) === 0 : data.rows.length === 0) ? (
          <div className="flex h-[210px] items-center justify-center text-xs text-muted-foreground">No data yet</div>
        ) : (
          <ResponsiveContainer width="100%" height={round && !config.combo ? 230 : 210}>
            {config.combo && comboData
              ? renderCombo(comboData, config, fmt, fmtAxis, fmtSecondary, fmtSecondaryAxis, comboColors, gradientPrefix)
              : renderChart(effKind, data, colors, fmt, fmtAxis, config.stacked, gradientPrefix, hidden, toggleSeries)}
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
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
  onToggle: (key: string) => void
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
      <ChartImpl data={data.rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
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
    <BarChart data={data.rows} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
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
  gradientPrefix: string
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
    <ComposedChart data={data.rows} margin={{ top: 4, right: 4, left: 0, bottom: 0 }}>
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
