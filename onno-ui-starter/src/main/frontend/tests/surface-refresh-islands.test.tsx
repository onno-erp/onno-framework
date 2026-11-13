import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FormPortals } from "@/lib/form-bridge";
import { ListPortals } from "@/lib/list-bridge";
import type { FormDescriptor } from "@/components/entity-form-widget";
import type { ListDescriptor } from "@/components/entity-list-widget";
import type { ActionItem } from "@/lib/actions-menu-bridge";
import { requestSurfaceRefresh } from "@/lib/surface-refresh";

const { getCatalogItem, getRecordRefresh } = vi.hoisted(() => ({
  getCatalogItem: vi.fn(), getRecordRefresh: vi.fn(),
}));
vi.mock("@/lib/api", () => ({
  api: { getCatalogItem, getRecordRefresh }, ApiError: class ApiError extends Error {},
}));
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/related-list-panel", () => ({ RelatedListPanel: () => null }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("@/lib/actions-menu-bridge", () => ({
  ActionsCluster: ({ items }: { items: ActionItem[] }) => items.map(item => (
    <button key={item.url} disabled={item.disabled}>{item.label}</button>
  )),
}));

const source = "/catalogs/rooms/r1";
const refreshContext = { profile: "ops", viewport: "desktop" };
const advance = { label: "Advance", url: "onno://action/catalogs/rooms/advance/r1" };
const surfaces: HTMLElement[] = [];
let fetchMock: ReturnType<typeof vi.fn>;

function surface(path: string) {
  const el = document.createElement("div");
  el.dataset.onnoSurfacePath = path;
  document.body.append(el);
  surfaces.push(el);
  return el;
}

function form(host: HTMLElement, overrides: Partial<FormDescriptor> = {}) {
  const el = document.createElement("onno-form") as HTMLElement & { form: FormDescriptor };
  el.form = {
    kind: "catalogs", name: "rooms", id: "r1", title: "Room", submitLabel: "Save",
    meta: { name: "Rooms", attributes: [{
      fieldName: "label", columnName: "label", displayName: "Label", javaType: "String",
      required: false, visibleInForm: true, order: 0,
    } as never] },
    initial: { _id: "r1", label: "Old value" },
    ...overrides,
  };
  host.append(el);
  return el;
}

function list(host: HTMLElement, overrides: Partial<ListDescriptor> = {}) {
  const el = document.createElement("onno-list") as HTMLElement & { list: ListDescriptor };
  el.list = {
    kind: "catalogs", name: "rooms", title: "Rooms", searchable: true,
    columns: [{ columnName: "label", label: "Label", width: "" }],
    sort: { column: null, descending: false }, newUrl: null, canWrite: true,
    actions: [], embedded: true, pageSize: 20, ...overrides,
  };
  host.append(el);
  return el;
}

