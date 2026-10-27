import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { RefPickerDialog, type RefPickerDialogProps } from "@/components/ref-picker-dialog";

vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("maplibre-gl", () => ({ default: {} }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }

const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });

type Row = { id: string; description: string; folder?: boolean; parent?: string | null };
const TOOLS: Row = { id: "f-tools", description: "Tools", folder: true, parent: null };
const PAINT: Row = { id: "f-paint", description: "Paint", folder: true, parent: null };
const HAMMER: Row = { id: "i-hammer", description: "Hammer", parent: "f-tools" };
const SAW: Row = { id: "i-saw", description: "Saw", parent: "f-tools" };
const LOOSE: Row = { id: "i-loose", description: "Loose", parent: null };
const ALL = [TOOLS, PAINT, HAMMER, SAW, LOOSE];

function descriptor(hierarchical: boolean) {
  return {
    kind: "catalogs", name: "Items", title: "Items", columns: [{ columnName: "description", label: "Name", width: "" }],
    searchable: true, sort: { column: null, descending: false }, newUrl: null, canWrite: false, actions: [], inputs: [],
    filters: [], pageSize: 50, hierarchical,
  };
}

/** A fake server: picker descriptor, the list feed (understands the clauses the dialog sends), decorate. */
function server(opts: { hierarchical?: boolean; decorate?: (ids: string[]) => unknown } = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname.startsWith("/api/divkit/pickers/")) return json(descriptor(!!opts.hierarchical));
    if (url.pathname === "/api/ref-options/decorate") {
      const body = JSON.parse(String(init?.body)) as { ids: string[] };
      return json(opts.decorate ? opts.decorate(body.ids) : { offered: body.ids, decorations: {} });
    }
    if (url.pathname === "/api/list/catalogs/Items") {
      const ids = url.searchParams.get("ids");
      if (ids) return json({ rows: ALL.filter((r) => r.id === ids), hasMore: false, nextCursor: null });
      const filter = url.searchParams.get("filter") ?? "";
      const q = url.searchParams.get("q");
      let rows = ALL;
      const parent = /_parent = (\S+)/.exec(filter)?.[1];
      if (parent) rows = rows.filter((r) => (r.parent ?? "null") === parent);
      if (filter.includes("_is_folder = true")) rows = rows.filter((r) => r.folder);
      if (q) rows = rows.filter((r) => r.description.toLowerCase().includes(q.toLowerCase()));
      return json({ rows, hasMore: false, nextCursor: null });
    }
    return json({});
  });
}

const feedCalls = (fetchMock: ReturnType<typeof vi.fn>) =>
  fetchMock.mock.calls.map((c) => new URL(String(c[0]), "http://localhost"))
    .filter((u) => u.pathname === "/api/list/catalogs/Items" && !u.searchParams.get("ids")
      // the folder tree's own requests are not the grid's
      && !(u.searchParams.get("filter") ?? "").includes("_is_folder"));

function open(props: Partial<RefPickerDialogProps> = {}) {
  const onPick = vi.fn();
  const onClose = vi.fn();
  render(<RefPickerDialog open onClose={onClose} onPick={onPick} fieldLabel="Item" targetKind="catalog"
    targetName="Items" {...props} />);
  return { onPick, onClose };
}

const pickRow = (text: string) => {
  const grid = document.querySelector("[data-onno-picker]") as HTMLElement;
  return within(grid).getAllByText(text).map((el) => el.closest("[data-onno-pick-row]")).find(Boolean) as HTMLElement;
};

