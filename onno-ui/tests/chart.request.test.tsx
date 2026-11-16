import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { setModel } from "@/lib/model-store";
import { queryClient } from "@/lib/query";
import { count, sum } from "@/lib/measure";

// A fixed, bounded shared range: the window (and so the request) is deterministic, and its 31-day
// span picks day buckets.
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

import { Chart } from "@/components/widgets/chart";

// The model lives on the shared query client, which each test clears; re-seed it first.
beforeEach(() => setModel({
  user: { username: "u", displayName: "U", avatarUrl: "", roles: [] },
  features: { comments: false, notifications: false },
  informationRegisters: [],
  constants: [],
  entities: [{ kind: "documents", name: "orders", logicalName: "Orders", title: "Orders", context: "",
    route: "/documents/orders", canWrite: true }],
}));

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });

/** Every aggregate request's query, answered with the given buckets. */
function stubAggregate(buckets: unknown[]) {
  const calls: URLSearchParams[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    const [path, qs] = url.split("?");
    if (path !== "/api/list/documents/orders/aggregate") throw new Error(`unexpected ${url}`);
    calls.push(new URLSearchParams(qs));
    return json({ buckets, truncated: false });
  }));
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  return calls;
}

afterEach(() => {
  cleanup();
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe("Chart requests", () => {
  it("sends the typed measure and its where as JSON, never a filter string", async () => {
    const calls = stubAggregate([{ key: "NEW", label: "New", value: 1200 }, { key: "DONE", label: "Done", value: 300 }]);

    render(<Chart title="Revenue by status" measure={sum("Orders", "total", { posted: true })} by="status" />);

    await waitFor(() => expect(calls).toHaveLength(1));
    const params = Object.fromEntries(calls[0]);
    expect(params).toEqual({
      metric: "sum",
      field: "total",
      groupBy: "status",
      where: JSON.stringify({ posted: true }),
      dateField: "date",
      from: "2026-01-01T00:00:00",
      to: "2026-02-01T00:00:00",
    });
    expect(params.filter).toBeUndefined();
    // The additive headline totals the server's buckets.
    expect((await screen.findByTestId("headline")).textContent).toBe("1,500.00");
  });

  it("buckets the default date grouping by the range's granularity", async () => {
    const calls = stubAggregate([]);

    render(<Chart title="Orders" measure={count("Orders")} />);

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(Object.fromEntries(calls[0])).toMatchObject({ metric: "count", groupBy: "date", groupByDate: "day" });
    expect(await screen.findByText(/no data/i)).toBeTruthy();
  });

  it("fetches each series of a multi-measure chart with its own where", async () => {
    const calls = stubAggregate([{ key: "2026-01-02T00:00:00", value: 4 }]);

    render(
      <Chart
        title="Orders vs revenue"
        series={[
          { measure: count("Orders", { open: true }), label: "Open" },
          { measure: sum("Orders", "total", { posted: true }), label: "Revenue", axis: "right", type: "line" },
        ]}
      />
    );

    await waitFor(() => expect(calls).toHaveLength(2));
    const sent = calls.map((c) => Object.fromEntries(c));
    expect(sent).toEqual(expect.arrayContaining([
      expect.objectContaining({ metric: "count", where: JSON.stringify({ open: true }), groupByDate: "day" }),
      expect.objectContaining({ metric: "sum", field: "total", where: JSON.stringify({ posted: true }) }),
    ]));
    // Several measures have no single total.
    expect(screen.queryByTestId("headline")).toBeNull();
  });
});
