import { PolarAngleAxis, RadialBar, RadialBarChart, ResponsiveContainer } from "recharts";
import { formatCompact } from "../../lib/format";
import { resolveColor } from "../../lib/chart-colors";
import type { MeasureSpec } from "../../lib/measure";
import { Boundary } from "../pages/boundary";
import { Card, CardContent } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { AnimatedNumber } from "../ui/animated-number";
import { useMeasureSeries } from "./stat";

/**
 * A radial progress gauge: a `measure` rendered as a ring filled toward a `target` — a fixed number
 * or another measure (e.g. the plan). With a target it shows the percentage reached and
 * "of {target}"; with none it degrades to a full ring around the bare value.
 */
export function Gauge(props: GaugeProps) {
  return (
    <Boundary label={props.title || "Gauge"}>
      <GaugeBody {...props} />
    </Boundary>
  );
}

/** Props of {@link Gauge}. */
export interface GaugeProps {
  /** Card heading. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** What is measured, e.g. `count("Orders", { posted: true })`. */
  measure: MeasureSpec;
  /** The value the ring fills toward: a number or a measure; without one (or ≤ 0) the ring is full. */
  target?: number | MeasureSpec;
  /** Ring accent: palette tokens (`"chart-2"`, `"success"`) or CSS colors; the first is used. */
  colors?: string[];
  /** ISO 4217 code, e.g. `"USD"`. */
  currency?: string;
  /** A free unit label instead of a currency (`"kg"`, `"pcs"`). */
  unit?: string;
  /** Which side of the number the unit goes (default suffix). */
  unitPosition?: "prefix" | "suffix";
  /** Number style (default `integer` for `count`). */
  format?: "integer" | "decimal";
  /** BCP 47 locale for number formatting (default: the browser's). */
  locale?: string;
}

function GaugeBody(props: GaugeProps) {
  const { title, hint, measure } = props;
  // One grand-total bucket each (no grouping), aggregated server-side.
  const { source, series } = useMeasureSeries(measure);
  const targetMeasure = typeof props.target === "object" && props.target ? props.target : null;
  const targetSeries = useMeasureSeries(targetMeasure);
  const value = series.total;
  const color = resolveColor(props.colors?.join(","));
  const metric = measure.aggregation;
  const rawTarget = targetMeasure
    ? targetSeries.source ? targetSeries.series.total : null
    : typeof props.target === "number" ? props.target : null;
  const target = rawTarget != null && Number.isFinite(rawTarget) ? rawTarget : null;
  const hasTarget = target != null && target > 0;
  const pct = hasTarget ? Math.max(0, Math.min(100, (value / target) * 100)) : 100;

  const fmtNum = (n: number) =>
    formatCompact(n, {
      currency: props.currency,
      unit: props.unit,
      unitPosition: props.unitPosition,
      format: props.format ?? (metric === "count" ? "integer" : undefined),
      locale: props.locale,
    });

  return (
    <Card className="overflow-hidden">
      <CardContent className="p-4">
        <div className="flex items-center gap-1.5">
          <span className="text-[13px] font-medium text-muted-foreground">{title}</span>
          <HintIcon text={hint} size={13} />
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
            <AnimatedNumber
              value={source ? fmtNum(value) : "—"}
              className="text-[24px] font-semibold leading-none tabular-nums"
            />
            {source && hasTarget && (
              <span className="mt-1.5 text-[11px] text-muted-foreground">
                {Math.round(pct)}% of {fmtNum(target!)}
              </span>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
