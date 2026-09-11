import type { ComponentType } from "react";
import { registerWidget } from "@/lib/widget-registry";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EntityListWidget, type ListDescriptor } from "@/components/entity-list-widget";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
const rows = [{ id: "one", description: "First" }, { id: "two", description: "Second" }];
const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
function list(extra: Partial<ListDescriptor> = {}): ListDescriptor {
  return { kind: "catalogs", name: "orders", title: "Orders", columns: [{ columnName: "description", label: "Name", width: "" }], searchable: false,
    sort: { column: null, descending: false }, newUrl: null, canWrite: true, pageSize: 20, embedded: true,
    actions: [{ key: "review", label: "Review", icon: "", scope: "row", server: true, kind: "catalogs", name: "orders" }], ...extra };
}
describe("optional list selection checkboxes", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
    fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes("/batch")) return response({ ok: 1, failed: [], total: 1 });
      if (String(input).includes("/groups")) return response({ groups: [{ label: "Pending", count: 100, values: [], expand: [{ op: "eq", column: "status", value: "pending" }] }], capped: false });
      return response({ rows, total: 100, hasMore: false, nextCursor: null });
    });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
  it("reloads composed workspace constraints when the channel changes", async () => {
    const descriptor = list();
    const { rerender } = render(<EntityListWidget list={descriptor} queryParams={{eq:["channel,EMAIL", "inbox,first"]}} />);
    await screen.findByText("First");
    expect(new URL(String(fetchMock.mock.calls.at(-1)![0]), "http://localhost").searchParams.getAll("eq")).toEqual(["channel,EMAIL", "inbox,first"]);
    fetchMock.mockClear();
    rerender(<EntityListWidget list={descriptor} queryParams={{eq:["channel,WHATSAPP"]}} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(new URL(String(fetchMock.mock.calls.at(-1)![0]), "http://localhost").searchParams.getAll("eq")).toEqual(["channel,WHATSAPP"]);
  });
  it("preserves the default table without checkboxes", async () => {
    render(<EntityListWidget list={list()} />);
    await screen.findByText("First");
    expect(screen.queryByRole("checkbox", { name: "Select First" })).not.toBeInTheDocument();
  });
  it("toggles without opening a row, exposes mixed state, selects loaded rows and clears", async () => {
    const navigate = vi.fn(); window.addEventListener("onno:action", navigate);
    render(<EntityListWidget list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    expect(navigate).not.toHaveBeenCalled();
    const all = screen.getByRole("checkbox", { name: "Select loaded rows" });
    expect(all).toHaveAttribute("aria-checked", "mixed");
    expect(screen.getByRole("group", { name: "Actions for selected" })).toBeInTheDocument();
    fireEvent.click(all);
    expect(screen.getByRole("checkbox", { name: "Select Second" })).toHaveAttribute("aria-checked", "true");
    expect(all).toHaveAttribute("aria-checked", "true");
    fireEvent.click(all);
    expect(screen.queryByRole("group", { name: "Actions for selected" })).not.toBeInTheDocument();
    window.removeEventListener("onno:action", navigate);
  });
  it("runs the server batch action for exactly the checked row, including a single selection", async () => {
    render(<EntityListWidget list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select Second" }));
    fireEvent.click(screen.getByRole("group", { name: "Actions for selected" }).querySelectorAll("button")[1]);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/review/batch"))).toBe(true));
    const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/review/batch"))!;
    expect(JSON.parse((call[1] as RequestInit).body as string).ids).toEqual(["two"]);
  });
  it("selects only expanded, loaded group rows", async () => {
    render(<EntityListWidget list={list({ selectionCheckboxes: true, groupable: [{ columnName: "status", label: "Status", date: false }], defaultGroupBy: "status" })} />);
    const all = await screen.findByRole("checkbox", { name: "Select loaded rows" });
    expect(all).toBeDisabled();
    fireEvent.click(await screen.findByRole("button", { name: /Pending/ }));
    await screen.findByRole("checkbox", { name: "Select First" });
    fireEvent.click(all);
    expect(screen.getByRole("checkbox", { name: "Select Second" })).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByRole("checkbox", { name: "Select First" }));
    expect(all).toHaveAttribute("aria-checked", "mixed");
  });
  it("passes checked ids to an authored selection widget and clears after completion", async () => {
    const received = vi.fn();
    function Selection({ ids, complete }: { ids: string[]; complete: () => void }) {
      return <button disabled={ids.length !== 2} onClick={() => { received(ids); complete(); }}>Merge selected</button>;
    }
    registerWidget("test-selection", Selection as unknown as Parameters<typeof registerWidget>[1]);
    render(<EntityListWidget list={list({ selectionCheckboxes: true, selectionWidget: "test-selection" })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    expect(screen.getByRole("button", { name: "Merge selected" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Select Second" }));
    fireEvent.click(screen.getByRole("button", { name: "Merge selected" }));
    expect(received).toHaveBeenCalledWith(["one", "two"]);
    expect(screen.queryByRole("button", { name: "Merge selected" })).not.toBeInTheDocument();
  });
  it("does not mount selection commands without write access", async () => {
    registerWidget("read-only-selection", (() => <button>Forbidden merge</button>) as ComponentType<any>);
    render(<EntityListWidget list={list({ selectionCheckboxes: true, selectionWidget: "read-only-selection", canWrite: false })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    expect(screen.queryByRole("button", { name: "Forbidden merge" })).not.toBeInTheDocument();
  });
  it("clears the selection on sort changes", async () => {
    render(<EntityListWidget list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    fireEvent.click(screen.getByRole("button", { name: "Name" }));
    await waitFor(() => expect(screen.queryByRole("group", { name: "Actions for selected" })).not.toBeInTheDocument());
  });
});
