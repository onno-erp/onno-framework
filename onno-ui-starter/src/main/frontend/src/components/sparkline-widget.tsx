import { useMemo } from "react";
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
 * The minimal trend tile: a title, the aggregate figure, and an inline sparkline — no delta badge
 * (that's the {@link StatWidget}). Same source/aggregate config as the chart and stat widgets.
 */
export function SparklineWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const cfg = widget.extraConfig ?? {};
  // Catalog/document tiles fetch pre-aggregated buckets (#199); registers keep the row path, so
  // hand useWidgetRows an entityType it ignores on the bucket path (the hook must still be called).
  const isRegister = widget.entityType === "register";
  const rowsWidget = useMemo(() => (isRegister ? widget : { ...widget, entityType: "" }), [isRegister, widget]);
  const items = useWidgetRows(rowsWidget);

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
