import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { EntityListView, type ListDescriptor } from "@/components/lists/entity-list";
import { MessagesProvider } from "@/providers/messages-provider";
import { AuthProvider, useAuth } from "@/providers/auth-provider";
import type { ReactNode } from "react";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
const user = { roles: ["EXPORTER"] };
vi.mock("maplibre-gl", () => ({ default: {} }));

beforeEach(() => {
  user.roles = ["EXPORTER"];
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider><MessagesProvider>{children}</MessagesProvider></AuthProvider>;
}

it("exports an embedded list's authored filter, current options and visible column order", async () => {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url.startsWith("/api/list/export/")) return new Response(null, { status: 413 });
    const body = url === "/api/auth/me"
      ? { authenticated: true, username: "manager", roles: ["ROLE_EXPORTER"] }
      : url === "/api/config" ? { listExportRoles: ["EXPORTER"] } : { rows: [], groups: [] };
    return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
  });
  vi.stubGlobal("fetch", fetchMock);
  const list: ListDescriptor = {
    kind: "catalogs", name: "Store items", title: "Store items", pageSize: 50, embedded: true,
    searchable: true, newUrl: null, canWrite: false, baseWhere: { site: "A" },
    sort: { column: "_code", descending: false },
    columns: [
      { columnName: "category", label: "Category", width: "" },
      { columnName: "_code", label: "SKU", width: "" },
    ],
    groupable: [{ columnName: "category", label: "Category", date: false }],
    defaultGroupBy: "category",
  };
  render(<EntityListView list={list} queryParams={{ q: ["widget search"], in: ["tag,a,b", "tag,c"] }} />,
    { wrapper });
  fireEvent.click(await screen.findByRole("button", { name: "Export" }));
  expect(await screen.findByRole("menu", { name: "Export" })).toBeTruthy();
  fireEvent.click(await screen.findByRole("menuitem", { name: "XLSX" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith("/api/list/export/catalogs/Store%20items",
    expect.objectContaining({ method: "POST" })));
  const call = fetchMock.mock.calls.find(([url]) => url.startsWith("/api/list/export/"));
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
    format: "xlsx",
    columns: [{ key: "category", label: "Category" }, { key: "_code", label: "SKU" }],
    query: { where: [JSON.stringify({ site: "A" })], q: ["widget search"], in: ["tag,a,b", "tag,c"], sort: ["_code"], dir: ["asc"] },
    groupBy: "category",
  });
});

const plainList: ListDescriptor = {
  kind: "catalogs", name: "Books", title: "Books", embedded: true, searchable: true,
  pageSize: 50, canWrite: false, newUrl: null,
  sort: { column: "_code", descending: false },
  columns: [{ columnName: "_code", label: "Code", width: "" }],
};

function mockFeed() {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => new Response(
    url.startsWith("/api/list/export/") ? null
      : JSON.stringify(url === "/api/config" ? { listExportRoles: ["EXPORTER"] }
        : url === "/api/auth/me" ? { authenticated: true, username: "tester", roles: user.roles } : { rows: [] }),
    { status: url.startsWith("/api/list/export/") ? 422 : 200,
      headers: { "Content-Type": "application/json" } },
  ));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

it("removes export when the current session loses its export role", async () => {
  mockFeed();
  function SessionRefresh() {
    const { refresh } = useAuth();
    return <button onClick={() => void refresh()}>Refresh session</button>;
  }
  render(<><SessionRefresh /><EntityListView list={plainList} /></>, { wrapper });
  await screen.findByRole("button", { name: "Export" });
  user.roles = ["READER"];
  fireEvent.click(screen.getByRole("button", { name: "Refresh session" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: "Export" })).toBeNull());
});

it("withholds export for a different role and for custom data feeds", async () => {
  mockFeed();
  user.roles = ["READER"];
  const first = render(<EntityListView list={plainList} />, { wrapper });
  await screen.findByText("No records");
  expect(screen.queryByRole("button", { name: "Export" })).toBeNull();
  first.unmount();
  user.roles = ["EXPORTER"];
  render(<EntityListView list={{ ...plainList, feed: "/api/crm/private-feed" }} />, { wrapper });
  await screen.findByText("No records");
  expect(screen.queryByRole("button", { name: "Export" })).toBeNull();
});

it("exports the chosen register view with its structured filter", async () => {
  const fetchMock = mockFeed();
  render(<EntityListView list={{ ...plainList, kind: "registers", name: "Stock",
    feed: "/api/list/registers/Stock/balance", baseWhere: { site: "A" } }} />, { wrapper });
  fireEvent.click(await screen.findByRole("button", { name: "Export" }));
  fireEvent.click(await screen.findByRole("menuitem", { name: "CSV" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith("/api/list/export/registers/Stock",
    expect.objectContaining({ method: "POST" })));
  const call = fetchMock.mock.calls.find(([url]) => url.startsWith("/api/list/export/"));
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
    format: "csv", view: "balance", query: { where: ['{"site":"A"}'] },
  });
});

it("uses the current search text even before the list's debounce completes", async () => {
  const fetchMock = mockFeed();
  render(<EntityListView list={plainList} />, { wrapper });
  const button = await screen.findByRole("button", { name: "Export" });
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "new search" } });
  fireEvent.click(button);
  fireEvent.click(await screen.findByRole("menuitem", { name: "CSV" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith("/api/list/export/catalogs/Books",
    expect.objectContaining({ method: "POST" })));
  const call = fetchMock.mock.calls.find(([url]) => url.startsWith("/api/list/export/"));
  expect(JSON.parse(String(call?.[1]?.body)).query.q).toEqual(["new search"]);
});

it("dismisses the export menu with Escape without starting a download", async () => {
  const fetchMock = mockFeed();
  render(<EntityListView list={plainList} />, { wrapper });
  const trigger = await screen.findByRole("button", { name: "Export" });
  expect(trigger).toHaveAttribute("aria-haspopup", "menu");
  fireEvent.click(trigger);
  await screen.findByRole("menuitem", { name: "XLSX" });
  fireEvent.keyDown(document, { key: "Escape" });
  await waitFor(() => expect(screen.queryByRole("menu", { name: "Export" })).toBeNull());
  expect(fetchMock.mock.calls.filter(([url]) => url.startsWith("/api/list/export/"))).toHaveLength(0);
  await waitFor(() => expect(document.activeElement).toBe(trigger));
});

it.each(["XLSX", "CSV"])("closes the menu and blocks duplicate downloads while %s is being prepared", async (format) => {
  const fetchMock = mockFeed();
  const defaultFetch = fetchMock.getMockImplementation()!;
  let finish!: (response: Response) => void;
  const download = new Promise<Response>((resolve) => { finish = resolve; });
  fetchMock.mockImplementation(async (url, init) => url.startsWith("/api/list/export/") ? download : defaultFetch(url, init));
  render(<EntityListView list={plainList} />, { wrapper });
  const trigger = await screen.findByRole("button", { name: "Export" });
  fireEvent.click(trigger);
  fireEvent.click(await screen.findByRole("menuitem", { name: format }));
  await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url.startsWith("/api/list/export/"))).toHaveLength(1));
  expect(screen.queryByRole("menu", { name: "Export" })).toBeNull();
  expect(trigger).toBeDisabled();
  fireEvent.click(trigger);
  expect(fetchMock.mock.calls.filter(([url]) => url.startsWith("/api/list/export/"))).toHaveLength(1);
  await act(async () => { finish(new Response(null, { status: 422 })); });
  await waitFor(() => expect(trigger).not.toBeDisabled());
});
