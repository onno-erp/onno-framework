import { useEffect, useMemo, useState } from "react";
import { format, parseISO } from "date-fns";
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
import { api } from "@/lib/api";
import { toSnakeCase } from "@/lib/utils";
import { formatNumber, toNumber } from "@/lib/format";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface ChartWidgetProps {
  widget: DashboardWidgetMeta;
}

// "pie" is an alias of "donut" with a zero inner radius (a filled circle).
type ChartKind = "bar" | "line" | "area" | "donut" | "pie";
const CHART_KINDS: ChartKind[] = ["bar", "line", "area", "donut", "pie"];

// Turnover/balance need a period window; a register chart sums across all of time
// unless the author scopes it, so we ask for an unbounded range.
const ALL_TIME_FROM = "1970-01-01T00:00:00";
const ALL_TIME_TO = "2999-12-31T23:59:59";

interface AggregateConfig {
  groupBy: string;
  groupByDate?: "day" | "week" | "month";
  metric: "count" | "sum";
  metricField?: string;
  kind: ChartKind;
  currency?: string;
  format?: string;
  locale?: string;
}

function readConfig(widget: DashboardWidgetMeta): AggregateConfig {
  const cfg = widget.extraConfig ?? {};
  const explicit = (cfg.kind as ChartKind | undefined) ?? "bar";
  let kind: ChartKind = explicit;
  if (!CHART_KINDS.includes(explicit)) {
    // FR-3: don't silently coerce an unknown kind — surface it, then fall back.
    console.warn(`[onec chart] unknown kind "${explicit}" for "${widget.title}"; falling back to "bar"`);
    kind = "bar";
  }
  const metric = (cfg.metric as "count" | "sum") ?? "count";
  return {
    groupBy: cfg.groupBy ?? "_date",
    groupByDate:
      (cfg.groupByDate as "day" | "week" | "month" | undefined) ??
      (cfg.groupBy === "_date" || !cfg.groupBy ? "day" : undefined),
    metric,
    metricField: cfg.metricField,
    kind,
    currency: cfg.currency,
    // Counts read better as integers on the axis/tooltip unless the author overrides.
    format: cfg.format ?? (metric === "count" ? "integer" : undefined),
    locale: cfg.locale,
  };
}

function bucketLabel(value: unknown, groupByDate?: AggregateConfig["groupByDate"]): string {
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

const CHART_COLORS = [
  "hsl(var(--primary))",
  "hsl(var(--success))",
  "hsl(var(--warning))",
  "hsl(var(--destructive))",
  "hsl(var(--muted-foreground))",
];

export function ChartWidget({ widget }: ChartWidgetProps) {
  const [items, setItems] = useState<EntityRecord[]>([]);
  const config = useMemo(() => readConfig(widget), [widget]);
  const fmt = useMemo(
    () => (n: number) => formatNumber(n, { currency: config.currency, format: config.format, locale: config.locale }),
    [config]
  );

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    if (widget.entityType === "document") {
      api.listDocuments(name).then(setItems);
    } else if (widget.entityType === "catalog") {
      api.listCatalog(name).then(setItems);
    } else if (widget.entityType === "register") {
      // FR-4: source from the register's server-side turnover (grouped resource sums),
      // then bucket/aggregate client-side exactly like a document/catalog feed.
      api.getTurnover(name, ALL_TIME_FROM, ALL_TIME_TO).then(setItems);
    }
  }, [widget]);

  const data = useMemo(() => {
    const buckets = new Map<string, { label: string; value: number }>();
    for (const row of items) {
      const raw = row[config.groupBy];
      const label = bucketLabel(raw, config.groupByDate);
      const existing = buckets.get(label) ?? { label, value: 0 };
      if (config.metric === "count") {
        existing.value += 1;
      } else if (config.metric === "sum" && config.metricField) {
        const v = row[config.metricField];
        if (typeof v === "number") existing.value += v;
      }
      buckets.set(label, existing);
    }
    return Array.from(buckets.values());
  }, [items, config]);

  const round = config.kind === "donut" || config.kind === "pie";
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={round ? 240 : 220}>
          {renderChart(config.kind, data, fmt)}
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}

function renderChart(
  kind: ChartKind,
  data: { label: string; value: number }[],
  fmt: (n: number) => string
): React.ReactElement {
  const tooltipStyle = {
    background: "hsl(var(--popover))",
    border: "1px solid hsl(var(--border))",
    borderRadius: 6,
    fontSize: 12,
    color: "hsl(var(--popover-foreground))",
  };
  // Recharts' Formatter passes a wide value type; coerce and render a single string.
  const tooltipFormatter = (value: unknown) => fmt(toNumber(value) ?? 0);

  if (kind === "donut" || kind === "pie") {
    return (
      <PieChart>
        <Tooltip
          contentStyle={tooltipStyle}
          itemStyle={{ color: "hsl(var(--popover-foreground))" }}
          formatter={tooltipFormatter}
        />
        <Legend wrapperStyle={{ fontSize: 11 }} />
        <Pie
          data={data}
          dataKey="value"
          nameKey="label"
          innerRadius={kind === "pie" ? 0 : 50}
          outerRadius={80}
          paddingAngle={kind === "pie" ? 0 : 2}
        >
          {data.map((_, i) => (
            <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
          ))}
        </Pie>
      </PieChart>
    );
  }

  if (kind === "line" || kind === "area") {
    const ChartImpl = kind === "line" ? LineChart : AreaChart;
    const Series = kind === "line" ? Line : Area;
    return (
      <ChartImpl data={data} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
        <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="label" stroke="hsl(var(--muted-foreground))" fontSize={11} tickLine={false} axisLine={false} />
        <YAxis stroke="hsl(var(--muted-foreground))" fontSize={11} tickLine={false} axisLine={false} width={40} tickFormatter={fmt} />
        <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} formatter={tooltipFormatter} />
        <Series
          type="monotone"
          dataKey="value"
          stroke="hsl(var(--primary))"
          fill="hsl(var(--primary) / 0.15)"
          strokeWidth={2}
          dot={false}
        />
      </ChartImpl>
    );
  }

  return (
    <BarChart data={data} margin={{ top: 4, right: 4, left: -16, bottom: 0 }}>
      <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
      <XAxis dataKey="label" stroke="hsl(var(--muted-foreground))" fontSize={11} tickLine={false} axisLine={false} />
      <YAxis stroke="hsl(var(--muted-foreground))" fontSize={11} tickLine={false} axisLine={false} width={40} tickFormatter={fmt} />
      <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} formatter={tooltipFormatter} />
      <Bar dataKey="value" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} />
    </BarChart>
  );
}
