import { describe, expect, it } from "vitest";
import {
  chartConfig,
  chartHeadlineValue,
  chartRequest,
  mergeSeries,
  seriesData,
} from "@/components/widgets/chart";
import { count, sum } from "@/lib/measure";
import { ISO_KEY, SINGLE_SERIES, type SeriesData } from "@/lib/aggregate-data";

const ALL_TIME = { from: -Infinity, to: Infinity };

describe("typed chart appearance config", () => {
  it("reads series styling, axes, thresholds, and display controls from typed props", () => {
    const config = chartConfig({
      title: "Revenue",
      type: "area",
      strokeWidth: 3,
      lineStyle: "dashed",
      opacity: 0.7,
      series: [
        { measure: sum("Orders", "total"), color: "primary" },
        { measure: count("Orders"), type: "bar", color: "warning", currency: "USD", axis: "right", label: "Orders" },
      ],
      bucketMode: "fixed",
      maxSeries: 5,
      seriesColors: { Wholesale: "#7c3aed" },
      axes: { left: { min: 0, max: 50000, label: "Revenue" }, right: { scale: "log" } },
      thresholds: [{ value: 20000, label: "Target", color: "success", axis: "right", style: "dotted" }],
      legend: "top",
      dataLabels: "auto",
      curve: "linear",
      points: true,
      grid: false,
      height: 320,
      barSize: 24,
      donutHole: 70,
    });

    expect(config.multi).toBe(true);
    expect(config.series).toEqual([
      expect.objectContaining({ key: "s0", type: "area", label: "Revenue", color: "primary", axis: "left",
        strokeWidth: 3, lineStyle: "dashed", opacity: 0.7, format: undefined }),
      expect.objectContaining({ key: "s1", type: "bar", label: "Orders", color: "warning", axis: "right",
        currency: "USD", format: "integer" }),
    ]);
    expect(config.bucketMode).toBe("fixed");
    expect(config.maxSeries).toBe(5);
    expect(config.seriesColors).toEqual({ Wholesale: "#7c3aed" });
    expect(config.axes.left).toMatchObject({ min: 0, max: 50000, label: "Revenue" });
    expect(config.axes.right.scale).toBe("log");
    expect(config.thresholds).toEqual([{
      value: 20000,
      label: "Target",
      color: "success",
      axis: "right",
      style: "dotted",
      width: 2,
    }]);
    expect(config).toMatchObject({
      legend: "top",
      dataLabels: "auto",
      curve: "linear",
      points: true,
      grid: false,
      height: 320,
      barSize: 24,
      donutHole: 70,
    });
  });

  it("applies the defaults to the one-series shorthand", () => {
    const config = chartConfig({ title: "Orders", measure: count("Orders") });
    expect(config).toMatchObject({
      type: "bar",
      multi: false,
      groupBy: undefined,
      groupByDate: "day",
      stacked: false,
      legend: "bottom",
      curve: "monotone",
      dataLabels: "hidden",
      grid: true,
      points: false,
      height: 210,
      barSize: 48,
      donutHole: 63,
      maxSeries: 8,
      bucketMode: "auto",
      thresholds: [],
    });
    expect(config.series).toEqual([expect.objectContaining({ key: SINGLE_SERIES, label: "Orders", format: "integer", axis: "left" })]);
    expect(config.axes.left).toMatchObject({ scale: "linear", visible: true });
  });

  it("reads `by` as a field or a bucketed date field", () => {
    expect(chartConfig({ measure: count("Orders"), by: "status" })).toMatchObject({ groupBy: "status", groupByDate: undefined });
    expect(chartConfig({ measure: count("Orders"), by: { createdAt: "week" } })).toMatchObject({ groupBy: "createdAt", groupByDate: "week" });
    expect(chartConfig({ measure: count("Orders"), by: "date" })).toMatchObject({ groupBy: "date", groupByDate: "day" });
  });

  it("clamps out-of-range numbers, drops invalid thresholds, and maps legend none to hidden", () => {
    const config = chartConfig({
      measure: count("Orders"),
      height: 5000,
      opacity: 3,
      strokeWidth: 0,
      legend: "none",
      thresholds: [{ value: Number.NaN }, { value: 10, width: 40 }],
    });
    expect(config.height).toBe(800);
    expect(config.series[0].opacity).toBe(1);
    expect(config.series[0].strokeWidth).toBe(1);
    expect(config.legend).toBe("hidden");
    expect(config.thresholds).toEqual([{ value: 10, label: undefined, color: undefined, axis: "left", style: "dashed", width: 12 }]);
  });

  it("plots a pie type beside other series as bars", () => {
    const config = chartConfig({ type: "pie", series: [{ measure: count("Orders") }, { measure: sum("Orders", "total") }] });
    expect(config.series.map((s) => s.type)).toEqual(["bar", "bar"]);
  });
});

