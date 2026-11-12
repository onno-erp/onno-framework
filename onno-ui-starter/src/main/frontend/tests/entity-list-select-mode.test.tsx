import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import {
  EntityListWidget,
  type ListDescriptor,
  type ListSelectMode,
  type PickRowState,
} from "@/components/entity-list-widget";
import type { EntityRecord } from "@/lib/types";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }

const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
const rows = (n: number) => Array.from({ length: n }, (_, i) => ({ id: `r${i + 1}`, description: `Row ${i + 1}` }));

function list(extra: Partial<ListDescriptor> = {}): ListDescriptor {
  return { kind: "catalogs", name: "books", title: "Books", columns: [{ columnName: "description", label: "Name", width: "" }],
    searchable: true, sort: { column: null, descending: false }, newUrl: null, canWrite: false, pageSize: 20,
    embedded: true, ...extra };
}

const pickRow = (text: string) => screen.getByText(text).closest("[data-onno-pick-row]") as HTMLElement;

/** A host that owns the highlight, as the picker dialog does. */
function Picker({ states = {}, onActivate, onRowsLoaded, query }: {
  query?: string;
  states?: Record<string, PickRowState>;
  onActivate: (row: EntityRecord) => void;
  onRowsLoaded?: (rows: EntityRecord[]) => void;
}) {
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  const mode: ListSelectMode = {
    highlightedId,
    onHighlight: (row) => setHighlightedId(String(row.id)),
    onActivate,
    rowState: (row) => states[String(row.id)],
    onRowsLoaded,
    query,
  };
  return (
    <div data-onno-picker="">
      <input aria-label="Find" />
      <EntityListWidget list={list()} selectMode={mode} toolbarOptions={{ search: false }} />
    </div>
  );
}

describe("list select mode (#411)", () => {
  const navigate = vi.fn();
  beforeEach(() => {
    navigate.mockClear();
    window.addEventListener("onno:action", navigate);
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
    vi.stubGlobal("fetch", vi.fn(async () => response({ rows: rows(5), hasMore: false, nextCursor: null })));
  });
  afterEach(() => {
    window.removeEventListener("onno:action", navigate);
    cleanup();
    vi.unstubAllGlobals();
  });

  it("highlights a row on click and activates it on double click, never opening the record", async () => {
    const onActivate = vi.fn();
    render(<Picker onActivate={onActivate} />);
    await screen.findByText("Row 2");

    fireEvent.click(pickRow("Row 2"));
    expect(pickRow("Row 2")).toHaveAttribute("data-onno-selected", "true");
    expect(pickRow("Row 2")).toHaveAttribute("aria-selected", "true");
    expect(onActivate).not.toHaveBeenCalled();

    fireEvent.doubleClick(pickRow("Row 3"));
    expect(onActivate).toHaveBeenCalledWith(expect.objectContaining({ id: "r3" }));
    expect(navigate).not.toHaveBeenCalled();
  });

  it("stamps no record-link marker and keeps the row menu shut", async () => {
    render(<Picker onActivate={vi.fn()} />);
    await screen.findByText("Row 1");

    expect(document.querySelector("[data-onno-row]")).toBeNull();
    const event = new MouseEvent("contextmenu", { bubbles: true, cancelable: true });
    pickRow("Row 1").dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("draws no hidden row and refuses to activate a disabled one", async () => {
    const onActivate = vi.fn();
    render(<Picker onActivate={onActivate} states={{
      r2: { hidden: true },
      r4: { disabled: true, reason: "Booked", badge: "Busy", tone: "danger" },
    }} />);
    await screen.findByText("Row 1");

    expect(screen.queryByText("Row 2")).toBeNull();
    const disabled = pickRow("Row 4");
    expect(disabled).toHaveAttribute("aria-disabled", "true");
    expect(disabled).toHaveAttribute("title", "Booked");
    expect(screen.getByText("Busy")).toBeInTheDocument();
    fireEvent.doubleClick(disabled);
    expect(onActivate).not.toHaveBeenCalled();
  });

  it("moves the highlight with the arrows from the search box and activates on Enter", async () => {
    const onActivate = vi.fn();
    render(<Picker onActivate={onActivate} states={{ r2: { disabled: true } }} />);
    await screen.findByText("Row 1");
    const search = screen.getByLabelText("Find");
    search.focus();

    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(pickRow("Row 1")).toHaveAttribute("data-onno-selected", "true");
    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(pickRow("Row 2")).toHaveAttribute("data-onno-selected", "true");
    fireEvent.keyDown(search, { key: "Enter" });
    expect(onActivate).not.toHaveBeenCalled(); // Row 2 is disabled

    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.keyDown(search, { key: "ArrowUp" });
    expect(pickRow("Row 1")).toHaveAttribute("data-onno-selected", "true");
    fireEvent.keyDown(search, { key: "Enter" });
    expect(onActivate).toHaveBeenCalledWith(expect.objectContaining({ id: "r1" }));
  });

  it("lets a host-owned search box drive the feed query", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) =>
      response({ rows: rows(2), hasMore: false, nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    render(<Picker onActivate={vi.fn()} query="Row" />);
    await waitFor(() => expect(fetchMock.mock.calls.some((c) =>
      new URL(String(c[0]), "http://localhost").searchParams.get("q") === "Row")).toBe(true));
  });

  it("reports each loaded batch so the host can resolve how to present it", async () => {
    const onRowsLoaded = vi.fn();
    render(<Picker onActivate={vi.fn()} onRowsLoaded={onRowsLoaded} />);
    await waitFor(() => expect(onRowsLoaded).toHaveBeenCalled());
    expect(onRowsLoaded.mock.calls[0][0]).toHaveLength(5);
  });

  it("leaves an ordinary list untouched: rows open their record", async () => {
    render(<EntityListWidget list={list()} />);
    await screen.findByText("Row 1");

    expect(document.querySelector("[data-onno-pick-row]")).toBeNull();
    fireEvent.click(screen.getByText("Row 1").closest("[data-onno-row]") as HTMLElement);
    expect(navigate).toHaveBeenCalled();
  });
});
