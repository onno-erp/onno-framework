import { describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { DashboardWidgetMeta } from "@/lib/types";

// Only the caption matters here, so the data layer is a stub that yields a non-zero delta: the
// current window totals 150, the previous one (the only params carrying `from`) totals 100.
vi.mock("@/lib/widget-data", () => ({
  useWidgetRows: () => [],
  useWidgetBuckets: (_w: unknown, params: Record<string, string> | null) =>
    params ? { buckets: [], truncated: false, previous: Boolean(params.from) } : null,
  seriesFromBuckets: (resp: { previous?: boolean } | null) => ({
    total: resp?.previous ? 100 : 150,
    rows: [{ label: "a", value: 60 }, { label: "b", value: 90 }],
  }),
  buildSeries: () => ({ total: 0, rows: [] }),
  filterWindow: (rows: unknown[]) => rows,
}));

vi.mock("@/providers/time-range-provider", () => ({
  useTimeRange: () => ({
    range: { kind: "absolute", from: "2024-02-01T00:00:00", to: "2024-03-01T00:00:00" },
  }),
}));

vi.mock("@/components/sparkline", () => ({ Sparkline: () => <div data-testid="sparkline" /> }));
vi.mock("@/components/ui/animated-number", () => ({
  AnimatedNumber: ({ value }: { value: string }) => <span>{value}</span>,
}));

// A deployment on a non-English locale: the server's messages map is what `t` resolves against.
vi.mock("@/providers/messages-provider", () => ({
  useMessages: () => (key: string) =>
    key === "widget.stat.comparison" ? "к прошлому периоду" : key,
}));

import { StatWidget } from "@/components/stat-widget";

const statWidget = (extraConfig: Record<string, string>) =>
  ({
    title: "Выручка",
    entityType: "catalog",
    extraConfig,
  }) as unknown as DashboardWidgetMeta;

describe("StatWidget comparison caption", () => {
  afterEach(() => cleanup());

  it("honours comparisonLabel on the trend (sparkline) path", () => {
    // Regression for #396: this branch used to render a hardcoded English literal, so turning the
    // sparkline on silently reverted an authored label.
    render(<StatWidget widget={statWidget({ comparisonLabel: "к прошлому периоду, %" })} />);

    expect(screen.getByText("к прошлому периоду, %")).toBeTruthy();
    expect(screen.queryByText("vs previous period")).toBeNull();
  });

  it("honours comparisonLabel on the headline-only path", () => {
    render(
      <StatWidget
        widget={statWidget({ trend: "false", comparison: "true", comparisonLabel: "к прошлому периоду, %" })}
      />
    );

    expect(screen.getByText("к прошлому периоду, %")).toBeTruthy();
  });

  it("falls back to the message catalogue, not an English literal, on both paths", () => {
    const { unmount } = render(<StatWidget widget={statWidget({})} />);
    expect(screen.getByText("к прошлому периоду")).toBeTruthy();
    expect(screen.queryByText("vs previous period")).toBeNull();
    unmount();

    render(<StatWidget widget={statWidget({ trend: "false", comparison: "true" })} />);
    expect(screen.getByText("к прошлому периоду")).toBeTruthy();
    expect(screen.queryByText("vs previous period")).toBeNull();
  });
});
