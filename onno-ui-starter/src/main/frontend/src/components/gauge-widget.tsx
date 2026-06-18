import { useMemo } from "react";
import { PolarAngleAxis, RadialBar, RadialBarChart, ResponsiveContainer } from "recharts";
import { formatCompact, toNumber } from "@/lib/format";
import { resolveColor } from "@/lib/chart-colors";
import { aggregate, useWidgetRows, type Metric } from "@/lib/widget-data";
import type { DashboardWidgetMeta } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";

/**
 * A radial progress gauge: an aggregate value (`metric`/`metricField`, like the `metric` card)
 * rendered as a ring filled toward a `target`. With a target it shows the percentage reached and
 * "of {target}"; with no target it degrades to a full ring around the bare value. `colors` sets
 * the ring accent.
 */
export function GaugeWidget({ widget }: { widget: DashboardWidgetMeta }) {
  const cfg = widget.extraConfig ?? {};
  const items = useWidgetRows(widget);

  const metric = (cfg.metric as Metric) ?? "count";
  const color = resolveColor(cfg.colors);
  const value = useMemo(
    () => aggregate(items, { metric, metricField: cfg.metricField }),
    [items, metric, cfg.metricField]
  );
  const target = toNumber(cfg.target ?? "");
  const hasTarget = target != null && target > 0;
  const pct = hasTarget ? Math.max(0, Math.min(100, (value / target) * 100)) : 100;

  const fmtNum = (n: number) =>
    formatCompact(n, {
      currency: cfg.currency,
      unit: cfg.unit,
      unitPosition: cfg.unitPosition,
      format: cfg.format ?? (metric === "count" ? "integer" : undefined),
      locale: cfg.locale,
    });

  return (
    <Card className="overflow-hidden">
      <CardContent className="p-4">
        <div className="flex items-center gap-1.5">
          <span className="text-[13px] font-medium text-muted-foreground">{widget.title}</span>
          <HintIcon text={widget.hint} size={13} />
        </div>
        <div className="relative mt-1" style={{ height: 168 }}>
          <ResponsiveContainer width="100%" height="100%">
            <RadialBarChart innerRadius="74%" outerRadius="100%" data={[{ value: pct }]} startAngle={90} endAngle={-270}>
              <PolarAngleAxis type="number" domain={[0, 100]} tick={false} axisLine={false} />
              <RadialBar
                background={{ fill: "hsl(var(--muted))" }}
                dataKey="value"
                cornerRadius={999}
                fill={color}
                isAnimationActive={false}
              />
            </RadialBarChart>
          </ResponsiveContainer>
          {/* recharts has no built-in centerpiece, so overlay the readout in the ring's hole. */}
          <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-[24px] font-semibold leading-none tabular-nums">{fmtNum(value)}</span>
            {hasTarget && (
              <span className="mt-1.5 text-[11px] text-muted-foreground">
                {Math.round(pct)}% of {fmtNum(target)}
              </span>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