describe("reference picker dialog (#411)", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("lists the target under the field's fixed refFilter and picks on double click", async () => {
    const fetchMock = server();
    vi.stubGlobal("fetch", fetchMock);
    const { onPick, onClose } = open({ filter: "region = north" });

    await waitFor(() => expect(pickRow("Hammer")).toBeTruthy());
    expect(screen.getByRole("heading")).toHaveTextContent("Select: Item");
    expect(screen.getByText("The list is limited by this field's conditions")).toBeInTheDocument();
    expect(feedCalls(fetchMock).every((u) => u.searchParams.get("filter") === "region = north")).toBe(true);
    expect(screen.getByLabelText("Search")).toHaveFocus();

    fireEvent.doubleClick(pickRow("Saw"));
    expect(onPick).toHaveBeenCalledWith("i-saw", expect.objectContaining({ id: "i-saw" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("enables Select only for a highlighted row and picks it", async () => {
    vi.stubGlobal("fetch", server());
    const { onPick } = open();
    await waitFor(() => expect(pickRow("Hammer")).toBeTruthy());
    const select = screen.getByRole("button", { name: "Select" });
    expect(select).toBeDisabled();

    fireEvent.click(pickRow("Hammer"));
    expect(select).toBeEnabled();
    fireEvent.click(select);
    expect(onPick).toHaveBeenCalledWith("i-hammer", expect.anything());
  });

  it("navigates folders with a double click and the breadcrumbs, and searches across them", async () => {
    const fetchMock = server({ hierarchical: true });
    vi.stubGlobal("fetch", fetchMock);
    const { onPick } = open({ filter: "active = true" });

    await waitFor(() => expect(pickRow("Tools")).toBeTruthy());
    expect(screen.queryByText("Hammer")).toBeNull();
    expect(feedCalls(fetchMock).at(-1)?.searchParams.get("filter")).toBe("active = true AND _parent = null");

    fireEvent.doubleClick(pickRow("Tools")); // a folder opens, it is not picked
    await waitFor(() => expect(pickRow("Hammer")).toBeTruthy());
    expect(onPick).not.toHaveBeenCalled();
    const crumbs = screen.getByRole("navigation", { name: "Folders" });
    expect(within(crumbs).getByText("Tools")).toHaveAttribute("aria-current", "location");

    fireEvent.click(within(crumbs).getByText("Items"));
    await waitFor(() => expect(pickRow("Loose")).toBeTruthy());

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "saw" } });
    await waitFor(() => expect(pickRow("Saw")).toBeTruthy());
    const searched = feedCalls(fetchMock).at(-1)!;
    expect(searched.searchParams.get("filter")).toBe("active = true");
    expect(searched.searchParams.get("q")).toBe("saw");
  });

  it("shows a folder tree whose nodes open their folder", async () => {
    vi.stubGlobal("fetch", server({ hierarchical: true }));
    open();
    const tree = await screen.findByRole("tree");
    const toolsNode = await within(tree).findByText("Tools");
    fireEvent.click(toolsNode);
    await waitFor(() => expect(pickRow("Hammer")).toBeTruthy());
  });

  it("opens in the current value's folder with that value highlighted and selectable", async () => {
    const fetchMock = server({ hierarchical: true });
    vi.stubGlobal("fetch", fetchMock);
    open({ value: "i-saw" });

    await waitFor(() => expect(pickRow("Saw")).toHaveAttribute("data-onno-selected", "true"));
    expect(feedCalls(fetchMock).at(-1)?.searchParams.get("focus")).toBe("i-saw");
    expect(feedCalls(fetchMock).at(-1)?.searchParams.get("filter")).toBe("_parent = f-tools");
    expect(screen.getByRole("button", { name: "Select" })).toBeEnabled();
  });

  it("hides what the field's decorator filters out and keeps disabled rows unselectable", async () => {
    vi.stubGlobal("fetch", server({
      decorate: (ids) => ({
        offered: ids.filter((id) => id !== "i-saw"),
        decorations: { "i-hammer": { disabled: true, badge: "Busy", reason: "Booked", tone: "danger" } },
      }),
    }));
    const { onPick } = open({ decorator: "app.Availability", optionContext: { fieldPath: "item", formValues: {} } });

    await waitFor(() => expect(screen.getByText("Busy")).toBeInTheDocument());
    expect(screen.queryByText("Saw")).toBeNull();
    fireEvent.doubleClick(pickRow("Hammer"));
    expect(onPick).not.toHaveBeenCalled();
    fireEvent.click(pickRow("Hammer"));
    expect(screen.getByRole("button", { name: "Select" })).toBeDisabled();
  });

  it("keeps values picked in sibling rows visible but unselectable", async () => {
    vi.stubGlobal("fetch", server());
    const { onPick } = open({ excludedIds: ["i-loose"] });
    await waitFor(() => expect(pickRow("Loose")).toHaveAttribute("aria-disabled", "true"));
    expect(screen.getByText("Already selected")).toBeInTheDocument();
    fireEvent.doubleClick(pickRow("Loose"));
    expect(onPick).not.toHaveBeenCalled();
  });

  it("carries the dropdown's query into its search and closes on Cancel", async () => {
    const fetchMock = server();
    vi.stubGlobal("fetch", fetchMock);
    const { onClose } = open({ initialQuery: "ham" });
    expect(screen.getByLabelText("Search")).toHaveValue("ham");
    await waitFor(() => expect(feedCalls(fetchMock).some((u) => u.searchParams.get("q") === "ham")).toBe(true));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalled();
  });
});
