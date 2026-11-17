import { describe, expect, it } from "vitest";
import { seriesFromBuckets, SINGLE_SERIES, type AggregateBucket } from "@/lib/aggregate-data";

const of = (buckets: AggregateBucket[]) => ({ buckets, truncated: false });

describe("seriesFromBuckets — single series", () => {
  it("keeps categorical buckets in server order, labelled by the resolved label, and totals", () => {
    const out = seriesFromBuckets(of([
      { key: "u-open", label: "open", value: 3 },
      { key: "u-closed", label: "closed", value: 4 },
    ]), { groupBy: "status", metric: "sum", metricField: "amt" });
    expect(out.seriesKeys).toEqual([SINGLE_SERIES]);
    expect(out.total).toBe(7);
    expect(out.rows).toEqual([
      { label: "open", __iso: "", value: 3 },
      { label: "closed", __iso: "", value: 4 },
    ]);
  });

  it("orders date buckets chronologically regardless of bucket order", () => {
    const out = seriesFromBuckets(of([
      { key: "2024-03-01T00:00", value: 1 },
      { key: "2024-01-01T00:00", value: 2 },
      { key: "2024-02-01T00:00", value: 1 },
    ]), { groupBy: "date", groupByDate: "month", metric: "count" });
    expect(out.rows.map((r) => r.label)).toEqual(["Jan 2024", "Feb 2024", "Mar 2024"]);
    expect(out.rows.map((r) => r.value)).toEqual([2, 1, 1]);
    expect(out.total).toBe(4);
  });
});

describe("seriesFromBuckets — multi series", () => {
  it("splits by the series label (raw value as fallback) and ranks series by total descending", () => {
    const out = seriesFromBuckets(of([
      { key: "2024-01-01T00:00", series: "a", seriesLabel: "A", value: 5 },
      { key: "2024-01-01T00:00", series: "b", value: 1 },
      { key: "2024-02-01T00:00", series: "a", seriesLabel: "A", value: 3 },
      { key: "2024-02-01T00:00", series: "b", value: 10 },
    ]), { groupBy: "date", groupByDate: "month", seriesBy: "prop", metric: "sum", metricField: "amt" });
    // b (11) outranks A (8)
    expect(out.seriesKeys).toEqual(["b", "A"]);
    expect(out.rows).toEqual([
      { label: "Jan 2024", __iso: "2024-01-01", b: 1, A: 5 },
      { label: "Feb 2024", __iso: "2024-02-01", b: 10, A: 3 },
    ]);
    expect(out.total).toBe(19);
  });

  it("folds the long tail beyond maxSeries into 'Other'", () => {
    const out = seriesFromBuckets(of([
      { key: "x", series: "a", value: 100 },
      { key: "x", series: "b", value: 50 },
      { key: "x", series: "c", value: 10 },
      { key: "x", series: "d", value: 5 },
    ]), { groupBy: "g", seriesBy: "k", metric: "sum", metricField: "n", maxSeries: 2 });
    expect(out.seriesKeys).toEqual(["a", "Other"]);
    expect(out.rows).toEqual([{ label: "x", __iso: "", a: 100, Other: 65 }]);
    expect(out.total).toBe(165);
  });

  it("keeps a zero-filled empty period on the axis without inventing a series", () => {
    const out = seriesFromBuckets(of([
      { key: "2024-01-01T00:00", series: "a", value: 2 },
      { key: "2024-02-01T00:00", value: 0 },
    ]), { groupBy: "date", groupByDate: "month", seriesBy: "k", metric: "count" });
    expect(out.seriesKeys).toEqual(["a"]);
    expect(out.rows.map((r) => [r.label, r.a])).toEqual([["Jan 2024", 2], ["Feb 2024", 0]]);
  });
});

describe("seriesFromBuckets — duplicate bucket labels widen until unique", () => {
  it("hour buckets spanning multiple days pick up the day", () => {
    const out = seriesFromBuckets(of([
      { key: "2024-01-01T15:00", value: 1 },
      { key: "2024-01-02T15:00", value: 2 },
      { key: "2024-01-02T18:00", value: 3 },
    ]), { groupBy: "date", groupByDate: "hour", metric: "sum", metricField: "n" });
    // Two different days both bucket to 15:00 — bare "15:00" labels would collide (and break
    // everything recharts resolves by label, e.g. the drag-zoom ReferenceArea).
    expect(out.rows.map((r) => r.label)).toEqual(["Jan 1 15:00", "Jan 2 15:00", "Jan 2 18:00"]);
  });

  it("day buckets spanning multiple years pick up the year", () => {
    const out = seriesFromBuckets(of([
      { key: "2023-03-05T00:00", value: 1 },
      { key: "2024-03-05T00:00", value: 2 },
    ]), { groupBy: "date", groupByDate: "day", metric: "sum", metricField: "n" });
    expect(out.rows.map((r) => r.label)).toEqual(["Mar 5, 2023", "Mar 5, 2024"]);
  });

  it("unique labels keep their compact form", () => {
    const out = seriesFromBuckets(of([
      { key: "2024-01-01T15:00", value: 1 },
      { key: "2024-01-01T18:00", value: 2 },
    ]), { groupBy: "date", groupByDate: "hour", metric: "sum", metricField: "n" });
    expect(out.rows.map((r) => r.label)).toEqual(["15:00", "18:00"]);
  });
});