beforeEach(() => {
  getCatalogItem.mockReset().mockResolvedValue({ _id: "r1", label: "Server value" });
  getRecordRefresh.mockReset();
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  fetchMock = vi.fn(async () => new Response(JSON.stringify({
    rows: [{ _id: "r1", label: "Stored room" }], total: 1, hasMore: false, nextCursor: null,
  }), { headers: { "Content-Type": "application/json" } }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  act(() => { for (const el of surfaces.splice(0)) el.remove(); });
  vi.unstubAllGlobals();
});

describe("in-place action refresh through real DivKit island portals (#447)", () => {
  it("updates a clean form without replacing its input or touching another tab", async () => {
    const host = surface(source);
    form(host, { refreshContext, actions: [advance] });
    getRecordRefresh.mockResolvedValue({
      record: { _id: "r1", label: "Server value" },
      actions: [{ ...advance, label: "Complete", disabled: true }], readOnly: false,
    });
    const other = surface("/catalogs/rooms/r2");
    form(other, { id: "r2", initial: { _id: "r2", label: "Other room" } });
    render(<FormPortals />);
    const input = screen.getByDisplayValue("Old value");
    const otherInput = screen.getByDisplayValue("Other room");
    input.focus();

    act(() => requestSurfaceRefresh(source));

    await waitFor(() => expect(input).toHaveValue("Server value"));
    expect(screen.getByDisplayValue("Server value")).toBe(input);
    expect(input).toHaveFocus();
    expect(screen.getByDisplayValue("Other room")).toBe(otherInput);
    expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Complete" })).toBeDisabled();
    expect(getRecordRefresh).toHaveBeenCalledExactlyOnceWith("catalogs", "rooms", "r1", refreshContext);
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("keeps a dirty form while a sibling list refreshes its own data", async () => {
    const host = surface(source);
    form(host, { refreshContext, actions: [advance] });
    getRecordRefresh.mockResolvedValue({
      record: { _id: "r1", label: "Server value" }, actions: [], readOnly: false,
    });
    const listHost = list(host);
    render(<><FormPortals /><ListPortals /></>);
    await within(listHost).findByText("Stored room");
    const input = screen.getByDisplayValue("Old value");
    fireEvent.change(input, { target: { value: "My draft" } });
    fetchMock.mockClear();

    act(() => requestSurfaceRefresh(source));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/^\/api\/list\/catalogs\/rooms\?/);
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect(input).toHaveValue("My draft");
    expect(screen.getByText("This record changed elsewhere.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument());
  });

  it("keeps edits started after the record request was sent", async () => {
    form(surface(source), { refreshContext, actions: [advance] });
    render(<FormPortals />);
    const snapshot = { record: { _id: "r1", label: "Late server value" }, actions: [], readOnly: false };
    let finish!: (result: typeof snapshot) => void;
    getRecordRefresh.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    act(() => requestSurfaceRefresh(source));
    expect(getRecordRefresh).toHaveBeenCalledOnce();
    const input = screen.getByDisplayValue("Old value");
    fireEvent.change(input, { target: { value: "Typing while loading" } });
    await act(async () => finish(snapshot));
    expect(input).toHaveValue("Typing while loading");
    expect(screen.getByDisplayValue("Typing while loading")).toBe(input);
    expect(screen.getByText("This record changed elsewhere.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument();
  });

  it("does not restore older fields or actions when refresh responses arrive out of order", async () => {
    form(surface(source), { refreshContext, actions: [advance] });
    render(<FormPortals />);
    const older = { record: { _id: "r1", label: "Older state" }, actions: [advance], readOnly: false };
    let finishOlder!: (result: typeof older) => void;
    getRecordRefresh.mockImplementationOnce(() => new Promise((resolve) => { finishOlder = resolve; }))
      .mockResolvedValueOnce({ record: { _id: "r1", label: "Newer state" }, actions: [], readOnly: false });
    act(() => requestSurfaceRefresh(source));
    await act(async () => requestSurfaceRefresh(source));
    await act(async () => finishOlder(older));

    expect(screen.getByDisplayValue("Newer state")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument();
  });

  it("preserves a list's search and sort and isolates tabs with different queries", async () => {
    const host = surface("/reports?period=month");
    const listHost = list(host, { sort: { column: "label", descending: true } });
    const otherHost = list(surface("/reports?period=year"));
    render(<ListPortals />);
    await within(listHost).findByText("Stored room");
    await within(otherHost).findByText("Stored room");
    const search = within(listHost).getByRole("textbox");
    fireEvent.change(search, { target: { value: "suite" } });
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) =>
      new URL(String(url), "http://localhost").searchParams.get("q") === "suite")).toBe(true));
    fetchMock.mockClear();

    act(() => requestSurfaceRefresh("/reports?period=month"));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    const query = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost").searchParams;
    expect(query.get("q")).toBe("suite");
    expect(query.get("sort")).toBe("label");
    expect(query.get("dir")).toBe("desc");
    expect(within(listHost).getByRole("textbox")).toBe(search);
    expect(search).toHaveValue("suite");
    expect(within(otherHost).getByRole("textbox")).toHaveValue("");
  });

  it.each(["new", "r1/duplicate"])("does not overwrite a %s form", (id) => {
    const path = `/catalogs/rooms/${id}`;
    form(surface(path), id === "new" ? { id: undefined } : { duplicate: true });
    render(<FormPortals />);
    const input = screen.getByDisplayValue("Old value");
    act(() => requestSurfaceRefresh(path));
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect(input).toHaveValue("Old value");
  });
});
