import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { EntityList } from "@/components/pages/page-blocks";
import type { ListDescriptor } from "@/components/lists/entity-list";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));

class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }

const list: ListDescriptor = {
  kind: "documents", name: "orders", title: "Orders", embedded: true,
  columns: [{ columnName: "number", fieldName: "number", label: "Number", width: "" }],
  searchable: false, sort: { column: null, descending: false },
  newUrl: null, canWrite: false, pageSize: 20,
};
const openOrder = { id: "open", number: "Open order" };
const closedOrder = { id: "closed", number: "Closed order" };
const response = (rows: typeof openOrder[]) => new Response(JSON.stringify({
  rows, total: rows.length, hasMore: false, nextCursor: null,
}), { headers: { "Content-Type": "application/json" } });

describe("EntityList reactive where prop", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("updates the visible records when a page adds, changes and removes its filter", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      const where = new URL(url, "http://localhost").searchParams.get("where");
      const open = where ? (JSON.parse(where) as { open: boolean }).open : undefined;
      return response(open === undefined ? [openOrder, closedOrder] : [open ? openOrder : closedOrder]);
    });
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(<EntityList list={list} />);
    await screen.findByText("Open order");
    expect(screen.getByText("Closed order")).toBeInTheDocument();

    rerender(<EntityList list={list} where={{ open: true }} />);
    await waitFor(() => expect(screen.queryByText("Closed order")).not.toBeInTheDocument());
    await screen.findByText("Open order");

    rerender(<EntityList list={list} where={{ open: false }} />);
    await waitFor(() => expect(screen.queryByText("Open order")).not.toBeInTheDocument());
    await screen.findByText("Closed order");

    rerender(<EntityList list={list} />);
    await screen.findByText("Open order");
    expect(screen.getByText("Closed order")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("does not reload when a page recreates an equivalent filter object", async () => {
    const fetchMock = vi.fn(async () => response([openOrder]));
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(<EntityList list={list} where={{ open: true }} />);
    await screen.findByText("Open order");

    rerender(<EntityList list={list} where={{ open: true }} />);
    expect(screen.getByText("Open order")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("ignores a previous filter's response if it arrives after the new results", async () => {
    let finishAll!: (result: Response) => void;
    const fetchMock = vi.fn((url: string) => {
      if (new URL(url, "http://localhost").searchParams.has("where")) {
        return Promise.resolve(response([openOrder]));
      }
      return new Promise<Response>((resolve) => { finishAll = resolve; });
    });
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(<EntityList list={list} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    rerender(<EntityList list={list} where={{ open: true }} />);
    await screen.findByText("Open order");
    await act(async () => { finishAll(response([openOrder, closedOrder])); });

    expect(screen.getByText("Open order")).toBeInTheDocument();
    expect(screen.queryByText("Closed order")).not.toBeInTheDocument();
  });
});
