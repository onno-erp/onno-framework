import { useMemo } from "react";
import { format } from "date-fns";
import { ArrowDownRight, ArrowRight, ArrowUpRight } from "lucide-react";
import { formatCompact, formatNumber } from "@/lib/format";
import { resolveColor } from "@/lib/chart-colors";
import {
  buildSeries,
  filterWindow,
  seriesFromBuckets,
  useWidgetBuckets,
  useWidgetRows,
  type GroupByDate,
  type Metric,
} from "@/lib/widget-data";
import { rangeSpanDays, resolveRange } from "@/lib/time-range";
import { useTimeRange } from "@/providers/time-range-provider";
import type { DashboardWidgetMeta } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { Sparkline } from "@/components/sparkline";
import { cn } from "@/lib/utils";

/**
 * A KPI tile with momentum: the headline figure (the same aggregate the `metric` card shows),
 * the period-over-period change versus the prior bucket, and a sparkline of the whole series.
 * Source/aggregate config matches the chart widget (`metric`/`metricField`, `groupBy`/`groupByDate`);
 * `kind` picks the sparkline shape and `colors` its accent.
 */
const toLocalIso = (ms: number) => format(new Date(ms), "yyyy-MM-dd'T'HH:mm:ss");
const absoluteRange = (from: number, to: number) => ({
  kind: "absolute" as const,
  from: toLocalIso(from),
  to: toLocalIso(to),
});

function granularityForStat(spanDays: number): GroupByDate {
  if (spanDays <= 2) return "hour";
  if (spanDays <= 60) return "day";
  if (spanDays <= 240) return "week";
  return "month";
}

