import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { InformationRegisterSurface } from "@/components/lists/information-register-surface";
import type { ListDescriptor } from "@/components/lists/entity-list";
import { queryClient } from "@/lib/query";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
const list: ListDescriptor = {
  kind: "registers", name: "book_prices", title: "Book prices", canWrite: false,
  columns: [{ columnName: "book", fieldName: "book", label: "Book", width: "1fr", type: "ref" }, { columnName: "_period", fieldName: "period", label: "Date", width: "1fr", type: "date" }],
  sort: { column: "_period", descending: true }, newUrl: null, searchable: false, pageSize: 50,
  feed: "/api/list/information-registers/book_prices",
  filters: [{ key: "book", column: "book", label: "Book", type: "multiOptions", optionsFrom: "Books", options: [] }, { key: "period", column: "_period", label: "Date", type: "dateRange", options: [] }],
};
beforeEach(() => {
  window.history.replaceState({}, "", "/information-registers/book_prices");
  vi.stubGlobal("ResizeObserver", ResizeObserverMock);
  vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
  vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
});
afterEach(() => { cleanup(); queryClient.clear(); vi.unstubAllGlobals(); window.history.replaceState({}, "", "/"); });
it("searches book titles, sends the selected ID and keeps it when switching to dated values", async () => {
  const requests: URL[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    const url = new URL(input, "http://localhost");
    if (url.pathname.includes("/list/catalogs/")) return response({ rows: Array.from({ length: 20 }, (_, i) => ({ id: `b${i}`, description: i === 7 ? "Dune" : `Book ${i}` })), hasMore: false, nextCursor: null });
    requests.push(url);
    const selected = url.searchParams.getAll("in").includes("book,b7");
    return response({ rows: selected ? [{ id: "r7", book: "b7", bookDisplay: "Dune", period: "2026-11-01T00:00:00" }] : [], total: selected ? 1 : 0, nextCursor: null, hasMore: false });
  }));
  render(<InformationRegisterSurface register={{ periodic: true, list }} />);
  const trigger = await screen.findByRole("button", { name: /^Book$/, expanded: false });
  fireEvent.click(trigger);
  const search = await screen.findByPlaceholderText("Search…");
  fireEvent.change(search, { target: { value: "dun" } });
  const option = await screen.findByText("Dune", { exact: true });
  fireEvent.click(option);
  await waitFor(() => expect(requests.some(url => url.searchParams.getAll("in").includes("book,b7"))).toBe(true));
  fireEvent.keyDown(document, { key: "Escape" });
  fireEvent.click(screen.getByText("Latest values"));
  fireEvent.change(screen.getByLabelText("Date"), { target: { value: "2026-11-10" } });
  await waitFor(() => expect(requests.some(url => url.searchParams.has("at") && url.searchParams.getAll("in").includes("book,b7"))).toBe(true));
  await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 50)); });
  const latestRequests = requests.filter(url => url.searchParams.has("at"));
  expect(latestRequests.length).toBeGreaterThan(0);
  expect(latestRequests.every(url => url.searchParams.getAll("in").includes("book,b7"))).toBe(true);
  expect(latestRequests.at(-1)?.searchParams.get("at")).toBe("2026-11-10T23:59:59.999999");
  const beforeReturn = requests.length;
  fireEvent.click(screen.getByText("History", { exact: true }));
  await waitFor(() => expect(requests.slice(beforeReturn).some(url => !url.searchParams.has("at") && url.searchParams.getAll("in").includes("book,b7"))).toBe(true));
  expect(screen.queryByRole("button", { name: /^New$/ })).toBeNull();
});