describe("chart requests", () => {
  const window = { from: Date.parse("2026-01-01T00:00:00"), to: Date.parse("2026-02-01T00:00:00") };

  it("sends the measure, its where as JSON, the grouping and the window to the aggregate endpoint", () => {
    const config = chartConfig({ measure: sum("Orders", "total", { posted: true, total: { gte: 100 } }), by: "status" });
    const request = chartRequest(config.series[0], config, "document", "Orders", { window });
    expect(request).toEqual({
      entityType: "document",
      entityName: "Orders",
      params: {
        metric: "sum",
        field: "total",
        groupBy: "status",
        where: JSON.stringify({ posted: true, total: { gte: 100 } }),
        dateField: "date",
        from: "2026-01-01T00:00:00",
        to: "2026-02-01T00:00:00",
      },
    });
    expect(request.params.filter).toBeUndefined();
  });

  it("buckets a date grouping at the effective granularity, windows by that field, and splits", () => {
    const config = chartConfig({ measure: count("Orders"), by: { createdAt: "week" } });
    const request = chartRequest(config.series[0], config, "catalog", "Orders", { window: ALL_TIME, granularity: "month", splitBy: "channel" });
    expect(request.params).toEqual({
      metric: "count",
      groupBy: "createdAt",
      groupByDate: "month",
      seriesBy: "channel",
      dateField: "createdAt",
    });
  });

  it("asks an un-grouped catalog for the grand total without a date window", () => {
    const config = chartConfig({ measure: count("Customers") });
    const request = chartRequest(config.series[0], config, "catalog", "Customers", { window, granularity: "day" });
    expect(request.params).toEqual({ metric: "count" });
  });

  it("asks the register aggregate for a measure over the movements, its where included, windowed by period", () => {
    const config = chartConfig({ measure: sum("Stock", "quantity", { warehouse: "w-1", movementType: "RECEIPT" }) });
    expect(chartRequest(config.series[0], config, "register", "Stock", { window, granularity: "day" })).toEqual({
      entityType: "register",
      entityName: "Stock",
      params: {
        metric: "sum",
        field: "quantity",
        groupBy: "period",
        groupByDate: "day",
        where: JSON.stringify({ warehouse: "w-1", movementType: "RECEIPT" }),
        dateField: "period",
        from: "2026-01-01T00:00:00",
        to: "2026-02-01T00:00:00",
      },
    });
  });
});

describe("register series", () => {
  it("shapes the register's server buckets like any entity's, by their resolved labels", () => {
    const config = chartConfig({ measure: sum("Stock", "quantity"), by: "warehouse" });
    const data = seriesData(config.series[0], config, "register", {
      buckets: [{ key: "w-1", label: "Main", value: 5 }, { key: "w-2", label: "Annex", value: 2 }],
      truncated: false,
    }, { round: false });
    expect(data.rows.map((r) => [r.label, r[SINGLE_SERIES]])).toEqual([["Main", 5], ["Annex", 2]]);
  });
});

describe("multi-series merge", () => {
  const part = (rows: Array<[string, string, number]>): SeriesData => ({
    rows: rows.map(([label, iso, value]) => ({ label, [ISO_KEY]: iso, [SINGLE_SERIES]: value })),
    seriesKeys: [SINGLE_SERIES],
    total: 0,
  });

  it("joins date buckets by start date in chronological order, zero-filling gaps", () => {
    const merged = mergeSeries([
      part([["Jan 2", "2026-01-02", 3], ["Jan 1", "2026-01-01", 1]]),
      part([["Jan 3", "2026-01-03", 9], ["Jan 1", "2026-01-01", 4]]),
    ], ["s0", "s1"]);
    expect(merged.seriesKeys).toEqual(["s0", "s1"]);
    expect(merged.rows.map((r) => [r.label, r.s0, r.s1])).toEqual([
      ["Jan 1", 1, 4],
      ["Jan 2", 3, 0],
      ["Jan 3", 0, 9],
    ]);
  });

  it("joins categories by label in first-seen order", () => {
    const merged = mergeSeries([part([["New", "", 2]]), part([["Shipped", "", 5], ["New", "", 1]])], ["s0", "s1"]);
    expect(merged.rows.map((r) => [r.label, r.s0, r.s1])).toEqual([["New", 2, 1], ["Shipped", 0, 5]]);
  });
});

describe("chart headline summary", () => {
  const series = (values: number[]): SeriesData => ({
    rows: values.map((value, index) => ({ label: `Bucket ${index + 1}`, value })),
    seriesKeys: [SINGLE_SERIES],
    total: values.reduce((sum, value) => sum + value, 0),
  });

  it("keeps additive totals across grouped buckets", () => {
    expect(chartHeadlineValue("count", series([2, 3, 4]))).toBe(9);
    expect(chartHeadlineValue("sum", series([100, 100, 100, 100, 100]))).toBe(500);
  });

  it("hides misleading grouped non-additive totals", () => {
    const percentages = series([100, 100, 100, 100, 100]);

    expect(chartHeadlineValue("avg", percentages)).toBeNull();
    expect(chartHeadlineValue("min", percentages)).toBeNull();
    expect(chartHeadlineValue("max", percentages)).toBeNull();
  });

  it("keeps one unsplit non-additive aggregate", () => {
    expect(chartHeadlineValue("max", series([100]))).toBe(100);
  });

  it("hides a non-additive total split into several series", () => {
    const split: SeriesData = {
      rows: [{ label: "All", Current: 40, Previous: 60 }],
      seriesKeys: ["Current", "Previous"],
      total: 100,
    };

    expect(chartHeadlineValue("avg", split)).toBeNull();
  });
});
