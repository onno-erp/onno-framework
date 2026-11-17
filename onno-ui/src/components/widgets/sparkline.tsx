import { formatCompact } from "../../lib/format";
import { resolveColor } from "../../lib/chart-colors";
import type { GroupSpec, MeasureSpec } from "../../lib/measure";
import { Boundary } from "../pages/boundary";
import { Card, CardContent } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { TrendLine } from "./trend-line";
import { AnimatedNumber } from "../ui/animated-number";
import { useMeasureSeries } from "./stat";

/**
 * The minimal trend tile: a title, the measure's figure, and an inline sparkline — no delta badge
 * (that's the {@link Stat}). `by` picks the sparkline's buckets (default: the date field by day).
 */
export function Sparkline(props: SparklineProps) {
  return (
    <Boundary label={props.title || "Sparkline"}>
      <SparklineBody {...props} />
    </Boundary>
  );
}

/** Props of {@link Sparkline}. */
export interface SparklineProps {
  /** Card heading. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** What is measured, e.g. `sum("Orders", "total", { posted: true })`. */
  measure: MeasureSpec;
  /** The sparkline's buckets, e.g. `{ date: "week" }` (default: the measure's date field by day). */
  by?: GroupSpec;
  /** Sparkline shape (default `area`). */
  type?: "line" | "area";
  /** Accent: palette tokens (`"chart-2"`, `"success"`) or CSS colors; the first is used. */
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

function SparklineBody(props: SparklineProps) {
  const { title, hint, measure, by } = props;
  const { source, series } = useMeasureSeries(measure, { by, dateBucket: by ? undefined : "day" });
  const color = resolveColor(props.colors?.join(","));
  const fmtNum = (n: number) =>
    formatCompact(n, {
      currency: props.currency,
      unit: props.unit,
      unitPosition: props.unitPosition,
      format: props.format ?? (measure.aggregation === "count" ? "integer" : undefined),
      locale: props.locale,
    });
  const points = series.rows.map((r) => ({ value: Number(r.value) || 0 }));

  return (
    <Card className="overflow-hidden">
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <span className="text-[13px] font-medium text-muted-foreground">{title}</span>
            <HintIcon text={hint} size={13} />
          </div>
          <AnimatedNumber
            value={source ? fmtNum(series.total) : "—"}
            className="text-[15px] font-semibold tabular-nums"
          />
        </div>
        <TrendLine data={points} color={color} kind={props.type === "line" ? "line" : "area"} height={40} />
      </CardContent>
    </Card>
  );
}
