import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

// The measured components (Stat, Sparkline, Gauge) turn a typed `measure` into aggregate requests.
// The data layer is a recorder: every bucket request is kept, and each answers with a total that
// identifies it (the `where` JSON and window pick the number), so the tests can read both the
// request and how the component combined the answers.
const calls = vi.hoisted(() => ({ buckets: [] as Array<{ entity: string; params: Record<string, string> }> }));

vi.mock("@/lib/aggregate-data", () => ({
  useAggregateBuckets: (source: { entityName: string } | null, params: Record<string, string> | null) => {
    if (!source || !params) return null;
    calls.buckets.push({ entity: source.entityName, params });
    return { buckets: [], truncated: false, params };
  },
  seriesFromBuckets: (resp: { params?: Record<string, string> } | null) => {
    const p = resp?.params ?? {};
    const previous = p.from?.startsWith("2024-01");
    const total = p.where?.includes("CANCELLED") ? 40 : p.metric === "count" ? (previous ? 50 : 80) : previous ? 100 : 150;
    return { total, rows: [{ label: "a", value: total / 2 }, { label: "b", value: total / 2 }], seriesKeys: ["value"] };
  },
}));

vi.mock("@/lib/entity-source", () => ({
  useEntitySource: (entity: string | undefined, options: { dateField?: string; where?: unknown } = {}) =>
    entity
      ? { entityType: entity === "Book Stock" ? "register" : "document", entityName: entity, dateField: options.dateField ?? "", where: options.where }
      : null,
}));

vi.mock("@/providers/time-range-provider", () => ({
  useTimeRange: () => ({
    range: { kind: "absolute", from: "2024-02-01T00:00:00", to: "2024-03-01T00:00:00" },
  }),
}));

vi.mock("@/components/widgets/trend-line", () => ({
  TrendLine: ({ data }: { data: Array<{ value: number }> }) => <div data-testid="sparkline">{data.map((d) => d.value).join(",")}</div>,
}));
vi.mock("@/components/ui/animated-number", () => ({
  AnimatedNumber: ({ value }: { value: string }) => <span data-testid="value">{value}</span>,
}));
vi.mock("@/providers/messages-provider", () => ({ useMessages: () => (key: string) => key }));

import { Stat } from "@/components/widgets/stat";
import { Sparkline } from "@/components/widgets/sparkline";
import { Gauge } from "@/components/widgets/gauge";
import { count, sum } from "@/lib/measure";

afterEach(() => {
  cleanup();
  calls.buckets.length = 0;
});

const current = () => calls.buckets.filter((c) => c.params.from?.startsWith("2024-02"));

describe("Stat requests", () => {
  it("aggregates a register measure on the server, by period, with its movement where", () => {
    render(<Stat title="Received" measure={sum("Book Stock", "quantity", { movementType: "RECEIPT" })} />);

    const [request] = current();
    expect(request.entity).toBe("Book Stock");
    expect(request.params).toMatchObject({
      metric: "sum",
      field: "quantity",
      where: JSON.stringify({ movementType: "RECEIPT" }),
      groupBy: "period",
      dateField: "period",
      from: "2024-02-01T00:00:00",
    });
  });

  it("sends the measure's aggregation, field and where as JSON, windowed and bucketed by its date field", () => {
    render(<Stat title="Revenue" measure={sum("Orders", "total", { posted: true, total: { gte: 100 } })} />);

    const [request] = current();
    expect(request.entity).toBe("Orders");
    expect(request.params).toMatchObject({
      metric: "sum",
      field: "total",
      where: JSON.stringify({ posted: true, total: { gte: 100 } }),
      groupBy: "date",
      groupByDate: "day",
      dateField: "date",
      from: "2024-02-01T00:00:00",
    });
    expect(request.params.filter).toBeUndefined();
    // The previous equal-length window is requested too: +50% (150 vs 100).
    expect(calls.buckets.some((c) => c.params.from?.startsWith("2024-01"))).toBe(true);
    expect(screen.getByText(/50\.0%/)).toBeTruthy();
  });

  it("uses an authored sparkline grouping and the measure's own date field", () => {
    render(<Stat measure={{ ...count("Orders"), dateField: "shippedAt" }} sparkline={{ shippedAt: "week" }} />);

    expect(current()[0].params).toMatchObject({ metric: "count", groupBy: "shippedAt", groupByDate: "week", dateField: "shippedAt" });
  });

  it("keeps a headline-only tile to one grand-total bucket", () => {
    render(<Stat measure={count("Orders")} trend={false} />);

    expect(calls.buckets).toHaveLength(1);
    expect(calls.buckets[0].params.groupBy).toBeUndefined();
  });

  it("computes a ratio of two measures, each with its own where", () => {
    render(<Stat measure={count("Orders", { status: "CANCELLED" })} ratioTo={count("Orders")} />);

    const wheres = current().map((c) => c.params.where);
    expect(wheres).toEqual([JSON.stringify({ status: "CANCELLED" }), undefined]);
    // 40 cancelled of 80 orders.
    expect(screen.getByTestId("value").textContent).toContain("50");
    expect(screen.queryByTestId("sparkline")).toBeNull();
  });
});

describe("Sparkline requests", () => {
  it("buckets the measure by day by default and sends where", () => {
    render(<Sparkline title="Orders" measure={count("Orders", { posted: true })} />);

    expect(calls.buckets[0].params).toEqual({
      metric: "count",
      groupBy: "date",
      groupByDate: "day",
      where: JSON.stringify({ posted: true }),
    });
    expect(screen.getByTestId("sparkline").textContent).toBe("40,40");
  });

  it("groups by an explicit `by`", () => {
    render(<Sparkline measure={sum("Orders", "total")} by={{ date: "month" }} />);

    expect(calls.buckets[0].params).toMatchObject({ metric: "sum", field: "total", groupBy: "date", groupByDate: "month" });
  });
});

describe("Gauge requests", () => {
  it("fills toward a target measure, one grand-total bucket each", () => {
    render(<Gauge title="Plan" measure={sum("Orders", "total", { posted: true })} target={sum("Plans", "amount")} />);

    expect(calls.buckets.map((c) => [c.entity, c.params])).toEqual([
      ["Orders", { metric: "sum", field: "total", where: JSON.stringify({ posted: true }) }],
      ["Plans", { metric: "sum", field: "amount" }],
    ]);
  });

  it("fills toward a fixed number", () => {
    render(<Gauge measure={count("Orders")} target={160} />);

    expect(calls.buckets).toHaveLength(1);
    expect(screen.getByText(/50% of/)).toBeTruthy();
  });
});
