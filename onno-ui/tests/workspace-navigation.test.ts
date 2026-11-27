import { describe, expect, it } from "vitest";
import {
  SOFT_TAB_LIMIT,
  TAB_HISTORY_LIMIT,
  closeOtherTabs,
  enforceSoftLimit,
  followBrowserHistory,
  leaveSurface,
  navigateInTab,
  normalizeSizes,
  openInNewIsland,
  openInNewTab,
  tabBack,
  tabForPath,
  tabForward,
  tabHistory,
  type Pane,
  type Workspace,
} from "@/views/workspace-navigation";

const clean = () => false;

function workspace(...panes: string[][]): Workspace {
  return {
    panes: panes.map((paths, i) => ({ id: `p${i}`, tabs: paths.map(tabForPath), activePath: paths[paths.length - 1] ?? "" })),
    sizes: panes.map(() => 1),
    focused: "p0",
  };
}

const paths = (pane: Pane) => pane.tabs.map((t) => t.path);
const active = (ws: Workspace, i = 0) => ws.panes[i].tabs.find((t) => t.path === ws.panes[i].activePath)!;

describe("workspace navigation", () => {
  it("shows a new route in the active tab and remembers the previous one for Back", () => {
    const ws = navigateInTab(workspace(["/catalogs/customers"]), "p0", "/documents/orders", clean);
    expect(paths(ws.panes[0])).toEqual(["/documents/orders"]);
    expect(active(ws).back).toEqual(["/catalogs/customers"]);
    expect(tabHistory(ws.panes[0])).toEqual({ canBack: true, canForward: false });
  });

  it("walks Back and Forward through the tab's own history", () => {
    let ws = workspace(["/a"]);
    ws = navigateInTab(ws, "p0", "/b", clean);
    ws = navigateInTab(ws, "p0", "/c", clean);
    ws = tabBack(ws, "p0");
    expect(ws.panes[0].activePath).toBe("/b");
    expect(active(ws)).toMatchObject({ back: ["/a"], forward: ["/c"] });
    ws = tabBack(ws, "p0");
    expect(ws.panes[0].activePath).toBe("/a");
    expect(tabHistory(ws.panes[0])).toEqual({ canBack: false, canForward: true });
    expect(tabBack(ws, "p0")).toBe(ws);
    ws = tabForward(ws, "p0");
    expect(ws.panes[0].activePath).toBe("/b");
    expect(active(ws)).toMatchObject({ back: ["/a"], forward: ["/c"] });
  });

  it("drops Forward history when a new route is opened after stepping back", () => {
    let ws = navigateInTab(workspace(["/a"]), "p0", "/b", clean);
    ws = tabBack(ws, "p0");
    ws = navigateInTab(ws, "p0", "/c", clean);
    expect(active(ws)).toMatchObject({ path: "/c", back: ["/a"], forward: [] });
  });

  it("activates a surface that is already open instead of duplicating it", () => {
    const ws = navigateInTab(workspace(["/a", "/b"], ["/documents/orders/1"]), "p0", "/documents/orders/1", clean);
    expect(paths(ws.panes[0])).toEqual(["/a", "/b"]);
    expect(ws.focused).toBe("p1");
    expect(ws.panes[1].tabs).toHaveLength(1);
  });

  it("keeps a tab with unsaved edits and opens the route beside it", () => {
    const ws = navigateInTab(workspace(["/catalogs/customers/1"]), "p0", "/documents/orders", (p) => p === "/catalogs/customers/1");
    expect(paths(ws.panes[0])).toEqual(["/catalogs/customers/1", "/documents/orders"]);
    expect(ws.panes[0].activePath).toBe("/documents/orders");
  });

  it("does not remember a create form once the tab moves on to the saved record", () => {
    let ws = navigateInTab(workspace(["/documents/orders/1"]), "p0", "/documents/orders/new", clean);
    ws = navigateInTab(ws, "p0", "/documents/orders/2", clean);
    expect(active(ws)).toMatchObject({ path: "/documents/orders/2", back: ["/documents/orders/1"] });
  });

  it("returns a finished form to the surface it came from, without Forward into it", () => {
    const ws = navigateInTab(workspace(["/documents/orders/1"]), "p0", "/documents/orders/new", clean);
    const left = leaveSurface(ws, "/documents/orders/new")!;
    expect(active(left)).toMatchObject({ path: "/documents/orders/1", back: [], forward: [] });
    expect(leaveSurface(workspace(["/a"]), "/a")).toBeNull();
    expect(leaveSurface(workspace(["/a"]), "/missing")).toBeNull();
  });

  it("skips history entries that are open in another tab", () => {
    let ws = navigateInTab(workspace(["/a"]), "p0", "/b", clean);
    ws = openInNewTab(ws, "p0", "/a", clean);
    ws = { ...ws, panes: ws.panes.map((p) => ({ ...p, activePath: "/b" })) };
    expect(tabBack(ws, "p0")).toBe(ws);
  });

  it("opens an explicit new tab right after the active one", () => {
    const ws = openInNewTab({ ...workspace(["/a", "/b", "/c"]), panes: [{ ...workspace(["/a", "/b", "/c"]).panes[0], activePath: "/a" }] }, "p0", "/d", clean);
    expect(paths(ws.panes[0])).toEqual(["/a", "/d", "/b", "/c"]);
    expect(ws.panes[0].activePath).toBe("/d");
  });

  it("follows the browser's Back / Forward through the focused tab's history", () => {
    let ws = navigateInTab(workspace(["/a"]), "p0", "/b", clean);
    ws = followBrowserHistory(ws, "/a", clean);
    expect(active(ws)).toMatchObject({ path: "/a", forward: ["/b"] });
    ws = followBrowserHistory(ws, "/b", clean);
    expect(active(ws)).toMatchObject({ path: "/b", back: ["/a"], forward: [] });
    ws = followBrowserHistory(ws, "/z", clean);
    expect(active(ws)).toMatchObject({ path: "/z", back: ["/a", "/b"] });
  });

  it("never steps a dirty tab through the browser's history; the route opens beside it", () => {
    const ws = navigateInTab(workspace(["/a"]), "p0", "/documents/orders/1", clean);
    const next = followBrowserHistory(ws, "/a", (p) => p === "/documents/orders/1");
    expect(paths(next.panes[0])).toEqual(["/documents/orders/1", "/a"]);
    expect(next.panes[0].activePath).toBe("/a");
  });

  it("caps a tab's history", () => {
    let ws = workspace(["/0"]);
    for (let i = 1; i <= TAB_HISTORY_LIMIT + 5; i++) ws = navigateInTab(ws, "p0", `/${i}`, clean);
    expect(active(ws).back).toHaveLength(TAB_HISTORY_LIMIT);
    expect(active(ws).back![0]).toBe("/5");
  });

  it("closes the least recently used clean background tab past the soft limit", () => {
    let ws = workspace(["/t0"]);
    for (let i = 1; i <= SOFT_TAB_LIMIT; i++) ws = openInNewTab(ws, "p0", `/t${i}`, (p) => p === "/t0");
    // /t0 is the oldest but holds unsaved edits, so /t1 goes instead.
    expect(ws.panes[0].tabs).toHaveLength(SOFT_TAB_LIMIT);
    expect(paths(ws.panes[0])).toContain("/t0");
    expect(paths(ws.panes[0])).not.toContain("/t1");
    expect(ws.panes[0].activePath).toBe(`/t${SOFT_TAB_LIMIT}`);
  });

  it("never closes the active tab or a dirty tab, even above the limit", () => {
    const pane = workspace(["/a", "/b", "/c"]).panes[0];
    expect(enforceSoftLimit(pane, (p) => p !== "/c", 1)).toBe(pane);
    expect(paths(enforceSoftLimit(pane, (p) => p === "/a", 1))).toEqual(["/a", "/c"]);
  });

  it("closes the other tabs of an island but keeps unsaved ones", () => {
    const ws = closeOtherTabs(workspace(["/a", "/b", "/c"]), "p0", "/b", (p) => p === "/c");
    expect(paths(ws.panes[0])).toEqual(["/b", "/c"]);
    expect(ws.panes[0].activePath).toBe("/b");
  });

  it("opens a new island right of the given one, taking half of its width", () => {
    const ws = openInNewIsland(workspace(["/a"], ["/documents/orders/1"]), "p0", "/b", "p9");
    expect(ws.panes.map((p) => p.id)).toEqual(["p0", "p9", "p1"]);
    expect(paths(ws.panes[1])).toEqual(["/b"]);
    expect(ws.focused).toBe("p9");
    expect(ws.sizes[0]).toBeCloseTo(ws.sizes[1]);
    expect(ws.sizes[2]).toBeCloseTo(ws.sizes[0] * 2);
    expect(ws.sizes.reduce((a, b) => a + b, 0)).toBeCloseTo(3);
  });

  it("activates an open surface, and fills an empty island, instead of splitting", () => {
    const open = openInNewIsland(workspace(["/a"], ["/b"]), "p0", "/b", "p9");
    expect(open.panes).toHaveLength(2);
    expect(open.focused).toBe("p1");
    const filled = openInNewIsland(workspace([]), "p0", "/a", "p9");
    expect(filled.panes).toHaveLength(1);
    expect(filled.panes[0]).toMatchObject({ id: "p0", activePath: "/a" });
  });

  it("keeps island weights summing to the island count", () => {
    expect(normalizeSizes([0.5])).toEqual([1]);
    expect(normalizeSizes([1, 3])).toEqual([0.5, 1.5]);
  });
});
