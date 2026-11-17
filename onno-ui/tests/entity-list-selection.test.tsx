import type { ComponentType } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EntityListView, type ListDescriptor, type ListRendererProps } from "@/components/lists/entity-list";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe = vi.fn(); unobserve() {} disconnect() {} }
const rows = [{ id: "one", description: "First" }, { id: "two", description: "Second" }];
const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
function list(extra: Partial<ListDescriptor> = {}): ListDescriptor {
  return { kind: "catalogs", name: "orders", title: "Orders", columns: [{ columnName: "description", label: "Name", width: "" }], searchable: false,
    sort: { column: null, descending: false }, newUrl: null, canWrite: true, pageSize: 20, embedded: true,
    commands: [{ key: "review", title: "Review", target: "record" }], ...extra };
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
  it("fills the grouped card only for fill lists and uses its scroller", async () => {
    const observe = vi.fn();
    vi.stubGlobal("ResizeObserver", class extends ResizeObserverMock { observe = observe; });
    const grouped = {
      groupable: [{ columnName: "status", label: "Status", date: false }],
      defaultGroupBy: "status",
    };
    const { container, rerender } = render(<EntityListView list={list({ ...grouped, fill: true })} />);
    await screen.findByRole("button", { name: /Pending/ });
    const scroller = container.querySelector(".overflow-auto")!;
    expect(scroller).toHaveClass("flex-1", "min-h-0");
    expect(scroller.parentElement).toHaveClass("flex-1", "min-h-0");
    expect(observe).toHaveBeenCalledWith(scroller);

    rerender(<EntityListView list={list(grouped)} />);
    await screen.findByRole("button", { name: /Pending/ });
    const ordinaryScroller = container.querySelector(".overflow-auto")!;
    expect(ordinaryScroller).not.toHaveClass("flex-1", "min-h-0");
    expect(ordinaryScroller.parentElement).not.toHaveClass("flex-1");

    rerender(<EntityListView list={list({ ...grouped, embedded: false })} />);
    await screen.findByRole("button", { name: /Pending/ });
    const routeScroller = container.querySelector(".overflow-auto")!;
    expect(routeScroller.parentElement).toHaveClass("min-h-0");
    expect(routeScroller.parentElement).not.toHaveClass("flex-1");
  });
  it("reloads composed workspace constraints when the channel changes", async () => {
    const descriptor = list();
    const { rerender } = render(<EntityListView list={descriptor} queryParams={{eq:["channel,EMAIL", "inbox,first"]}} />);
    await screen.findByText("First");
    expect(new URL(String(fetchMock.mock.calls.at(-1)![0]), "http://localhost").searchParams.getAll("eq")).toEqual(["channel,EMAIL", "inbox,first"]);
    fetchMock.mockClear();
    rerender(<EntityListView list={descriptor} queryParams={{eq:["channel,WHATSAPP"]}} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(new URL(String(fetchMock.mock.calls.at(-1)![0]), "http://localhost").searchParams.getAll("eq")).toEqual(["channel,WHATSAPP"]);
  });
  it("lets the parent own the selection", async () => {
    const onSelectionChange = vi.fn();
    const { rerender } = render(<EntityListView list={list({ selectionCheckboxes: true })}
      state={{ selection: ["two"], onSelectionChange }} />);
    expect(await screen.findByRole("checkbox", { name: "Select Second" })).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByRole("checkbox", { name: "Select First" }));
    expect(onSelectionChange).toHaveBeenLastCalledWith(["two", "one"]);
    // Controlled: nothing changes until the parent passes the new value.
    expect(screen.getByRole("checkbox", { name: "Select First" })).toHaveAttribute("aria-checked", "false");
    rerender(<EntityListView list={list({ selectionCheckboxes: true })} state={{ selection: [], onSelectionChange }} />);
    expect(screen.getByRole("checkbox", { name: "Select Second" })).toHaveAttribute("aria-checked", "false");
  });
  it("lets the parent own the grouping", async () => {
    const grouped = { groupable: [{ columnName: "status", label: "Status", date: false }] };
    const { rerender } = render(<EntityListView list={list(grouped)} state={{ groupBy: "" }} />);
    await screen.findByText("First");
    expect(screen.queryByRole("button", { name: /Pending/ })).not.toBeInTheDocument();
    rerender(<EntityListView list={list(grouped)} state={{ groupBy: "status" }} />);
    await screen.findByRole("button", { name: /Pending/ });
  });
  it("preserves the default table without checkboxes", async () => {
    render(<EntityListView list={list()} />);
    await screen.findByText("First");
    expect(screen.queryByRole("checkbox", { name: "Select First" })).not.toBeInTheDocument();
  });
  it("toggles without opening a row, exposes mixed state, selects all matching rows and clears", async () => {
    const navigate = vi.fn(); window.addEventListener("onno:open", navigate);
    render(<EntityListView list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    expect(navigate).not.toHaveBeenCalled();
    const all = screen.getByRole("checkbox", { name: "Select all matching rows" });
    expect(all).toHaveAttribute("aria-checked", "mixed");
    expect(screen.getByRole("group", { name: "Actions for selected" })).toBeInTheDocument();
    fireEvent.click(all);
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Select Second" })).toHaveAttribute("aria-checked", "true"));
    await waitFor(() => expect(all).toHaveAttribute("aria-checked", "true"));
    fireEvent.click(all);
    expect(screen.queryByRole("group", { name: "Actions for selected" })).not.toBeInTheDocument();
    window.removeEventListener("onno:open", navigate);
  });
  it("runs the server batch action for exactly the checked row, including a single selection", async () => {
    render(<EntityListView list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select Second" }));
    fireEvent.click(screen.getByRole("group", { name: "Actions for selected" }).querySelectorAll("button")[1]);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/review/batch"))).toBe(true));
    const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/review/batch"))!;
    expect(JSON.parse((call[1] as RequestInit).body as string).ids).toEqual(["two"]);
  });
  it("selects collapsed group rows without expanding them", async () => {
    render(<EntityListView list={list({ selectionCheckboxes: true, groupable: [{ columnName: "status", label: "Status", date: false }], defaultGroupBy: "status" })} />);
    await screen.findByRole("button", { name: /Pending/ });
    const all = screen.getByRole("checkbox", { name: "Select all matching rows" });
    expect(all).not.toBeDisabled();
    fireEvent.click(all);
    await waitFor(() => expect(all).toHaveAttribute("aria-checked", "true"));
    expect(screen.getByText("2 selected")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Select First" })).not.toBeInTheDocument();
  });
  it("selects every cursor page with the current constraints without loading it into the table", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://localhost");
      if (url.searchParams.get("limit") === "500") {
        expect(url.searchParams.get("eq")).toBe("status,NEW");
        expect(url.searchParams.get("where")).toBe(JSON.stringify({ channel: "web" }));
        return response(url.searchParams.has("cursor")
          ? { rows: [{ id: "three", description: "Third" }], hasMore: false }
          : { rows, hasMore: true, nextCursor: "next" });
      }
      return response({ rows, total: 3, hasMore: true, nextCursor: "next" });
    });
    render(<EntityListView list={list({ selectionCheckboxes: true, baseWhere: { channel: "web" } })} queryParams={{ eq: ["status,NEW"] }} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all matching rows" }));
    await screen.findByText("3 selected");
    expect(screen.queryByText("Third")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all matching rows" }));
    expect(screen.queryByText("3 selected")).not.toBeInTheDocument();
  });
  it("gives a replacement list body the shared selection and scoped select-all", async () => {
    function Replacement({ selectedIds, setSelection, selectAll, clearSelection, rows }: ListRendererProps) {
      return <section>
        <output>Custom selected: {selectedIds?.length ?? 0}</output>
        <button disabled={!rows.length} onClick={() => setSelection?.([String(rows[0].id)])}>Pick first</button>
        <button onClick={selectAll}>Pick all matching</button>
        <button onClick={clearSelection}>Clear custom</button>
      </section>;
    }
    render(<EntityListView list={list({ custom: { component: Replacement, defaultView: true } })} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText("Pick first")).not.toBeDisabled());
    fireEvent.click(screen.getByText("Pick first"));
    await screen.findByText("Custom selected: 1");
    fireEvent.click(screen.getByText("Pick all matching"));
    await screen.findByText("Custom selected: 2");
    fireEvent.click(screen.getByText("Clear custom"));
    await screen.findByText("Custom selected: 0");
  });
  it("uses the same complete selection for Ctrl+A", async () => {
    render(<EntityListView list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    fetchMock.mockImplementation(async () => response({ rows: [...rows, { id: "three" }], hasMore: false }));
    fireEvent.keyDown(window, { key: "a", code: "KeyA", ctrlKey: true });
    await screen.findByText("3 selected");
  });
  it("keeps the old selection when a later page fails", async () => {
    render(<EntityListView list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => new URL(String(input), "http://localhost").searchParams.has("cursor")
      ? new Response("Unavailable", { status: 503 }) : response({ rows, hasMore: true, nextCursor: "next" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all matching rows" }));
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Select all matching rows" })).not.toBeDisabled());
    expect(screen.getByText("1 selected")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Select Second" })).toHaveAttribute("aria-checked", "false");
  });
  it("discards a pending select-all after the query changes", async () => {
    const descriptor = list({ selectionCheckboxes: true });
    const { rerender } = render(<EntityListView list={descriptor} />);
    await screen.findByText("First");
    let resolve!: (value: Response) => void;
    fetchMock.mockImplementation((input: RequestInfo | URL) => new URL(String(input), "http://localhost").searchParams.get("limit") === "500"
      ? new Promise<Response>(done => { resolve = done; }) : Promise.resolve(response({ rows, hasMore: false })));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all matching rows" }));
    rerender(<EntityListView list={descriptor} queryParams={{ eq: ["status,NEW"] }} />);
    resolve(response({ rows, hasMore: false }));
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Select all matching rows" })).not.toBeDisabled());
    expect(screen.queryByText("2 selected")).not.toBeInTheDocument();
  });
  it("passes checked ids to an authored selection widget and clears after completion", async () => {
    const received = vi.fn();
    function Selection({ ids, complete }: { ids: string[]; complete: () => void }) {
      return <button disabled={ids.length !== 2} onClick={() => { received(ids); complete(); }}>Merge selected</button>;
    }
    render(<EntityListView list={list({ selectionCheckboxes: true, selectionComponent: Selection })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    expect(screen.getByRole("button", { name: "Merge selected" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Select Second" }));
    fireEvent.click(screen.getByRole("button", { name: "Merge selected" }));
    expect(received).toHaveBeenCalledWith(["one", "two"]);
    expect(screen.queryByRole("button", { name: "Merge selected" })).not.toBeInTheDocument();
  });
  it("does not mount selection commands without write access", async () => {
    render(<EntityListView list={list({ selectionCheckboxes: true, selectionComponent: () => <button>Forbidden merge</button>, canWrite: false })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    expect(screen.queryByRole("button", { name: "Forbidden merge" })).not.toBeInTheDocument();
  });
  it("clears the selection on sort changes", async () => {
    render(<EntityListView list={list({ selectionCheckboxes: true })} />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select First" }));
    fireEvent.click(screen.getByRole("button", { name: "Name" }));
    await waitFor(() => expect(screen.queryByRole("group", { name: "Actions for selected" })).not.toBeInTheDocument());
  });
});