export function StatWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const cfg = widget.extraConfig ?? {};
  const showTrend = cfg.trend !== "false" && cfg.sparkline !== "false";
  const { range } = useTimeRange();
  // Catalog/document tiles fetch pre-aggregated buckets (#199); registers keep the row path, so
  // hand useWidgetRows an entityType it ignores on the bucket path (the hook must still be called).
  const isRegister = widget.entityType === "register";
  const isDocument = widget.entityType === "document";
  const rowsWidget = useMemo(() => (isRegister ? widget : { ...widget, entityType: "" }), [isRegister, widget]);
  const windowField = widget.dateField || (isRegister ? "_period" : "_date");
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
  const previousRange = useMemo(() => {
    if (windowRange.from === -Infinity || windowRange.to === Infinity) return null;
    const span = windowRange.to - windowRange.from;
    if (!Number.isFinite(span) || span <= 0) return null;
    return absoluteRange(windowRange.from - span, windowRange.from);
  }, [windowRange]);
  const previousRegisterTurnoverRange = useMemo(
    () => (previousRange ? { from: previousRange.from, to: previousRange.to } : undefined),
    [previousRange]
  );
  const items = useWidgetRows(rowsWidget, registerTurnoverRange);
  const previousRegisterItems = useWidgetRows(rowsWidget, previousRegisterTurnoverRange);
  const rangedItems = useMemo(
    () => (isRegister ? items : filterWindow(items, windowField, range)),
    [isRegister, items, windowField, range]
  );
  const previousItems = useMemo(
    () => (isRegister ? (previousRange ? previousRegisterItems : []) : previousRange ? filterWindow(items, windowField, previousRange) : []),
    [isRegister, previousRegisterItems, items, windowField, previousRange]
  );

  const metric = (cfg.metric as Metric) ?? "count";
  const metricField = cfg.metricField;
  const groupBy = cfg.groupBy ?? "_date";
  // Default to monthly buckets for the trend — a day-over-day delta on a long series is noise.
  const groupByDate =
    (cfg.groupByDate as GroupByDate | undefined) ??
    (groupBy === "_date" || !cfg.groupBy ? "month" : undefined);
  const spanDays = useMemo(() => rangeSpanDays(range, 365), [range]);
  const effGroupByDate = showTrend && (groupBy === "_date" || groupBy === "_period")
    ? granularityForStat(spanDays)
    : groupByDate;
  const color = resolveColor(cfg.colors);
  const fmtNum = (n: number) =>
    formatCompact(n, {
      currency: cfg.currency,
      unit: cfg.unit,
      unitPosition: cfg.unitPosition,
      format: cfg.format ?? (metric === "count" ? "integer" : undefined),
      locale: cfg.locale,
    });
  const fmtFull = (n: number) =>
    formatNumber(n, {
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
      if (effGroupByDate) p.groupByDate = effGroupByDate;
    }
    if (cfg.filter) p.filter = cfg.filter;
    if (isDocument || windowField !== "_date") {
      p.dateField = windowField;
      if (windowRange.from !== -Infinity) p.from = toLocalIso(windowRange.from);
      if (windowRange.to !== Infinity) p.to = toLocalIso(windowRange.to);
    }
    return p;
  }, [isRegister, isDocument, metric, metricField, groupBy, effGroupByDate, widget.entityType, cfg.filter, windowField, windowRange]);
  const resp = useWidgetBuckets(widget, params);
  const previousParams = useMemo(() => {
    if (isRegister || !params || !previousRange) return null;
    return {
      ...params,
      from: previousRange.from,
      to: previousRange.to,
    };
  }, [isRegister, params, previousRange]);
  const previousResp = useWidgetBuckets(widget, previousParams);

  const series = useMemo(
    () =>
      isRegister
        ? buildSeries(rangedItems, { groupBy, groupByDate: effGroupByDate, metric, metricField })
        : seriesFromBuckets(resp ?? { buckets: [], truncated: false }, { groupBy, groupByDate: effGroupByDate, metric, metricField }),
    [isRegister, rangedItems, resp, groupBy, effGroupByDate, metric, metricField]
  );
  const previousSeries = useMemo(
    () =>
      isRegister
        ? buildSeries(previousItems, { groupBy, groupByDate: effGroupByDate, metric, metricField })
        : seriesFromBuckets(previousResp ?? { buckets: [], truncated: false }, { groupBy, groupByDate: effGroupByDate, metric, metricField }),
    [isRegister, previousItems, previousResp, groupBy, effGroupByDate, metric, metricField]
  );

  const points = series.rows.map((r) => ({ value: Number(r.value) || 0 }));
  const delta = previousSeries.total !== 0 ? (series.total - previousSeries.total) / previousSeries.total : null;

  const Arrow = delta == null || delta === 0 ? ArrowRight : delta > 0 ? ArrowUpRight : ArrowDownRight;
  const deltaClass =
    delta == null || delta === 0 ? "text-muted-foreground" : delta > 0 ? "text-success" : "text-destructive";

  return (
    <Card className="overflow-hidden">
      <CardContent
        className={cn(
          "p-4",
          !showTrend && "grid min-h-[128px] grid-rows-[auto_1fr] gap-2"
        )}
      >
        <div className="flex items-center gap-1.5">
          <span className="text-[13px] font-medium text-muted-foreground">{widget.title}</span>
          <HintIcon text={widget.hint} size={13} />
        </div>
        <div className={cn("mt-1 flex items-baseline gap-2", !showTrend && "mt-0 flex-col items-center justify-center gap-2 text-center")}>
          <div className="flex items-baseline gap-2">
            <span className={cn("font-semibold leading-none tabular-nums", showTrend ? "text-[28px]" : "text-[46px] tracking-normal")}>
              {fmtNum(series.total)}
            </span>
            {showTrend && delta != null && (
              <span className={`flex items-center gap-0.5 text-xs font-medium ${deltaClass}`}>
                <Arrow size={13} />
                {Math.abs(delta * 100).toFixed(1)}%
              </span>
            )}
          </div>
          {!showTrend && (
            <span className="rounded-control border border-border/70 bg-muted/30 px-2 py-0.5 text-[11px] font-medium tabular-nums text-muted-foreground">
              {fmtFull(series.total)}
            </span>
          )}
        </div>
        {showTrend ? (
          <div className="mt-2.5">
            <Sparkline data={points} color={color} kind={cfg.kind === "line" ? "line" : "area"} height={44} />
          </div>
        ) : null}
        {showTrend && delta != null && (
          <div className="mt-1 text-[11px] text-muted-foreground">vs previous period</div>
        )}
      </CardContent>
    </Card>
  );
}
