import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { shortRecordNumber } from "@/lib/record-number";
import { EntityListView, ListCell, type ListColumn, type ListDescriptor } from "@/components/lists/entity-list";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it.each([
  ["CON-00000001", "CON-1"], ["EVENT-00001", "EVENT-1"], ["O-00000000010", "O-10"],
  ["00000002", "2"], ["0000", "0"], ["0", "0"], ["CON-1", "CON-1"],
  ["2026/CON-00042", "2026/CON-42"], ["CON00042", "CON42"],
  ["O-0009007199254740993", "O-9007199254740993"],
  ["O-10001", "O-10001"], ["SKU-001-A", "SKU-001-A"], ["", ""], [null, ""],
])("displays %s as %s without losing significant digits", (raw, expected) => {
  expect(shortRecordNumber(raw)).toBe(expected);
});

it.each([
  ["_number", "number"], ["_code", "code"], ["number", "number"], ["code", "code"],
])("compacts system column %s for all shared list consumers", (columnName, fieldName) => {
  const row = { [fieldName]: "CON-00000001" };
  render(<ListCell row={row} col={{ columnName, fieldName, label: "Identifier", width: "" }} />);
  expect(screen.getByText("CON-1")).toBeTruthy();
  expect(row[fieldName]).toBe("CON-00000001");
});

it("preserves business text, explicit formats and authored renderers", () => {
  const col: ListColumn = { columnName: "note", label: "Note", width: "" };
  const { rerender } = render(<ListCell row={{ note: "CON-00000001" }} col={col} />);
  expect(screen.getByText("CON-00000001")).toBeTruthy();
  rerender(<ListCell row={{ code: "000123" }} col={{ ...col, columnName: "code", format: { number: { decimals: 2 } } }} />);
  expect(screen.getByText("123.00")).toBeTruthy();
  rerender(<ListCell row={{ number: "O-00001" }} col={{ ...col, columnName: "number", render: value => `Custom ${value}` }} />);
  expect(screen.getByText("Custom O-00001")).toBeTruthy();
});

it("keeps server ordering and raw sort keys while displaying 1, 2, 10 in both directions", async () => {
  vi.stubGlobal("ResizeObserver", ResizeObserverMock);
  vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
  vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  const requests: URL[] = [];
  const rows = ["00001", "00002", "00010"].map((n, i) => ({ id: `order-${i}`, number: `O-${n}` }));
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    const url = new URL(input, "http://localhost");
    requests.push(url);
    return new Response(JSON.stringify({ rows: url.searchParams.get("dir") === "desc" ? [...rows].reverse() : rows, total: 3, hasMore: false, nextCursor: null }), { headers: { "Content-Type": "application/json" } });
  }));
  const list: ListDescriptor = { kind: "documents", name: "orders", title: "Orders", columns: [{ columnName: "_number", fieldName: "number", label: "Number", width: "" }],
    searchable: false, sort: { column: "_number", descending: false }, newUrl: null, canWrite: false, pageSize: 20, embedded: true, commands: [] };
  render(<EntityListView list={list} />);
  const displayed = () => Array.from(document.querySelectorAll("[data-onno-row]")).map(el => el.textContent);
  await screen.findByText("O-10");
  expect(displayed()).toEqual(["O-1", "O-2", "O-10"]);
  fireEvent.click(screen.getByRole("button", { name: "Number" }));
  await waitFor(() => expect(displayed()).toEqual(["O-10", "O-2", "O-1"]));
  expect(requests.at(-1)?.searchParams.get("sort")).toBe("_number");
  expect(requests.at(-1)?.searchParams.get("dir")).toBe("desc");
  expect(rows.map(row => row.number)).toEqual(["O-00001", "O-00002", "O-00010"]);
});
