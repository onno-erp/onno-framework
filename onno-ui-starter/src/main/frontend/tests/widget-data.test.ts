import { describe, expect, it } from "vitest";
import { aggregate, buildSeries, SINGLE_SERIES } from "@/lib/widget-data";
import type { EntityRecord } from "@/lib/types";

describe("aggregate", () => {
  const rows: EntityRecord[] = [{ gross: 10 }, { gross: 20 }, { gross: "5" }, { gross: null }];

  it("counts rows", () => {
    expect(aggregate(rows, { metric: "count" })).toBe(4);
  });

  it("sums a numeric field, coercing numeric strings and skipping non-numbers", () => {
    expect(aggregate(rows, { metric: "sum", metricField: "gross" })).toBe(35);
  });

  it("sums to 0 when no metricField is given", () => {
    expect(aggregate(rows, { metric: "sum" })).toBe(0);
  });
});

describe("buildSeries — single series", () => {
  it("buckets by a categorical field and totals", () => {
    const rows: EntityRecord[] = [
      { status: "open", amt: 1 },
      { status: "open", amt: 2 },
      { status: "closed", amt: 4 },
    ];
    const out = buildSeries(rows, { groupBy: "status", metric: "sum", metricField: "amt" });
    expect(out.seriesKeys).toEqual([SINGLE_SERIES]);
    expect(out.total).toBe(7);
    // categorical buckets keep insertion order
    expect(out.rows).toEqual([
      { label: "open", value: 3 },
      { label: "closed", value: 4 },
    ]);
  });

  it("orders date buckets chronologically regardless of row order", () => {
    const rows: EntityRecord[] = [
      { _date: "2024-03-10T00:00:00" },
      { _date: "2024-01-05T00:00:00" },
      { _date: "2024-01-20T00:00:00" },
      { _date: "2024-02-01T00:00:00" },
    ];
    const out = buildSeries(rows, { groupBy: "_date", groupByDate: "month", metric: "count" });
    expect(out.rows.map((r) => r.label)).toEqual(["Jan 2024", "Feb 2024", "Mar 2024"]);
    expect(out.rows.map((r) => r.value)).toEqual([2, 1, 1]);
    expect(out.total).toBe(4);
  });
});

describe("buildSeries — multi series", () => {
  it("splits buckets by seriesBy and ranks series by total descending", () => {
    const rows: EntityRecord[] = [
      { _date: "2024-01-01T00:00:00", prop: "A", amt: 5 },
      { _date: "2024-01-01T00:00:00", prop: "B", amt: 1 },
      { _date: "2024-02-01T00:00:00", prop: "A", amt: 3 },
      { _date: "2024-02-01T00:00:00", prop: "B", amt: 10 },
    ];
    const out = buildSeries(rows, {
      groupBy: "_date",
      groupByDate: "month",
      seriesBy: "prop",
      metric: "sum",
      metricField: "amt",
    });
    // B (11) outranks A (8)
    expect(out.seriesKeys).toEqual(["B", "A"]);
    expect(out.rows).toEqual([
      { label: "Jan 2024", B: 1, A: 5 },
      { label: "Feb 2024", B: 10, A: 3 },
    ]);
    expect(out.total).toBe(19);
  });

  it("folds the long tail beyond maxSeries into 'Other'", () => {
    const rows: EntityRecord[] = [
      { g: "x", k: "a", n: 100 },
      { g: "x", k: "b", n: 50 },
      { g: "x", k: "c", n: 10 },
      { g: "x", k: "d", n: 5 },
    ];
    const out = buildSeries(rows, { groupBy: "g", seriesBy: "k", metric: "sum", metricField: "n", maxSeries: 2 });
    // keep top (maxSeries-1)=1 → "a", everything else folds into "Other"
    expect(out.seriesKeys).toEqual(["a", "Other"]);
    expect(out.rows).toEqual([{ label: "x", a: 100, Other: 65 }]);
    expect(out.total).toBe(165);
  });
});
