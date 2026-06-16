import { useId, useMemo } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
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
import { buildSeries, SINGLE_SERIES, useWidgetRows, type GroupByDate, type Metric, type SeriesData } from "@/lib/widget-data";
import type { DashboardWidgetMeta } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";

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
}

function readConfig(widget: DashboardWidgetMeta): ChartConfig {
  const cfg = widget.extraConfig ?? {};
  const explicit = (cfg.kind as ChartKind | undefined) ?? "bar";
  let kind: ChartKind = explicit;
  if (!CHART_KINDS.includes(explicit)) {
    // Don't silently coerce an unknown kind — surface it, then fall back.
    console.warn(`[onec chart] unknown kind "${explicit}" for "${widget.title}"; falling back to "bar"`);
    kind = "bar";
  }
  const metric = (cfg.metric as Metric) ?? "count";
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
  };
}

export function ChartWidget({ widget }: ChartWidgetProps) {
  const config = useMemo(() => readConfig(widget), [widget]);
  const items = useWidgetRows(widget);
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

  const round = config.kind === "donut" || config.kind === "pie";
  const data = useMemo<SeriesData>(
    () =>
      buildSeries(items, {
        groupBy: config.groupBy,
        groupByDate: config.groupByDate,
        // A pie's slices ARE the buckets; splitting into series doesn't apply there.
        seriesBy: round ? undefined : config.seriesBy,
        metric: config.metric,
        metricField: config.metricField,
      }),
    [items, config, round]
  );

  const colors = useMemo(
    () => resolveColors(round ? data.rows.length : data.seriesKeys.length, config.colors),
    [round, data, config.colors]
  );

  // A unique gradient-id prefix per widget instance — SVG ids are document-global, so two area
  // charts on one page would otherwise share (and clobber) the same <linearGradient>.
  const gradientPrefix = `chart-${useId().replace(/:/g, "")}`;

  return (
    <Card className="overflow-hidden">
      <CardHeader className="flex-row items-start justify-between space-y-0 p-4 pb-1">
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
          <HintIcon text={widget.hint} size={13} />
        </div>
        {data.rows.length > 0 && (
          <span className="text-[13px] font-semibold tabular-nums text-foreground">{fmt(data.total)}</span>
        )}
      </CardHeader>
      <CardContent className="p-4 pt-2">
        {data.rows.length === 0 ? (
          <div className="flex h-[210px] items-center justify-center text-xs text-muted-foreground">No data yet</div>
        ) : (
          <ResponsiveContainer width="100%" height={round ? 230 : 210}>
            {renderChart(config.kind, data, colors, fmt, fmtAxis, config.stacked, gradientPrefix)}
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
  gradientPrefix: string
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
  const legend = multi ? <Legend wrapperStyle={{ fontSize: 11, paddingTop: 6 }} iconType="circle" iconSize={8} /> : null;

  if (kind === "donut" || kind === "pie") {
    return (
      <PieChart>
        <Tooltip contentStyle={tooltipStyle} itemStyle={{ color: "hsl(var(--popover-foreground))" }} formatter={(v: unknown) => fmt(toNumber(v) ?? 0)} />
        <Legend wrapperStyle={{ fontSize: 11 }} iconType="circle" iconSize={8} />
        <Pie
          data={data.rows}
          dataKey={SINGLE_SERIES}
          nameKey="label"
          innerRadius={kind === "pie" ? 0 : 52}
          outerRadius={82}
          paddingAngle={kind === "pie" ? 0 : 2}
          stroke="hsl(var(--card))"
          strokeWidth={2}
        >
          {data.rows.map((_, i) => (
            <Cell key={i} fill={colors[i % colors.length]} />
          ))}
        </Pie>
      </PieChart>
    );
  }

  if (kind === "line" || kind === "area") {
    const ChartImpl = kind === "line" ? LineChart : AreaChart;
    return (
      <ChartImpl data={data.rows} margin={{ top: 4, right: 8, left: 4, bottom: 0 }}>
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
        <YAxis {...axis} width={52} tickFormatter={fmtAxis} />
        <Tooltip contentStyle={tooltipStyle} cursor={{ stroke: "hsl(var(--border))" }} formatter={tooltipFormatter} />
        {legend}
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
            />
          )
        )}
      </ChartImpl>
    );
  }

  return (
    <BarChart data={data.rows} margin={{ top: 4, right: 8, left: 4, bottom: 0 }}>
      <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
      <XAxis dataKey="label" {...axis} />
      <YAxis {...axis} width={52} tickFormatter={fmtAxis} />
      <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} formatter={tooltipFormatter} />
      {legend}
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
          />
        );
      })}
    </BarChart>
  );
}
