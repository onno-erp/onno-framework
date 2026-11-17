import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { ViewNodeView } from "@/views/route-view";
import type { FormDescriptor } from "@/components/entities/record-form";
import type { ListDescriptor } from "@/components/lists/entity-list";
import type { RecordAction } from "@/components/actions/record-actions";
import { invalidateEntity, queryClient } from "@/lib/query";

const { getCatalogItem, getRecordRefresh } = vi.hoisted(() => ({
  getCatalogItem: vi.fn(), getRecordRefresh: vi.fn(),
}));
vi.mock("@/lib/api", () => ({
  api: { getCatalogItem, getRecordRefresh }, ApiError: class ApiError extends Error {},
}));
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/fields/ref-select", () => ({ RefSelect: () => null }));
vi.mock("@/components/fields/date-picker", () => ({ DatePicker: () => null }));
vi.mock("@/components/maps/map-editor", () => ({ MapEditor: () => null }));
vi.mock("@/components/fields/image-picker", () => ({ ImagePicker: () => null, GalleryPicker: () => null }));
vi.mock("@/components/fields/file-picker", () => ({ FilePicker: () => null }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("@/components/actions/record-actions", () => ({
  RecordActions: ({ items }: { items: RecordAction[] }) => items.map(item => (
    <button key={item.url} disabled={item.disabled}>{item.label}</button>
  )),
}));

const source = "/catalogs/rooms/r1";
const advance = { key: "advance", title: "Advance", target: "record" as const };
let fetchMock: ReturnType<typeof vi.fn>;

// A pane (a workspace tab) showing its route.
function Surface({ path, children }: { path: string; children: ReactNode }) {
  return <div data-path={path}>{children}</div>;
}

// A command's refresh: invalidate the entity (and, for a record command, name its record). The
// query cache tells its subscribers on the next tick.
async function refresh(ids?: string[]) {
  await act(async () => {
    invalidateEntity("catalogs/rooms", ids);
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

// A record form node exactly as RouteView renders it (inside an Boundary).
function Form(overrides: Partial<FormDescriptor> = {}) {
  const form: FormDescriptor = {
    kind: "catalogs", name: "rooms", id: "r1",
    meta: { name: "Rooms", attributes: [{
      fieldName: "label", columnName: "label", displayName: "Label", javaType: "String",
      required: false, visibleInForm: true, order: 0,
    } as never] },
    initial: { _id: "r1", label: "Old value" },
    ...overrides,
  };
  return <ViewNodeView node={{ type: "form", form: form as never }} />;
}

// An embedded list node as RouteView renders it; the testid wrapper scopes queries to it.
function List({ testId, ...overrides }: Partial<ListDescriptor> & { testId: string }) {
  const list: ListDescriptor = {
    kind: "catalogs", name: "rooms", title: "Rooms", searchable: true,
    columns: [{ columnName: "label", label: "Label", width: "" }],
    sort: { column: null, descending: false }, newUrl: null, canWrite: true,
    commands: [], embedded: true, pageSize: 20, ...overrides,
  } as ListDescriptor;
  return <div data-testid={testId}><ViewNodeView node={{ type: "list", list: list as never }} /></div>;
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
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe("in-place command refresh through the query cache (#447)", () => {
  it("updates a clean form without replacing its input or touching another entity's tab", async () => {
    getRecordRefresh.mockResolvedValue({
      record: { _id: "r1", label: "Server value" },
      actions: [], commands: [{ ...advance, title: "Complete", enabled: false }], readOnly: false,
    });
    render(<>
      <Surface path={source}><Form refreshable commands={[advance]} /></Surface>
      <Surface path="/catalogs/suites/s2"><Form name="suites" id="s2" initial={{ _id: "s2", label: "Other room" }} /></Surface>
    </>);
    const input = screen.getByDisplayValue("Old value");
    const otherInput = screen.getByDisplayValue("Other room");
    input.focus();

    await refresh(["r1"]);

    await waitFor(() => expect(input).toHaveValue("Server value"));
    expect(screen.getByDisplayValue("Server value")).toBe(input);
    expect(input).toHaveFocus();
    expect(screen.getByDisplayValue("Other room")).toBe(otherInput);
    expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Complete" })).toBeDisabled();
    expect(getRecordRefresh).toHaveBeenCalledExactlyOnceWith("catalogs", "rooms", "r1");
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("keeps a dirty form while a sibling list refreshes its own data", async () => {
    getRecordRefresh.mockResolvedValue({
      record: { _id: "r1", label: "Server value" }, actions: [], readOnly: false,
    });
    render(<Surface path={source}>
      <Form refreshable commands={[advance]} />
      <List testId="list" />
    </Surface>);
    const listHost = screen.getByTestId("list");
    await within(listHost).findByText("Stored room");
    const input = screen.getByDisplayValue("Old value");
    fireEvent.change(input, { target: { value: "My draft" } });
    fetchMock.mockClear();

    await refresh(["r1"]);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/^\/api\/list\/catalogs\/rooms\?/);
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect(input).toHaveValue("My draft");
    expect(screen.getByText("This record changed elsewhere.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument());
  });

  it("keeps edits started after the record request was sent", async () => {
    render(<Surface path={source}><Form refreshable commands={[advance]} /></Surface>);
    const snapshot = { record: { _id: "r1", label: "Late server value" }, actions: [], readOnly: false };
    let finish!: (result: typeof snapshot) => void;
    getRecordRefresh.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    await refresh(["r1"]);
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
    render(<Surface path={source}><Form refreshable commands={[advance]} /></Surface>);
    const older = { record: { _id: "r1", label: "Older state" }, actions: [], commands: [advance], readOnly: false };
    let finishOlder!: (result: typeof older) => void;
    getRecordRefresh.mockImplementationOnce(() => new Promise((resolve) => { finishOlder = resolve; }))
      .mockResolvedValueOnce({ record: { _id: "r1", label: "Newer state" }, actions: [], readOnly: false });
    await refresh(["r1"]);
    await refresh(["r1"]);
    await act(async () => finishOlder(older));

    expect(screen.getByDisplayValue("Newer state")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Advance" })).not.toBeInTheDocument();
  });

  it("preserves each list's own search and sort when a collection command refreshes the entity", async () => {
    render(<>
      <Surface path="/reports?period=month"><List testId="month" sort={{ column: "label", descending: true }} /></Surface>
      <Surface path="/reports?period=year"><List testId="year" /></Surface>
    </>);
    const listHost = screen.getByTestId("month");
    const otherHost = screen.getByTestId("year");
    await within(listHost).findByText("Stored room");
    await within(otherHost).findByText("Stored room");
    const search = within(listHost).getByRole("textbox");
    fireEvent.change(search, { target: { value: "suite" } });
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) =>
      new URL(String(url), "http://localhost").searchParams.get("q") === "suite")).toBe(true));
    fetchMock.mockClear();

    await refresh();

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const queries = fetchMock.mock.calls.map(([url]) => new URL(String(url), "http://localhost").searchParams);
    const query = queries.find((q) => q.get("q") === "suite")!;
    expect(queries.find((q) => q !== query)?.get("q")).toBeNull();
    expect(query.get("sort")).toBe("label");
    expect(query.get("dir")).toBe("desc");
    expect(within(listHost).getByRole("textbox")).toBe(search);
    expect(search).toHaveValue("suite");
    expect(within(otherHost).getByRole("textbox")).toHaveValue("");
  });

  it.each(["new", "r1/duplicate"])("does not overwrite a %s form", async (id) => {
    const path = `/catalogs/rooms/${id}`;
    render(<Surface path={path}><Form {...(id === "new" ? { id: undefined } : { duplicate: true })} /></Surface>);
    const input = screen.getByDisplayValue("Old value");
    await refresh(["r1"]);
    expect(getCatalogItem).not.toHaveBeenCalled();
    expect(input).toHaveValue("Old value");
  });
});
