import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import {
  EntityListWidget,
  type ListAction,
  type ListDescriptor,
} from "@/components/entity-list-widget";

vi.mock("@/lib/presence-store", () => ({
  useViewersById: () => new Map(),
}));

vi.mock("@/components/list-map-view", () => ({
  ListMapView: () => null,
}));

vi.mock("maplibre-gl", () => ({
  default: {},
}));

class MockResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const rowId = "6b4bc2b6-a1a2-4bf4-b9e1-b5f3afad152d";

function action(label: string): ListAction {
  return {
    key: label.toLowerCase().replaceAll(" ", "-"),
    label,
    icon: "",
    scope: "row",
    menu: "Change status",
    server: true,
    kind: "catalogs",
    name: "orders",
  };
}

function descriptor(cellMenu = false): ListDescriptor {
  return {
    kind: "catalogs",
    name: "orders",
    title: "Orders",
    columns: [{
      columnName: "status",
      label: "Status",
      width: "",
      cellMenu: cellMenu ? "Change status" : undefined,
    }],
    searchable: false,
    sort: { column: null, descending: false },
    newUrl: null,
    canWrite: true,
    actions: [],
    dynamicActions: true,
    pageSize: 20,
    embedded: true,
  };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function installFetch(dynamicResponses: Array<Response | Error>) {
  let dynamicIndex = 0;
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith("/api/list/catalogs/orders")) {
      return json({
        rows: [{ _id: rowId, status: "pending", status_display: "Pending" }],
        nextCursor: null,
        hasMore: false,
        total: 1,
      });
    }
    if (url.startsWith("/api/actions/catalogs/orders?")) {
      const next = dynamicResponses[dynamicIndex++];
      if (next instanceof Error) throw next;
      return next;
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

async function row() {
  return await waitFor(() => {
    const element = document.querySelector<HTMLElement>("[data-onno-row]");
    expect(element).not.toBeNull();
    return element!;
  });
}

async function closeMenu() {
  fireEvent.click(document.body);
  await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
}

describe("late-bound entity-list actions", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", MockResizeObserver);
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) =>
      window.setTimeout(() => callback(0), 0)
    );
    vi.stubGlobal("cancelAnimationFrame", (handle: number) => window.clearTimeout(handle));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("refreshes an ordinary submenu on every row-menu open", async () => {
    const fetchMock = installFetch([
      json({ actions: [action("Set new")], rowActions: {} }),
      json({ actions: [action("Set completed")], rowActions: {} }),
    ]);
    render(<EntityListWidget list={descriptor()} />);
    const listRow = await row();

    fireEvent.contextMenu(listRow, { clientX: 100, clientY: 100 });
    const firstTrigger = await screen.findByRole("menuitem", { name: "Change status" });
    fireEvent.mouseEnter(firstTrigger.parentElement!);
    expect(await screen.findByRole("menuitem", { name: "Set new" })).toBeInTheDocument();
    await closeMenu();

    fireEvent.contextMenu(listRow, { clientX: 100, clientY: 100 });
    await waitFor(() =>
      expect(fetchMock.mock.calls.filter(([url]) => String(url).startsWith("/api/actions/")))
        .toHaveLength(2)
    );
    const secondTrigger = await screen.findByRole("menuitem", { name: "Change status" });
    fireEvent.mouseEnter(secondTrigger.parentElement!);
    expect(await screen.findByRole("menuitem", { name: "Set completed" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Set new" })).not.toBeInTheDocument();
  });

  it("refreshes the flat cell-menu mode", async () => {
    installFetch([
      json({ actions: [action("Set on hold")], rowActions: {} }),
    ]);
    render(<EntityListWidget list={descriptor(true)} />);
    await row();

    fireEvent.contextMenu(await screen.findByText("Pending"), { clientX: 120, clientY: 120 });

    expect(await screen.findByRole("menuitem", { name: "Set on hold" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Change status" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Open" })).not.toBeInTheDocument();
  });

  it("keeps the last successful descriptors when a later refresh fails", async () => {
    installFetch([
      json({ actions: [action("Set approved")], rowActions: {} }),
      new Error("network down"),
    ]);
    render(<EntityListWidget list={descriptor(true)} />);
    await row();

    fireEvent.contextMenu(await screen.findByText("Pending"), { clientX: 120, clientY: 120 });
    expect(await screen.findByRole("menuitem", { name: "Set approved" })).toBeInTheDocument();
    await closeMenu();

    fireEvent.contextMenu(await screen.findByText("Pending"), { clientX: 120, clientY: 120 });
    expect(await screen.findByRole("menuitem", { name: "Set approved" })).toBeInTheDocument();
  });

  it("does not request menu descriptors for a static-only entity", async () => {
    const fetchMock = installFetch([]);
    const list = descriptor();
    list.dynamicActions = false;
    list.actions = [action("Static action")];
    render(<EntityListWidget list={list} />);

    fireEvent.contextMenu(await row(), { clientX: 100, clientY: 100 });
    expect(await screen.findByRole("menuitem", { name: "Change status" })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith("/api/actions/"))).toBe(false);
  });
});
