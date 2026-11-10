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
const HAND: Row = { id: "f-hand", description: "Hand", folder: true, parent: "f-tools" };
const PAINT: Row = { id: "f-paint", description: "Paint", folder: true, parent: null };
const HAMMER: Row = { id: "i-hammer", description: "Hammer", parent: "f-tools" };
const SAW: Row = { id: "i-saw", description: "Saw", parent: "f-tools" };
const LOOSE: Row = { id: "i-loose", description: "Loose", parent: null };
const ALL = [TOOLS, HAND, PAINT, HAMMER, SAW, LOOSE];

/** The server's pruned path tree for `ids` (as `/tree?ids=` answers). */
function pathTree(ids: string[]) {
  const keep = new Set<string>();
  for (const id of ids) {
    for (let r = ALL.find((x) => x.id === id); r; r = ALL.find((x) => x.id === r!.parent)) keep.add(r.id);
  }
  const build = (parent: string | null): unknown[] => ALL
    .filter((r) => keep.has(r.id) && (r.parent ?? null) === parent)
    .map((r) => ({ ...r, children: build(r.id) }));
  return build(null);
}

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
    if (url.pathname === "/api/catalogs/Items/children" && url.searchParams.get("folders") === "true") {
      const parent = url.searchParams.get("parent");
      return json(ALL.filter((r) => r.folder && (r.parent ?? null) === parent)
        .map((r) => ({ ...r, hasChildFolders: ALL.some((c) => c.folder && c.parent === r.id) })));
    }
    if (url.pathname === "/api/catalogs/Items/tree") {
      return json(pathTree((url.searchParams.get("ids") ?? "").split(",").filter(Boolean)));
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

const calls = (fetchMock: ReturnType<typeof vi.fn>) =>
  fetchMock.mock.calls.map((c) => new URL(String(c[0]), "http://localhost"));
const feedCalls = (fetchMock: ReturnType<typeof vi.fn>) =>
  calls(fetchMock).filter((u) => u.pathname === "/api/list/catalogs/Items" && !u.searchParams.get("ids"));

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
    // the folder's own highlight does not survive the navigation
    expect(screen.getByRole("button", { name: "Select" })).toBeDisabled();
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

  it("draws an expand arrow only on folders that hold sub-folders", async () => {
    vi.stubGlobal("fetch", server({ hierarchical: true }));
    open();
    const tree = await screen.findByRole("tree");
    await within(tree).findByText("Tools");
    const node = (name: string) => within(tree).getByText(name).closest("li") as HTMLElement;
    expect(node("Tools").querySelector("[data-onno-tree-toggle]")).not.toBeNull();
    expect(node("Paint").querySelector("[data-onno-tree-toggle]")).toBeNull();
    expect(node("Paint")).not.toHaveAttribute("aria-expanded");
  });

  it("shows where each hit lives while a search spans every folder", async () => {
    const fetchMock = server({ hierarchical: true });
    vi.stubGlobal("fetch", fetchMock);
    open();
    await waitFor(() => expect(pickRow("Tools")).toBeTruthy());
    expect(screen.queryByText("Location")).toBeNull();

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "a" } });
    await waitFor(() => expect(screen.getByText("Location")).toBeInTheDocument());
    await waitFor(() => expect(within(pickRow("Hammer")).getByText("Tools")).toBeInTheDocument());
    // one path request for the whole window, not one per row
    const treeCalls = calls(fetchMock).filter((u) => u.pathname === "/api/catalogs/Items/tree");
    expect(treeCalls).toHaveLength(1);
    expect(treeCalls[0].searchParams.get("ids")?.split(",")).toEqual(expect.arrayContaining(["i-hammer", "i-saw"]));
  });

  it("keeps the list inset like the search box, never flush with the dialog edge", async () => {
    vi.stubGlobal("fetch", server());
    open();
    await waitFor(() => expect(pickRow("Hammer")).toBeTruthy());
    expect(document.querySelector("[data-onno-picker-list]")).toHaveClass("px-5");
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
    // the whole path came from one request, not one lookup per level
    expect(calls(fetchMock).filter((u) => u.pathname === "/api/catalogs/Items/tree")).toHaveLength(1);
    expect(calls(fetchMock).some((u) => u.searchParams.get("ids") && u.pathname.startsWith("/api/list/"))).toBe(false);
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

  it("lets a press outside close an open popup first, then the picker", async () => {
    vi.stubGlobal("fetch", server());
    const { onClose } = open();
    await waitFor(() => expect(pickRow("Hammer")).toBeTruthy());
    const portal = screen.getByRole("dialog").parentElement!.querySelector(":scope > .contents")!;
    const popup = document.createElement("div");
    popup.setAttribute("data-state", "open");
    portal.appendChild(popup);
    const pressOutside = () => {
      fireEvent.pointerDown(document.body, { button: 0, pointerType: "mouse" });
      fireEvent.mouseDown(document.body, { button: 0 });
      fireEvent.pointerUp(document.body, { button: 0, pointerType: "mouse" });
      fireEvent.mouseUp(document.body, { button: 0 });
      fireEvent.click(document.body, { button: 0 });
    };

    pressOutside();
    expect(onClose).not.toHaveBeenCalled();

    popup.remove();
    pressOutside();
    expect(onClose).toHaveBeenCalled();
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
