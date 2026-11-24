import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useRecordRelations } from "@/components/entities/record-relations";
import { queryClient } from "@/lib/query";
import { setModel } from "@/lib/model-store";
import type { AppModel } from "@/lib/app-model";

const specs = { offers: { entity: "Offers", via: "supplier", columns: ["price"], sort: "-price" } };
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
const first = Array.from({ length: 500 }, (_, i) => ({ id: `offer-${i}`, price: 1000 - i }));
beforeEach(() => {
  setModel({ entities: [{ kind: "catalogs", name: "offers", logicalName: "Offers", title: "Offers", route: "/catalogs/offers", canWrite: true }], informationRegisters: [], constants: [] } as unknown as AppModel);
  queryClient.setDefaultOptions({ queries: { retry: false } });
});
afterEach(() => { cleanup(); queryClient.clear(); queryClient.setDefaultOptions({ queries: { retry: 1, staleTime: 30000, refetchOnWindowFocus: false } }); vi.unstubAllGlobals(); });

it("loads every relation page before allowing an edit and preserves the last link in the save payload", async () => {
  let finish!: (response: Response) => void;
  const fetcher = vi.fn(async (input: string) => {
    const url = new URL(input, "http://localhost");
    if (url.pathname.startsWith("/api/ui/view")) return json({ form: { meta: { attributes: [{ fieldName: "price", javaType: "BigDecimal", displayName: "Price" }] } } });
    if (url.searchParams.has("cursor")) return new Promise<Response>((resolve) => { finish = resolve; });
    return json({ rows: first, hasMore: true, nextCursor: "page-2" });
  });
  vi.stubGlobal("fetch", fetcher);
  const { result } = renderHook(() => useRecordRelations(specs, "supplier-1", vi.fn()));
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) => url.includes("cursor=page-2"))).toBe(true));
  expect(result.current.relations[0].loaded).toBe(false);
  expect(result.current.payload()).toEqual([]);
  await act(async () => finish(json({ rows: [{ id: "offer-500", price: 500 }], hasMore: false, nextCursor: null })));
  await waitFor(() => expect(result.current.relations[0].loaded).toBe(true));
  expect(result.current.relations[0].rows).toHaveLength(501);
  act(() => result.current.setRows("offers", result.current.relations[0].rows.map((row, i) => i === 0 ? { ...row, price: 9999 } : row)));
  expect(result.current.payload()[0].rows).toHaveLength(501);
  expect(result.current.payload()[0].rows.at(-1)).toEqual({ id: "offer-500", price: 500 });
  for (const [input] of fetcher.mock.calls.filter(([url]) => url.startsWith("/api/list/"))) {
    const params = new URL(input, "http://localhost").searchParams;
    expect(JSON.parse(params.get("where")!)).toEqual({ supplier: { eq: "supplier-1" } });
    expect(params.get("sort")).toBe("price");
    expect(params.get("dir")).toBe("desc");
  }
});

it.each(["missing cursor", "repeated cursor", "failed page"])("never offers a partial replacement after a %s", async (failure) => {
  const fetcher = vi.fn(async (input: string) => {
    const url = new URL(input, "http://localhost");
    if (url.pathname.startsWith("/api/ui/view")) return json({ form: { meta: { attributes: [{ fieldName: "price", javaType: "BigDecimal" }] } } });
    if (failure === "failed page" && url.searchParams.has("cursor")) return new Response("Unavailable", { status: 503 });
    return json({ rows: first, hasMore: true, nextCursor: failure === "missing cursor" ? null : "same-cursor" });
  });
  vi.stubGlobal("fetch", fetcher);
  const { result } = renderHook(() => useRecordRelations(specs, "supplier-1", vi.fn()));
  await waitFor(() => expect(queryClient.getQueryState(["onno", "Offers", "relation-rows", "supplier", "supplier-1", "-price"])?.status).toBe("error"));
  expect(result.current.relations[0].loaded).toBe(false);
  expect(result.current.relations[0].rows).toEqual([]);
  act(() => result.current.setRows("offers", [{ id: "offer-0", price: 42 }]));
  expect(result.current.payload()).toEqual([]);
});

it("loads document relations through every page with the same sort and owner scope", async () => {
  setModel({ entities: [{ kind: "documents", name: "offers", logicalName: "Offers", title: "Offers", route: "/documents/offers", canWrite: true }], informationRegisters: [], constants: [] } as unknown as AppModel);
  const fetcher = vi.fn(async (input: string) => {
    const url = new URL(input, "http://localhost");
    if (url.pathname.startsWith("/api/ui/view")) return json({ form: { meta: { attributes: [{ fieldName: "price", javaType: "BigDecimal" }] } } });
    const last = url.searchParams.has("cursor");
    return json({ rows: [{ id: last ? "second" : "first", price: 1 }], hasMore: !last, nextCursor: last ? null : "next" });
  });
  vi.stubGlobal("fetch", fetcher);
  const { result } = renderHook(() => useRecordRelations(specs, "supplier-1", vi.fn()));
  await waitFor(() => expect(result.current.relations[0].loaded).toBe(true));
  expect(result.current.relations[0].rows.map((row) => row.id)).toEqual(["first", "second"]);
  const requests = fetcher.mock.calls.filter(([url]) => url.startsWith("/api/list/documents/offers"));
  expect(requests).toHaveLength(2);
  for (const [input] of requests) {
    const params = new URL(input, "http://localhost").searchParams;
    expect(params.get("sort")).toBe("price");
    expect(params.get("dir")).toBe("desc");
    expect(JSON.parse(params.get("where")!)).toEqual({ supplier: { eq: "supplier-1" } });
  }
});
