import { useMemo } from "react";
import { ArrowDownRight, ArrowRight, ArrowUpRight } from "lucide-react";
import { formatCompact } from "@/lib/format";
import { resolveColor } from "@/lib/chart-colors";
import {
  buildSeries,
  seriesFromBuckets,
  useWidgetBuckets,
  useWidgetRows,
  type GroupByDate,
  type Metric,
} from "@/lib/widget-data";
import type { DashboardWidgetMeta } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { Sparkline } from "@/components/sparkline";

/**
 * A KPI tile with momentum: the headline figure (the same aggregate the `metric` card shows),
 * the period-over-period change versus the prior bucket, and a sparkline of the whole series.
 * Source/aggregate config matches the chart widget (`metric`/`metricField`, `groupBy`/`groupByDate`);
 * `kind` picks the sparkline shape and `colors` its accent.
 */
export function StatWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const cfg = widget.extraConfig ?? {};
  // Catalog/document tiles fetch pre-aggregated buckets (#199); registers keep the row path, so
  // hand useWidgetRows an entityType it ignores on the bucket path (the hook must still be called).
  const isRegister = widget.entityType === "register";
  const rowsWidget = useMemo(() => (isRegister ? widget : { ...widget, entityType: "" }), [isRegister, widget]);
  const items = useWidgetRows(rowsWidget);

  const metric = (cfg.metric as Metric) ?? "count";
  const metricField = cfg.metricField;
  const groupBy = cfg.groupBy ?? "_date";
  // Default to monthly buckets for the trend — a day-over-day delta on a long series is noise.
  const groupByDate =
    (cfg.groupByDate as GroupByDate | undefined) ??
    (groupBy === "_date" || !cfg.groupBy ? "month" : undefined);
  const color = resolveColor(cfg.colors);
  const fmtNum = (n: number) =>
    formatCompact(n, {
      currency: cfg.currency,
      unit: cfg.unit,
      unitPosition: cfg.unitPosition,
      format: cfg.format ?? (metric === "count" ? "integer" : undefined),
      locale: cfg.locale,
    });

  // The bucket request: same measure/grouping as the row path, with the authored "_display" suffix
  // stripped — the server groups the real column and resolves labels itself. Catalogs have no _date
  // column, so a defaulted "_date" grouping degrades to the endpoint's single grand-total bucket.
  const params = useMemo(() => {
    if (isRegister) return null;
    const p: Record<string, string> = { metric };
    if (metricField) p.field = metricField;
    const group = groupBy.replace(/_display$/, "");
    if (widget.entityType === "document" || group !== "_date") {
      p.groupBy = group;
      if (groupByDate) p.groupByDate = groupByDate;
    }
    if (cfg.filter) p.filter = cfg.filter;
    return p;
  }, [isRegister, metric, metricField, groupBy, groupByDate, widget.entityType, cfg.filter]);
  const resp = useWidgetBuckets(widget, params);

  const series = useMemo(
    () =>
      isRegister
        ? buildSeries(items, { groupBy, groupByDate, metric, metricField })
        : seriesFromBuckets(resp ?? { buckets: [], truncated: false }, { groupBy, groupByDate, metric, metricField }),
    [isRegister, items, resp, groupBy, groupByDate, metric, metricField]
  );

  const points = series.rows.map((r) => ({ value: Number(r.value) || 0 }));
  const last = points.length ? points[points.length - 1].value : undefined;
  const prev = points.length > 1 ? points[points.length - 2].value : undefined;
  // The headline is the grand total; the delta is the latest bucket's move on the one before it.
  const delta = last != null && prev != null && prev !== 0 ? (last - prev) / prev : null;
  const periodWord = groupByDate === "day" ? "day" : groupByDate === "week" ? "week" : "month";

  const Arrow = delta == null || delta === 0 ? ArrowRight : delta > 0 ? ArrowUpRight : ArrowDownRight;
  const deltaClass =
    delta == null || delta === 0 ? "text-muted-foreground" : delta > 0 ? "text-success" : "text-destructive";

  return (
    <Card className="overflow-hidden">
      <CardContent className="p-4">
        <div className="flex items-center gap-1.5">
          <span className="text-[13px] font-medium text-muted-foreground">{widget.title}</span>
          <HintIcon text={widget.hint} size={13} />
        </div>
        <div className="mt-1 flex items-baseline gap-2">
          <span className="text-[28px] font-semibold leading-none tabular-nums">{fmtNum(series.total)}</span>
          {delta != null && (
            <span className={`flex items-center gap-0.5 text-xs font-medium ${deltaClass}`}>
              <Arrow size={13} />
              {Math.abs(delta * 100).toFixed(1)}%
            </span>
          )}
        </div>
        <div className="mt-2.5">
          <Sparkline data={points} color={color} kind={cfg.kind === "line" ? "line" : "area"} height={44} />
        </div>
        {delta != null && (
          <div className="mt-1 text-[11px] text-muted-foreground">vs previous {periodWord}</div>
        )}
      </CardContent>
    </Card>
  );
}
