import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { EntityListWidget, type ListDescriptor } from "@/components/entity-list-widget";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });

// #275: list column widths are separate from form layout. The server resolves a ListSpec.width
// override (or a pixel field hint) to whole pixels; form-layout tokens such as "half"/"1/2" must
// never become one-pixel tracks, even if an older server still forwards them.
function list(columns: ListDescriptor["columns"]): ListDescriptor {
  return { kind: "catalogs", name: "orders", title: "Orders", columns, searchable: false,
    sort: { column: null, descending: false }, newUrl: null, canWrite: true, pageSize: 20, embedded: true, actions: [] };
}

function gridTemplate(): string {
  const tracks = Array.from(document.querySelectorAll<HTMLElement>("[style]"))
    .map((el) => el.style.gridTemplateColumns)
    .filter(Boolean);
  expect(tracks.length).toBeGreaterThan(0);
  return tracks[0];
}

describe("entity list column widths", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
    vi.stubGlobal("fetch", vi.fn(async () => response({
      rows: [{ id: "one", status: "New", channel: "Email", note: "First" }], total: 1, hasMore: false, nextCursor: null,
    })));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("honors a resolved pixel override next to a half-width form field", async () => {
    render(<EntityListWidget list={list([
      { columnName: "status", label: "Status", width: "105" },
      { columnName: "channel", label: "Channel", width: "90px" },
      { columnName: "note", label: "Note", width: "" },
    ])} />);
    await screen.findByText("First");
    expect(gridTemplate()).toBe("105px 90px minmax(0,1fr)");
  });

  it("never collapses a form fraction into a one-pixel track", async () => {
    render(<EntityListWidget list={list([
      { columnName: "status", label: "Status", width: "1/2" },
      { columnName: "channel", label: "Channel", width: "half" },
      { columnName: "note", label: "Note", width: "50%" },
    ])} />);
    await screen.findByText("First");
    const template = gridTemplate();
    expect(template).not.toMatch(/\b1px\b/);
    expect(template).toBe("minmax(0,1fr) minmax(0,1fr) minmax(0,1fr)");
  });
});
