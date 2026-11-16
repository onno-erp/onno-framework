import { describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

// Only the caption matters here, so the data layer is a stub that yields a non-zero delta: the
// current window (February) totals 150, the previous one (from January) totals 100.
vi.mock("@/lib/aggregate-data", () => ({
  useAggregateBuckets: (_w: unknown, params: Record<string, string> | null) =>
    params ? { buckets: [], truncated: false, previous: Boolean(params.from?.startsWith("2024-01")) } : null,
  seriesFromBuckets: (resp: { previous?: boolean } | null) => ({
    total: resp?.previous ? 100 : 150,
    rows: [{ label: "a", value: 60 }, { label: "b", value: 90 }],
  }),
}));

vi.mock("@/lib/entity-source", () => ({
  useEntitySource: () => ({ entityType: "catalog", entityName: "Revenue", dateField: "" }),
}));

vi.mock("@/providers/time-range-provider", () => ({
  useTimeRange: () => ({
    range: { kind: "absolute", from: "2024-02-01T00:00:00", to: "2024-03-01T00:00:00" },
  }),
}));

vi.mock("@/components/widgets/trend-line", () => ({ TrendLine: () => <div data-testid="sparkline" /> }));
vi.mock("@/components/ui/animated-number", () => ({
  AnimatedNumber: ({ value }: { value: string }) => <span>{value}</span>,
}));

// A deployment on a non-English locale: the server's messages map is what `t` resolves against.
vi.mock("@/providers/messages-provider", () => ({
  useMessages: () => (key: string) =>
    key === "widget.stat.comparison" ? "к прошлому периоду" : key,
}));

import { Stat, type StatProps } from "@/components/widgets/stat";
import { sum } from "@/lib/measure";

const stat = (props: Partial<StatProps>) =>
  <Stat title="Выручка" measure={{ ...sum("Revenue", "amount"), dateField: "date" }} {...props} />;

describe("Stat comparison caption", () => {
  afterEach(() => cleanup());

  it("honours compareLabel on the trend (sparkline) path", () => {
    // Regression for #396: this branch used to render a hardcoded English literal, so turning the
    // sparkline on silently reverted an authored label.
    render(stat({ compareLabel: "к прошлому периоду, %" }));

    expect(screen.getByText("к прошлому периоду, %")).toBeTruthy();
    expect(screen.queryByText("vs previous period")).toBeNull();
  });

  it("honours compareLabel on the headline-only path", () => {
    render(stat({ trend: false, compare: true, compareLabel: "к прошлому периоду, %" }));

    expect(screen.getByText("к прошлому периоду, %")).toBeTruthy();
    // 150 vs 100: the badge reads +50%.
    expect(screen.getByText(/50\.0%/)).toBeTruthy();
  });

  it("falls back to the message catalogue, not an English literal, on both paths", () => {
    const { unmount } = render(stat({}));
    expect(screen.getByText("к прошлому периоду")).toBeTruthy();
    expect(screen.queryByText("vs previous period")).toBeNull();
    unmount();

    render(stat({ trend: false, compare: "previous" }));
    expect(screen.getByText("к прошлому периоду")).toBeTruthy();
    expect(screen.queryByText("vs previous period")).toBeNull();
  });
});
