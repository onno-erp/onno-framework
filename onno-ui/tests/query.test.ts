import { afterEach, describe, expect, it, vi } from "vitest";
import type { UiEvent } from "@/lib/types";

let emit: (event: UiEvent) => void = () => {};
vi.mock("@/lib/ui-event-bus", () => ({
  subscribeUiEvents: (listener: (event: UiEvent) => void) => {
    emit = listener;
    return () => {};
  },
}));

const { queryClient, queryKeys, startQueryInvalidation, useEntityChanges } = await import("@/lib/query");
const { setModel } = await import("@/lib/model-store");
const { act, renderHook } = await import("@testing-library/react");

const model = {
  entities: [
    { kind: "documents", name: "sales_orders", logicalName: "Sales Orders", title: "Sales Orders", context: "", route: "/documents/sales_orders", canWrite: true },
    { kind: "registers", name: "stock", logicalName: "Stock", title: "Stock", context: "", route: "/registers/stock", canWrite: false },
  ],
  informationRegisters: [],
  constants: [],
} as unknown as import("@/lib/app-model").AppModel;

/** What a component keeping its own view of `entity` hears, in order. */
function listen(entity: string) {
  const heard: (readonly string[] | null)[] = [];
  const hook = renderHook(() => useEntityChanges(entity, (ids) => heard.push(ids)));
  return { heard, unmount: hook.unmount };
}

describe("live query invalidation", () => {
  afterEach(() => {
    queryClient.clear();
    vi.useRealTimers();
  });

  it("tells an entity's subscribers which records changed in place, by route or logical name", async () => {
    vi.useFakeTimers();
    setModel(model);
    const stop = startQueryInvalidation();
    const byRoute = listen("documents/sales_orders");
    const byName = listen("Sales Orders");
    const other = listen("registers/stock");

    // Two in-place changes in one burst arrive as one change naming both records.
    emit({ type: "updated", entityType: "document", entityName: "Sales Orders", id: "o1" });
    emit({ type: "posted", entityType: "document", entityName: "Sales Orders", id: "o2" });
    await act(async () => vi.advanceTimersByTimeAsync(150));
    expect(byRoute.heard).toEqual([["o1", "o2"]]);
    expect(byName.heard).toEqual([["o1", "o2"]]);
    expect(other.heard).toEqual([]);

    // A create changes the set itself: subscribers reload.
    emit({ type: "created", entityType: "document", entityName: "Sales Orders", id: "o3" });
    await act(async () => vi.advanceTimersByTimeAsync(150));
    expect(byRoute.heard).toEqual([["o1", "o2"], null]);

    // Posting's register wildcard reaches every register, not the documents.
    emit({ type: "changed", entityType: "register", entityName: "*" });
    await act(async () => vi.advanceTimersByTimeAsync(150));
    expect(other.heard).toEqual([null]);
    expect(byRoute.heard).toHaveLength(2);

    byRoute.unmount(); byName.unmount(); other.unmount();
    setModel(null);
    stop();
  });

  it("matches an entity the model doesn't offer by its route", async () => {
    vi.useFakeTimers();
    const stop = startQueryInvalidation();
    const list = listen("catalogs/crm_conversations");
    emit({ type: "updated", entityType: "catalog", entityName: "CrmConversations", id: "c1" });
    await act(async () => vi.advanceTimersByTimeAsync(150));
    expect(list.heard).toEqual([["c1"]]);
    list.unmount();
    stop();
  });

  it("invalidates only the changed entity's queries, coalescing bursts", () => {
    vi.useFakeTimers();
    const stop = startQueryInvalidation();
    queryClient.setQueryData(queryKeys.records("Orders"), [{ id: "1" }]);
    queryClient.setQueryData(queryKeys.records("Books"), [{ id: "2" }]);

    emit({ type: "changed", entityType: "document", entityName: "Orders" });
    emit({ type: "posted", entityType: "document", entityName: "Orders" });
    vi.advanceTimersByTime(150);

    expect(queryClient.getQueryState(queryKeys.records("Orders"))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(queryKeys.records("Books"))?.isInvalidated).toBe(false);
    stop();
  });

  it("refreshes all entity data on a register wildcard and ignores presence pings", () => {
    vi.useFakeTimers();
    const stop = startQueryInvalidation();
    queryClient.setQueryData(queryKeys.records("Books"), []);

    emit({ type: "presence", entityType: "presence", entityName: "Books" });
    vi.advanceTimersByTime(150);
    expect(queryClient.getQueryState(queryKeys.records("Books"))?.isInvalidated).toBe(false);

    emit({ type: "changed", entityType: "register", entityName: "*" });
    vi.advanceTimersByTime(150);
    expect(queryClient.getQueryState(queryKeys.records("Books"))?.isInvalidated).toBe(true);
    stop();
  });
});
