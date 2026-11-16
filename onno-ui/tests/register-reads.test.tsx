import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

// The register hooks send `where` as JSON (never string maps) and read the register aggregate.
const stock = { kind: "registers", name: "book_stock", logicalName: "Book Stock", route: "/registers/book_stock" };
vi.mock("@/lib/model-store", () => ({
  useModel: () => ({
    entities: [stock],
    informationRegisters: [{ logicalName: "Exchange Rates", title: "Rates", context: "", periodic: true }],
    constants: [{ name: "Store Name", type: "String" }],
  }),
  findEntity: (_model: unknown, ref: string) => (ref === "Book Stock" ? stock : undefined),
}));

const { queryClient, useAggregate, useBalance, useConstant, useInformationRegister, useMovements, useTurnover } =
  await import("@/lib/query");
const { sum } = await import("@/lib/measure");

const urls: URL[] = [];
function stubFetch(body: unknown) {
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    urls.push(new URL(input, "http://localhost"));
    return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
  }));
}
const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

afterEach(() => {
  cleanup();
  queryClient.clear();
  urls.length = 0;
  vi.unstubAllGlobals();
});

describe("register reads", () => {
  it("useBalance sends where as JSON and asOf", async () => {
    stubFetch([{ book: "b-1", quantity: 3 }]);
    const { result } = renderHook(() => useBalance("Book Stock", { where: { quantity: { gt: 0 } }, asOf: "2026-03-31" }), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([{ book: "b-1", quantity: 3 }]));
    expect(urls[0].pathname).toBe("/api/registers/book_stock/balance");
    expect(JSON.parse(urls[0].searchParams.get("where")!)).toEqual({ quantity: { gt: 0 } });
    expect(urls[0].searchParams.get("asOf")).toBe("2026-03-31");
  });

  it("useTurnover and useMovements send the window and the movement where", async () => {
    stubFetch([]);
    const where = { movementType: "RECEIPT" as const, book: "b-1" };
    const turnover = renderHook(() => useTurnover("Book Stock", { from: "2026-01-01T00:00:00", to: "2026-02-01T00:00:00", where }), { wrapper });
    const movements = renderHook(() => useMovements("Book Stock", { from: "2026-01-01T00:00:00" }), { wrapper });
    await waitFor(() => expect(turnover.result.current.isSuccess && movements.result.current.isSuccess).toBe(true));
    const t = urls.find((u) => u.pathname.endsWith("/turnover"))!;
    expect(Object.fromEntries(t.searchParams)).toEqual({
      from: "2026-01-01T00:00:00", to: "2026-02-01T00:00:00", where: JSON.stringify(where), limit: "500",
    });
    const m = urls.find((u) => u.pathname.endsWith("/movements"))!;
    expect(Object.fromEntries(m.searchParams)).toEqual({ from: "2026-01-01T00:00:00", limit: "500" });
  });

  it("register hooks follow the paged envelope's continuation to every row", async () => {
    let call = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      urls.push(new URL(String(input), "http://localhost"));
      const body = call++ === 0
        ? { rows: [{ quantity: 1 }], nextCursor: "c2", hasMore: true }
        : { rows: [{ quantity: 2 }], nextCursor: null, hasMore: false };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    const { result } = renderHook(() => useBalance("Book Stock"), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([{ quantity: 1 }, { quantity: 2 }]));
    expect(urls[1].searchParams.get("cursor")).toBe("c2");
  });

  it("useAggregate over a register asks the register aggregate with the measure's where", async () => {
    stubFetch({ buckets: [{ value: 12 }], truncated: false });
    const { result } = renderHook(() => useAggregate(sum("Book Stock", "quantity", { book: "b-1" }), { period: "week" }), { wrapper });
    await waitFor(() => expect(result.current.data?.buckets[0].value).toBe(12));
    expect(urls[0].pathname).toBe("/api/list/registers/book_stock/aggregate");
    expect(Object.fromEntries(urls[0].searchParams)).toEqual({
      metric: "sum", field: "quantity", groupBy: "period", groupByDate: "week", where: JSON.stringify({ book: "b-1" }),
    });
  });

  it("useInformationRegister reads the slice of last values with at and where", async () => {
    stubFetch([{ currency: "EUR", rate: 1.1 }]);
    const { result } = renderHook(() => useInformationRegister("Exchange Rates", { at: "2026-03-01T00:00:00", where: { currency: "EUR" } }), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([{ currency: "EUR", rate: 1.1 }]));
    expect(urls[0].pathname).toBe("/api/information-registers/Exchange%20Rates/slice-last");
    expect(Object.fromEntries(urls[0].searchParams)).toEqual({ at: "2026-03-01T00:00:00", where: JSON.stringify({ currency: "EUR" }) });
  });

  it("useConstant selects one setting's value", async () => {
    stubFetch([{ name: "Store Name", type: "String", value: "Books & Co" }, { name: "Other", type: "Boolean", value: true }]);
    const { result } = renderHook(() => useConstant("Store Name"), { wrapper });
    await waitFor(() => expect(result.current.data).toBe("Books & Co"));
    expect(urls[0].pathname).toBe("/api/settings");
  });
});
