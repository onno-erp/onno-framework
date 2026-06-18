import { useId } from "react";
import { Area, AreaChart, Line, LineChart, ResponsiveContainer } from "recharts";

/**
 * A compact, axis-less trend line — the chart half of the stat and sparkline widgets. An area
 * fill (the default) reads as a filled trend; `kind="line"` is a bare stroke. Both take a single
 * resolved CSS color so they track the theme palette / brand and any `config("colors", ...)`.
 */
export function Sparkline({
  data,
  color,
  kind = "area",
  height = 48,
}: {
  data: Array<{ value: number }>;
  color: string;
  kind?: "area" | "line";
  height?: number;
}) {
  // A unique gradient id per instance — SVG ids are document-global, so two sparklines on the
  // same page would otherwise share (and fight over) one <linearGradient>.
  const gradientId = `spark-${useId().replace(/:/g, "")}`;

  if (data.length === 0) {
    return <div style={{ height }} />;
  }

  return (
    <ResponsiveContainer width="100%" height={height}>
      {kind === "line" ? (
        <LineChart data={data} margin={{ top: 2, right: 2, bottom: 2, left: 2 }}>
          <Line type="monotone" dataKey="value" stroke={color} strokeWidth={2} dot={false} isAnimationActive={false} />
        </LineChart>
      ) : (
        <AreaChart data={data} margin={{ top: 2, right: 2, bottom: 2, left: 2 }}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.35} />
              <stop offset="100%" stopColor={color} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <Area
            type="monotone"
            dataKey="value"
            stroke={color}
            strokeWidth={2}
            fill={`url(#${gradientId})`}
            dot={false}
            isAnimationActive={false}
          />
        </AreaChart>
      )}
    </ResponsiveContainer>
  );
}
