import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EntityListView, type ListDescriptor } from "@/components/lists/entity-list";
import { dateOnlySort, daySortColumn, parseSort, serializeSort, toggleListSort } from "@/lib/list-sort";
import { publishDisplayTimeZone } from "@/lib/time-zone";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
const list: ListDescriptor = {
  kind: "documents", name: "orders", title: "Orders", embedded: true,
  columns: ["status", "price"].map(columnName => ({ columnName, fieldName: columnName, label: columnName, width: "" })),
  searchable: false, sort: { column: "date", descending: true }, newUrl: null, pageSize: 20,
};

describe("list sorting by header click order", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  });
  afterEach(() => { publishDisplayTimeZone(undefined); cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

  it("appends keys, keeps their priority on direction changes, removes and re-adds at the end", async () => {
    const urls: URL[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      urls.push(new URL(url, "http://localhost"));
      return new Response(JSON.stringify({ rows: [], hasMore: false, nextCursor: null }));
    }));
    render(<EntityListView list={list} />);
    await waitFor(() => expect(urls).toHaveLength(1));
    const click = async (name: string, sort: string) => {
      fireEvent.click(screen.getByRole("button", { name: new RegExp(`^${name}`) }));
      await waitFor(() => expect(serializeSort(parseSort(urls.at(-1)?.searchParams.get("sort") ?? "", urls.at(-1)?.searchParams.get("dir") === "desc"))).toBe(sort));
    };
    await click("status", "status"); // replaces the default date sort
    await click("price", "status,price");
    expect(screen.getByRole("button", { name: /^status/ })).toHaveTextContent("1");
    expect(screen.getByRole("button", { name: /^price/ })).toHaveTextContent("2");
    expect(screen.getByRole("button", { name: /^status/ })).toHaveClass("text-primary");
    expect(screen.queryByRole("group", { name: "Sorting:" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^status/ })).toHaveAttribute("aria-description", "Sort priority 1");
    expect(screen.getByRole("button", { name: /^price/ })).toHaveAttribute("aria-description", "Sort priority 2");
    await click("status", "-status,price");
    await click("status", "price");
    expect(screen.getByRole("button", { name: /^price/ })).toHaveAttribute("aria-description", "Sort priority 1");
    await click("status", "price,status");
    await click("price", "-price,status");
    await click("price", "status");
    await click("status", "-status");
    await click("status", "");
  });

  it("restores all criteria from the URL and keeps them on reload", async () => {
    window.history.replaceState(null, "", "/documents/orders?sort=status,-price&dir=asc");
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ rows: [], hasMore: false })));
    vi.stubGlobal("fetch", fetchMock);
    render(<EntityListView list={{ ...list, embedded: false }} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.getByRole("button", { name: /^status/ })).toHaveAttribute("aria-description", "Sort priority 1");
    expect(screen.getByRole("button", { name: /^price/ })).toHaveAttribute("aria-description", "Sort priority 2");
    expect(new URLSearchParams(window.location.search).get("sort")).toBe("status,-price");
  });

  it("requests day precision for date-only columns before adding an amount criterion", async () => {
    publishDisplayTimeZone("Europe/Moscow");
    const urls: URL[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      urls.push(new URL(url, "http://localhost"));
      return new Response(JSON.stringify({ rows: [], hasMore: false }));
    }));
    const dated: ListDescriptor = { ...list, sort: { column: null, descending: false }, columns: [
      { columnName: "_date", fieldName: "date", label: "Date", width: "", type: "date", format: { date: "medium" } },
      list.columns[1],
    ] };
    render(<EntityListView list={dated} />);
    await waitFor(() => expect(urls).toHaveLength(1));
    fireEvent.click(screen.getByRole("button", { name: "Date" }));
    await waitFor(() => expect(urls.at(-1)?.searchParams.get("sort")).toBe("_date:day@Europe/Moscow"));
    fireEvent.click(screen.getByRole("button", { name: "price" }));
    await waitFor(() => expect(urls.at(-1)?.searchParams.get("sort")).toBe("_date:day@Europe/Moscow,price"));
    expect(serializeSort(parseSort("_date:day@Europe/Moscow,price"))).toBe("_date,price");
    expect(daySortColumn("starts_at", { date: { dateStyle: "medium", timeZone: "America/New_York" } })).toBe("starts_at:day@America/New_York");
    expect(dateOnlySort({ date: { dateStyle: "medium", timeStyle: "short" } })).toBe(false);
  });

  it("round-trips legacy directions, skips duplicates, and toggles a secondary key independently", () => {
    expect(serializeSort(parseSort("price", true))).toBe("-price");
    expect(serializeSort(parseSort("status,-price,status"))).toBe("status,-price");
    expect(serializeSort(toggleListSort(parseSort("status,price"), "price"))).toBe("status,-price");
  });
});
