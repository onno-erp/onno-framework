import { describe, expect, it } from "vitest";
import { chartHeadlineValue, readChartConfig } from "@/components/chart-widget";
import { SINGLE_SERIES, type SeriesData } from "@/lib/widget-data";
import type { DashboardWidgetMeta } from "@/lib/types";

const widget = (extraConfig: Record<string, string>): DashboardWidgetMeta => ({
  title: "Revenue",
  widgetType: "chart",
  order: 0,
  width: "full",
  entityType: "document",
  entityName: "orders",
  maxItems: 10,
  dateField: "date",
  titleField: "number",
  extraConfig,
});

describe("typed chart appearance config", () => {
  it("parses measure styling, axes, thresholds, and display controls", () => {
    const config = readChartConfig(widget({
      metric: "sum",
      metricField: "total",
      kind: "area",
      color: "primary",
      strokeWidth: "3",
      lineStyle: "dashed",
      opacity: "0.7",
      measure2: "count",
      kind2: "bar",
      color2: "warning",
      currency2: "USD",
      bucketMode: "fixed",
      maxSeries: "5",
      "seriesColor.Wholesale": "#7c3aed",
      yMin: "0",
      yMax: "50000",
      yLabel: "Revenue",
      y2Scale: "log",
      "threshold.0.value": "20000",
      "threshold.0.label": "Target",
      "threshold.0.color": "success",
      "threshold.0.axis": "right",
      "threshold.0.style": "dotted",
      legend: "top",
      dataLabels: "auto",
      curve: "linear",
      points: "true",
      grid: "false",
      height: "320",
      barSize: "24",
      donutHole: "70",
    }));

    expect(config.primaryColor).toBe("primary");
    expect(config.secondaryColor).toBe("warning");
    expect(config.secondaryCurrency).toBe("USD");
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
      primaryStrokeWidth: 3,
      primaryLineStyle: "dashed",
      primaryOpacity: 0.7,
    });
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
