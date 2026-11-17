import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EntityListView, type ListDescriptor } from "@/components/lists/entity-list";
import { isFocusedListPath, workspaceWithFocusedList, type Workspace } from "@/views/app-shell";
import { leavesPane } from "@/components/actions/record-actions";

const toastInfo = vi.fn();
vi.mock("@/components/ui/toast", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/ui/toast")>();
  return { ...actual, toast: Object.assign(actual.toast, { info: (...args: unknown[]) => toastInfo(...args) }) };
});
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }

const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
const rowsFrom = (from: number, to: number) =>
  Array.from({ length: to - from + 1 }, (_, i) => ({ id: `r${from + i}`, description: `Row ${from + i}` }));

function list(extra: Partial<ListDescriptor> = {}): ListDescriptor {
  return { kind: "catalogs", name: "books", title: "Books", columns: [{ columnName: "description", label: "Name", width: "" }],
    searchable: false, sort: { column: null, descending: false }, newUrl: null, canWrite: true, pageSize: 20,
    embedded: true, ...extra };
}

const params = (call: unknown[]) => new URL(String(call[0]), "http://localhost").searchParams;
const rowOf = (text: string) => screen.getByText(text).closest("[data-onno-row]") as HTMLElement;

describe("Open in list: the focused list window (#410)", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    toastInfo.mockClear();
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("asks the feed for the window around the record, then scrolls to it and highlights it", async () => {
    fetchMock = vi.fn(async () => response({
      rows: rowsFrom(311, 330), focus: "r320", hasMore: true, nextCursor: "next", hasPrevious: true, prevCursor: "prev", total: 500,
    }));
    vi.stubGlobal("fetch", fetchMock);
    render(<EntityListView list={list({ focus: "r320" })} />);

    await waitFor(() => expect(rowOf("Row 320")).toHaveAttribute("data-onno-focused", "true"));
    expect(params(fetchMock.mock.calls[0]).get("focus")).toBe("r320");
    expect(rowOf("Row 319")).not.toHaveAttribute("data-onno-focused");
    expect(toastInfo).not.toHaveBeenCalled();
  });

  it("loads the rows above when scrolled to the top, keeping the loaded rows in place", async () => {
    fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const q = new URL(String(input), "http://localhost").searchParams;
      if (q.get("before") === "prev") {
        return response({ rows: rowsFrom(291, 310), hasMore: true, nextCursor: "n2", hasPrevious: false, prevCursor: null });
      }
      return response({ rows: rowsFrom(311, 330), focus: "r312", hasMore: false, nextCursor: null, hasPrevious: true, prevCursor: "prev" });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<EntityListView list={list({ focus: "r312" })} />);
    await waitFor(() => expect(rowOf("Row 312")).toHaveAttribute("data-onno-focused", "true"));

    // Scroll the body to its top: the list fetches the window before the first loaded row.
    for (let el: HTMLElement | null = rowOf("Row 312"); el; el = el.parentElement) {
      el.scrollTop = 0;
      fireEvent.scroll(el);
    }
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => params(c).get("before") === "prev")).toBe(true));
    const before = fetchMock.mock.calls.find((c) => params(c).get("before") === "prev")!;
    expect(params(before).get("focus")).toBeNull();
    // Only one request for the window above, and the highlight survives the prepend.
    await waitFor(() => expect(rowOf("Row 312")).toHaveAttribute("data-onno-focused", "true"));
    expect(fetchMock.mock.calls.filter((c) => params(c).get("before")).length).toBe(1);
  });

  it("tells the user when the record is not in the list, and stays at the top", async () => {
    fetchMock = vi.fn(async () => response({ rows: rowsFrom(1, 20), hasMore: true, nextCursor: "next", hasPrevious: false, prevCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    render(<EntityListView list={list({ focus: "gone" })} />);

    await screen.findByText("Row 1");
    expect(params(fetchMock.mock.calls[0]).get("focus")).toBe("gone");
    expect(document.querySelector("[data-onno-focused]")).toBeNull();
    await waitFor(() => expect(toastInfo).toHaveBeenCalledWith("This record isn't in the list with the current filters"));
  });

  it("opens flat even when the view defaults to a grouping", async () => {
    fetchMock = vi.fn(async () => response({ rows: rowsFrom(1, 5), focus: "r3", hasMore: false, nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    render(<EntityListView list={list({ focus: "r3", groupable: [{ columnName: "status", label: "Status", date: false }], defaultGroupBy: "status" })} />);

    await waitFor(() => expect(rowOf("Row 3")).toHaveAttribute("data-onno-focused", "true"));
    expect(fetchMock.mock.calls.some((c) => String(c[0]).includes("/groups"))).toBe(false);
  });

  it("drops the highlight once the user opens a row", async () => {
    fetchMock = vi.fn(async () => response({ rows: rowsFrom(1, 5), focus: "r3", hasMore: false, nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    const navigate = vi.fn();
    window.addEventListener("onno:open", navigate);
    render(<EntityListView list={list({ focus: "r3" })} />);
    await waitFor(() => expect(rowOf("Row 3")).toHaveAttribute("data-onno-focused", "true"));

    fireEvent.click(rowOf("Row 4"));
    expect(navigate).toHaveBeenCalled();
    expect(document.querySelector("[data-onno-focused]")).toBeNull();
    window.removeEventListener("onno:open", navigate);
  });
});

describe("Open in list: where the list opens in the islands workspace", () => {
  const workspace = (): Workspace => ({
    panes: [
      { id: "main", tabs: [{ path: "/", title: "Dashboard" }], activePath: "/" },
      { id: "detail", tabs: [{ path: "/catalogs/books/r320", title: "Book" }], activePath: "/catalogs/books/r320" },
    ],
    sizes: [0.5, 0.5],
    focused: "detail",
  });

  it("keeps the record's menu from handing the focus back after Open in list", () => {
    expect(leavesPane("onno://documents/orders?focus=r1")).toBe(true);
    expect(leavesPane("onno://catalogs/books?q=x&focus=r1")).toBe(true);
    expect(leavesPane("onno://catalogs/books/r1/duplicate")).toBe(false);
    expect(leavesPane("onno://delete/catalogs/books/r1")).toBe(false);
  });

  it("recognises only a catalog/document list route that carries a focus", () => {
    expect(isFocusedListPath("/catalogs/books?focus=r1")).toBe(true);
    expect(isFocusedListPath("/documents/orders?sort=_date&focus=r1")).toBe(true);
    expect(isFocusedListPath("/catalogs/books")).toBe(false);
    expect(isFocusedListPath("/catalogs/books/r1?focus=r1")).toBe(false);
    expect(isFocusedListPath("/registers/stock?focus=r1")).toBe(false);
  });

  it("opens in the main pane like a navigation click, leaving the record open beside it", () => {
    const next = workspaceWithFocusedList(workspace(), "/catalogs/books?focus=r320");
    expect(next.focused).toBe("main");
    expect(next.panes[0].activePath).toBe("/catalogs/books?focus=r320");
    expect(next.panes[0].tabs.map((t) => t.path)).toEqual(["/", "/catalogs/books?focus=r320"]);
    expect(next.panes[1]).toEqual(workspace().panes[1]);
  });

  it("re-points the pane already showing that list instead of opening a duplicate tab", () => {
    const ws = workspace();
    ws.panes[0].tabs.push({ path: "/catalogs/books?q=war", title: "Books" });
    const next = workspaceWithFocusedList(ws, "/catalogs/books?focus=r320");
    expect(next.panes[0].tabs.map((t) => t.path)).toEqual(["/", "/catalogs/books?focus=r320"]);
    expect(next.panes[0].activePath).toBe("/catalogs/books?focus=r320");
    expect(next.focused).toBe("main");
  });
});
