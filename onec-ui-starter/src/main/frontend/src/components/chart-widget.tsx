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
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface ChartWidgetProps {
  widget: DashboardWidgetMeta;
}

type ChartKind = "bar" | "line" | "area" | "donut";

interface AggregateConfig {
  groupBy: string;
  groupByDate?: "day" | "week" | "month";
  metric: "count" | "sum";
  metricField?: string;
  kind: ChartKind;
}

function readConfig(widget: DashboardWidgetMeta): AggregateConfig {
  const cfg = widget.extraConfig ?? {};
  const explicit = (cfg.kind as ChartKind | undefined) ?? "bar";
  const kind: ChartKind =
    explicit === "bar" || explicit === "line" || explicit === "area" || explicit === "donut"
      ? explicit
      : "bar";
  return {
    groupBy: cfg.groupBy ?? "_date",
    groupByDate:
      (cfg.groupByDate as "day" | "week" | "month" | undefined) ??
      (cfg.groupBy === "_date" || !cfg.groupBy ? "day" : undefined),
    metric: (cfg.metric as "count" | "sum") ?? "count",
    metricField: cfg.metricField,
    kind,
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

  useEffect(() => {
    const name = toSnakeCase(widget.entityName);
    if (widget.entityType === "document") {
      api.listDocuments(name).then(setItems);
    } else if (widget.entityType === "catalog") {
      api.listCatalog(name).then(setItems);
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

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={config.kind === "donut" ? 240 : 220}>
          {renderChart(config.kind, data)}
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}

function renderChart(
  kind: ChartKind,
  data: { label: string; value: number }[]
): React.ReactElement {
  const tooltipStyle = {
    background: "hsl(var(--popover))",
    border: "1px solid hsl(var(--border))",
    borderRadius: 6,
    fontSize: 12,
    color: "hsl(var(--popover-foreground))",
  };

  if (kind === "donut") {
    return (
      <PieChart>
        <Tooltip
          contentStyle={tooltipStyle}
          itemStyle={{ color: "hsl(var(--popover-foreground))" }}
        />
        <Legend wrapperStyle={{ fontSize: 11 }} />
        <Pie
          data={data}
          dataKey="value"
          nameKey="label"
          innerRadius={50}
          outerRadius={80}
          paddingAngle={2}
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
        <YAxis stroke="hsl(var(--muted-foreground))" fontSize={11} tickLine={false} axisLine={false} width={32} />
        <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} />
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
      <YAxis stroke="hsl(var(--muted-foreground))" fontSize={11} tickLine={false} axisLine={false} width={32} />
      <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "hsl(var(--accent) / 0.4)" }} />
      <Bar dataKey="value" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} />
    </BarChart>
  );
}
