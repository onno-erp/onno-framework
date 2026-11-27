import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { chartConfig, rowHeadlineValue, rowSeriesData } from "@/components/widgets/chart";
import { rowBucket, rowsAreDated, seriesFromRows } from "@/lib/row-series";
import { ISO_KEY, SINGLE_SERIES } from "@/lib/aggregate-data";

vi.mock("@/providers/time-range-provider", () => ({
  useTimeRange: () => ({
    range: { kind: "absolute", from: "2026-01-01T00:00:00", to: "2026-02-01T00:00:00" },
    granularity: "auto",
    setAbsolute: () => {},
  }),
}));
vi.mock("@/components/ui/animated-number", () => ({
  AnimatedNumber: ({ value }: { value: string }) => <span data-testid="headline">{value}</span>,
}));
vi.mock("@/components/widgets/trend-line", () => ({
  TrendLine: ({ data }: { data: Array<{ value: number }> }) => <div data-testid="sparkline">{data.map((p) => p.value).join(",")}</div>,
}));

import { Chart } from "@/components/widgets/chart";
import { Stat } from "@/components/widgets/stat";
import { Sparkline } from "@/components/widgets/sparkline";

const days = [
  { day: "2026-11-03", reach: 120, chats: 1, source: "Instagram" },
  { day: "2026-11-01", reach: 100, chats: 0, source: "Website" },
  { day: "2026-11-02", reach: null, chats: 2, source: "Instagram" },
  { day: "2026-11-03", reach: 30, chats: 0, source: "Website" },
];

let fetchSpy: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchSpy = vi.fn(async () => { throw new Error("a data chart never fetches"); });
  vi.stubGlobal("fetch", fetchSpy);
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("rows → series", () => {
  it("orders date rows, adds up rows of the same day and keeps an empty day as 0", () => {
    const data = seriesFromRows(days, { x: "day", value: "reach" });
    expect(data.rows.map((r) => r[ISO_KEY])).toEqual(["2026-11-01", "2026-11-02", "2026-11-03"]);
    expect(data.rows.map((r) => r[SINGLE_SERIES])).toEqual([100, 0, 150]);
    expect(data.total).toBe(250);
  });

  it("keeps categories in the order given and splits by a key, largest first", () => {
    const rows = [{ src: "Google", leads: 3 }, { src: "Instagram", leads: 5 }, { src: "Google", leads: 1 }];
    expect(seriesFromRows(rows, { x: "src", value: "leads" }).rows.map((r) => [r.label, r[SINGLE_SERIES]])).toEqual([["Google", 4], ["Instagram", 5]]);
    const split = seriesFromRows(days, { x: "day", value: "chats", splitBy: "source" });
    expect(split.seriesKeys).toEqual(["Instagram", "Website"]);
    expect(split.rows.find((r) => r[ISO_KEY] === "2026-11-02")?.Instagram).toBe(2);
  });

  it("folds the tail beyond maxSeries into Other", () => {
    const rows = ["a", "b", "c", "d"].map((k, i) => ({ x: "2026-01-01", k, v: 10 - i }));
    expect(seriesFromRows(rows, { x: "x", value: "v", splitBy: "k", maxSeries: 3 }).seriesKeys).toEqual(["a", "b", "Other"]);
  });

  it("buckets dates by span unless told, and treats Date objects as dates", () => {
    const long = Array.from({ length: 200 }, (_, i) => ({ d: new Date(2026, 0, 1 + i), v: 1 }));
    expect(rowsAreDated(long, "d")).toBe(true);
    expect(rowBucket(long, "d")).toBe("week");
    expect(seriesFromRows(long, { x: "d", value: "v", bucket: "month" }).rows).toHaveLength(7);
    expect(rowsAreDated([{ d: "June" }], "d")).toBe(false);
  });
});

describe("Chart with data", () => {
  it("plots rows without an entity or a request; headline sums, or takes the last bucket", () => {
    render(<Chart title="Reach" data={days} x="day" value="reach" />);
    expect(screen.getByText("Reach")).toBeTruthy();
    expect(screen.getByTestId("headline").textContent).toBe("250");
    expect(fetchSpy).not.toHaveBeenCalled();

    const config = chartConfig({ data: days, x: "day", value: "reach" });
    const data = rowSeriesData(config.series[0], { data: days, x: "day" }, config, { round: false });
    expect(rowHeadlineValue("last", data)).toBe(150);
    expect(rowHeadlineValue("none", data)).toBeNull();
  });

  it("takes several row keys as series, each on its own key", () => {
    const config = chartConfig({ data: days, x: "day", series: [{ value: "reach" }, { value: "chats", axis: "right", type: "line" }] });
    expect(config.multi).toBe(true);
    expect(config.series.map((s) => [s.value, s.measure, s.axis, s.label])).toEqual([["reach", undefined, "left", "reach"], ["chats", undefined, "right", "chats"]]);
  });

  it("keeps entity charts as they were", () => {
    const config = chartConfig({ measure: { entity: "Orders", aggregation: "count" } });
    expect(config.series[0].measure?.entity).toBe("Orders");
    expect(config.series[0].value).toBeUndefined();
    expect(config.series[0].format).toBe("integer");
  });
});

describe("Stat and Sparkline with a value", () => {
  it("shows the given figure, its change and its points without requests", () => {
    render(<Stat title="Followers" value={1100} previous={1000} points={[1000, 1050, 1100]} />);
    expect(screen.getByTestId("headline").textContent).toBe("1K");
    expect(screen.getByText("10.0%")).toBeTruthy();
    expect(screen.getByTestId("sparkline").textContent).toBe("1000,1050,1100");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("a headline-only value prints the exact figure", () => {
    render(<Stat title="Events" value={7} />);
    expect(screen.getByTestId("headline").textContent).toBe("7");
  });

  it("a sparkline sums its points unless given a value", () => {
    render(<Sparkline title="Chats" points={[1, 2, 3]} />);
    expect(screen.getByTestId("headline").textContent).toBe("6");
    cleanup();
    render(<Sparkline title="Followers" points={[10, 12]} value={12} />);
    expect(screen.getByTestId("headline").textContent).toBe("12");
  });
});
