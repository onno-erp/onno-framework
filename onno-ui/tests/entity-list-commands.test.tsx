import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EntityListView, type ListDescriptor } from "@/components/lists/entity-list";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
vi.mock("@/lib/icons", () => ({ Icon: ({ name }: { name: string }) => <i data-icon={name} /> }));

class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });

function list(): ListDescriptor {
  return {
    kind: "catalogs", name: "orders", title: "Orders",
    columns: [{ columnName: "description", label: "Name", width: "" }],
    searchable: false, sort: { column: null, descending: false }, newUrl: null, canWrite: true, pageSize: 20, embedded: true,
    commands: [
      { key: "pause", title: "Pause", target: "record" },
      { key: "archive", title: "Archive", target: "record" },
      { key: "remind", title: "Remind", target: "record" },
      { key: "export", title: "Export", target: "collection" },
    ],
    // The app's `commands` customization: presentation lives in React, behaviour on the server.
    commandPresentation: {
      pause: { placement: "row", icon: (row) => (row.paused ? "play" : "pause") },
      archive: { placement: "hidden" },
      export: { placement: "primary", icon: "download" },
    },
  };
}

describe("entity-list commands", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
    fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/commands/")) return json({ refresh: false });
      return json({
        // The feed's per-record state retitles the row's command.
        rows: [{ id: "one", description: "First", paused: true, _actions: { pause: { visible: true, enabled: true, title: "Resume" } } }],
        total: 1, hasMore: false, nextCursor: null,
      });
    });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  const commandCalls = () => fetchMock.mock.calls
    .filter(([url]) => String(url).startsWith("/api/commands/"))
    .map(([url, init]) => [String(url), JSON.parse(String((init as RequestInit).body))]);

  it("renders a row-placed command inline with its per-record title and icon, and runs it on the row", async () => {
    render(<EntityListView list={list()} />);
    const button = await screen.findByRole("button", { name: "Resume" });
    expect(button.querySelector("[data-icon]")).toHaveAttribute("data-icon", "play");

    fireEvent.click(button);

    await waitFor(() => expect(commandCalls()).toEqual([
      ["/api/commands/catalogs/orders/pause", { id: "one", inputs: {} }],
    ]));
  });

  it("offers a primary collection command in the toolbar and runs it without a record", async () => {
    render(<EntityListView list={list()} />);
    const button = await screen.findByRole("button", { name: "Export" });
    expect(button.className).toContain("bg-primary");
    expect(button.querySelector("[data-icon]")).toHaveAttribute("data-icon", "download");

    fireEvent.click(button);

    await waitFor(() => expect(commandCalls()).toEqual([["/api/commands/catalogs/orders/export", { inputs: {} }]]));
  });

  it("lists record commands in the row menu, leaving hidden ones out", async () => {
    render(<EntityListView list={list()} />);
    await screen.findByRole("button", { name: "Resume" });
    fireEvent.contextMenu(document.querySelector("[data-onno-row]")!, { clientX: 50, clientY: 50 });

    expect(await screen.findByRole("menuitem", { name: "Remind" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Resume" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Archive" })).toBeNull();
    // No icon named: the neutral default.
    expect(screen.getByRole("menuitem", { name: "Remind" }).querySelector("[data-icon]")).toHaveAttribute("data-icon", "circle-dot");
  });
});
