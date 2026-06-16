import { useMemo } from "react";
import { formatCompact } from "@/lib/format";
import { resolveColor } from "@/lib/chart-colors";
import { buildSeries, useWidgetRows, type GroupByDate, type Metric } from "@/lib/widget-data";
import type { DashboardWidgetMeta } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { Sparkline } from "@/components/sparkline";

/**
 * The minimal trend tile: a title, the aggregate figure, and an inline sparkline — no delta badge
 * (that's the {@link StatWidget}). Same source/aggregate config as the chart and stat widgets.
 */
export function SparklineWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const cfg = widget.extraConfig ?? {};
  const items = useWidgetRows(widget);

  const metric = (cfg.metric as Metric) ?? "count";
  const metricField = cfg.metricField;
  const groupBy = cfg.groupBy ?? "_date";
  const groupByDate =
    (cfg.groupByDate as GroupByDate | undefined) ??
    (groupBy === "_date" || !cfg.groupBy ? "day" : undefined);
  const color = resolveColor(cfg.colors);
  const fmtNum = (n: number) =>
    formatCompact(n, {
      currency: cfg.currency,
      unit: cfg.unit,
      unitPosition: cfg.unitPosition,
      format: cfg.format ?? (metric === "count" ? "integer" : undefined),
      locale: cfg.locale,
    });

  const series = useMemo(
    () => buildSeries(items, { groupBy, groupByDate, metric, metricField }),
    [items, groupBy, groupByDate, metric, metricField]
  );
  const points = series.rows.map((r) => ({ value: Number(r.value) || 0 }));

  return (
    <Card className="overflow-hidden">
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <span className="text-[13px] font-medium text-muted-foreground">{widget.title}</span>
            <HintIcon text={widget.hint} size={13} />
          </div>
          <span className="text-[15px] font-semibold tabular-nums">{fmtNum(series.total)}</span>
        </div>
        <Sparkline data={points} color={color} kind={cfg.kind === "line" ? "line" : "area"} height={40} />
      </CardContent>
    </Card>
  );
}
