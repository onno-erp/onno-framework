/**
 * The desktop workspace model and its navigation rules, kept free of React so every transition is a
 * plain function over an immutable {@link Workspace}.
 *
 * Navigation works like a browser window: an ordinary open replaces what the island's active tab
 * shows and remembers the previous surface in that tab's own history, so Back / Forward walk it.
 * A new tab appears only when one is asked for explicitly ({@link openInNewTab}, or a whole new
 * island with {@link openInNewIsland}), when the surface
 * is already open elsewhere (that tab is activated instead of duplicating it), or when the active
 * tab holds unsaved edits (they are never discarded by a navigation). An island holding more than
 * {@link SOFT_TAB_LIMIT} tabs quietly closes its least recently used clean background tab.
 */

/** One open surface of an island, with that tab's own Back / Forward history. */
export type WorkspaceTab = {
  path: string;
  title: string;
  /** Surfaces this tab showed before, oldest first; the last one is where Back goes. */
  back?: string[];
  /** Surfaces Back stepped away from, nearest first; the first one is where Forward goes. */
  forward?: string[];
  /** Monotonic use stamp: the least recently used clean background tab is closed past the soft limit. */
  touched?: number;
};

// An island: an ordered set of open tabs plus the one currently shown. Several live
// side by side, each sized by a flex weight in {@link Workspace.sizes}.
export type Pane = { id: string; tabs: WorkspaceTab[]; activePath: string };

// The desktop content area: islands left-to-right, their flex weights, and which one
// has focus (the island the URL/navigation drives).
export type Workspace = { panes: Pane[]; sizes: number[]; focused: string | null };

/** Entries a tab keeps in each direction of its history; older entries are dropped. */
export const TAB_HISTORY_LIMIT = 50;

/**
 * Tabs an island keeps before it closes the least recently used clean background tab. Deliberately
 * high: with ordinary navigation reusing the active tab, only explicitly opened tabs count against it.
 */
export const SOFT_TAB_LIMIT = 15;

/** Asks whether the form at a tab path holds unsaved edits. */
export type IsDirty = (path: string) => boolean;

let useSeq = 0;
function nextUse(): number {
  useSeq += 1;
  return useSeq;
}

// Percent-decode a single route segment for display. Route segments can be
// percent-encoded (a non-ASCII or spaced entity name leaks into the URL as
// e.g. "%D0%97..."), and a tab title built from the raw segment would show that
// encoded form. Falls back to the raw segment if it isn't valid encoding.
export function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

export function humanizeRouteToken(token: string): string {
  return decodeSegment(token)
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

// A path may carry a query (a prefilled New form: …/new?startsAt=…). Keep the full string as the
// stored tab path (the surface fetch needs the query), but read route segments from the query-less
// form so verb detection and title/identity aren't thrown off by the "?…" tail.
export function stripQuery(pathname: string): string {
  return pathname.split("?")[0];
}

export function tabForPath(pathname: string): WorkspaceTab {
  const path = pathname || "/";
  if (stripQuery(path) === "/") return { path, title: "Dashboard", touched: nextUse() };

  const [kind, name, detail, action] = stripQuery(path).split("/").filter(Boolean);
  const entity = name ? humanizeRouteToken(name) : humanizeRouteToken(kind ?? "Page");

  const touched = nextUse();
  if (detail === "new") return { path, title: `New ${entity}`, touched };
  if (action === "duplicate") return { path, title: `Duplicate ${entity}`, touched };
  if (detail) return { path, title: `${entity} ${decodeSegment(detail).slice(0, 8)}`, touched };
  return { path, title: entity, touched };
}

// A record surface opened from a list — a document/catalog record or a "new" form (3 segments).
// These open in their own island beside the list, master-detail style.
export function isRecordDetail(pathname: string): boolean {
  const seg = stripQuery(pathname).split("/").filter(Boolean);
  return seg.length === 3 && (seg[0] === "documents" || seg[0] === "catalogs");
}

export function recordBasePath(pathname: string): string | null {
  return isRecordDetail(pathname) ? stripQuery(pathname) : null;
}

export function sameRecordTab(a: string, b: string): boolean {
  const ar = recordBasePath(a);
  const br = recordBasePath(b);
  return ar != null && ar === br;
}

// The same surface: the same path, or the same record route with a different query.
function sameSurface(a: string, b: string): boolean {
  return a === b || sameRecordTab(a, b);
}

// A create/duplicate form is a step, not a destination: once it is saved or left for the record
// it created, Back should not return to an empty form.
function isTransientForm(path: string): boolean {
  const seg = stripQuery(path).split("/").filter(Boolean);
  return (seg[0] === "documents" || seg[0] === "catalogs")
    && (seg[2] === "new" || seg[3] === "duplicate");
}

function capBack(entries: string[]): string[] {
  return entries.length > TAB_HISTORY_LIMIT ? entries.slice(entries.length - TAB_HISTORY_LIMIT) : entries;
}

function capForward(entries: string[]): string[] {
  return entries.length > TAB_HISTORY_LIMIT ? entries.slice(0, TAB_HISTORY_LIMIT) : entries;
}

/** The tab an island currently shows. */
export function activeTab(pane: Pane | undefined): WorkspaceTab | undefined {
  return pane?.tabs.find((tab) => tab.path === pane.activePath);
}

/** Whether the island's active tab can step Back / Forward. */
export function tabHistory(pane: Pane | undefined): { canBack: boolean; canForward: boolean } {
  const tab = activeTab(pane);
  return { canBack: (tab?.back?.length ?? 0) > 0, canForward: (tab?.forward?.length ?? 0) > 0 };
}

function focusedPane(ws: Workspace): Pane | undefined {
  return ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
}

function replacePane(ws: Workspace, paneId: string, update: (pane: Pane) => Pane, focus = true): Workspace {
  return {
    ...ws,
    focused: focus ? paneId : ws.focused,
    panes: ws.panes.map((p) => (p.id === paneId ? update(p) : p)),
  };
}

// The tab already showing this surface, wherever it lives.
function findSurface(ws: Workspace, path: string): { pane: Pane; tab: WorkspaceTab } | undefined {
  for (const pane of ws.panes) {
    const tab = pane.tabs.find((t) => sameSurface(t.path, path));
    if (tab) return { pane, tab };
  }
  return undefined;
}

// Show an existing tab; a record reopened with another query takes the new path in place.
function activateExisting(ws: Workspace, paneId: string, tab: WorkspaceTab, path: string): Workspace {
  return replacePane(ws, paneId, (pane) => ({
    ...pane,
    tabs: pane.tabs.map((t) => (t === tab ? { ...t, path, touched: nextUse() } : t)),
    activePath: path,
  }));
}

/**
 * Close the least recently used clean background tabs of an island until it holds at most
 * {@link SOFT_TAB_LIMIT} tabs. The active tab and tabs with unsaved edits are never closed; if only
 * those remain, the island simply stays above the limit.
 */
export function enforceSoftLimit(pane: Pane, isDirty: IsDirty, limit = SOFT_TAB_LIMIT): Pane {
  let tabs = pane.tabs;
  while (tabs.length > limit) {
    const candidates = tabs.filter((t) => t.path !== pane.activePath && !isDirty(t.path));
    if (candidates.length === 0) break;
    const oldest = candidates.reduce((a, b) => ((a.touched ?? 0) <= (b.touched ?? 0) ? a : b));
    tabs = tabs.filter((t) => t !== oldest);
  }
  return tabs === pane.tabs ? pane : { ...pane, tabs };
}

/**
 * Open `path` as a new tab of the island, right after its active tab, and show it. A surface that
 * is already open anywhere is activated instead of being duplicated.
 */
export function openInNewTab(ws: Workspace, paneId: string, path: string, isDirty: IsDirty): Workspace {
  const existing = findSurface(ws, path);
  if (existing) return activateExisting(ws, existing.pane.id, existing.tab, path);
  return replacePane(ws, paneId, (pane) => {
    const at = pane.tabs.findIndex((t) => t.path === pane.activePath);
    const tabs = [...pane.tabs];
    tabs.splice(at < 0 ? tabs.length : at + 1, 0, tabForPath(path));
    return enforceSoftLimit({ ...pane, tabs, activePath: path }, isDirty);
  });
}

/**
 * Scale flex weights so they sum to the island count (mean 1 each). Flexbox only distributes the
 * fraction of free space equal to the sum of grow factors when that sum is below 1, so the weights
 * are renormalised on every structural change and the islands always fill the row.
 */
export function normalizeSizes(sizes: number[]): number[] {
  const sum = sizes.reduce((a, b) => a + b, 0) || 1;
  const k = sizes.length / sum;
  return sizes.map((s) => s * k);
}

/**
 * Open `path` in a new island right of the island `besidePaneId`, which gives up half of its width
 * (a browser's "open in new window", kept inside the workspace). A surface that is already open
 * anywhere is activated instead of being duplicated. `newPaneId` names the island when one is made.
 */
export function openInNewIsland(ws: Workspace, besidePaneId: string, path: string, newPaneId: string): Workspace {
  const existing = findSurface(ws, path);
  if (existing) return activateExisting(ws, existing.pane.id, existing.tab, path);
  const found = ws.panes.findIndex((p) => p.id === besidePaneId);
  const at = found < 0 ? ws.panes.length - 1 : found;
  const neighbour = ws.panes[at];
  // An island with nothing in it is filled rather than split.
  if (neighbour && neighbour.tabs.length === 0) {
    return replacePane(ws, neighbour.id, (pane) => ({ ...pane, tabs: [tabForPath(path)], activePath: path }));
  }
  const panes = [
    ...ws.panes.slice(0, at + 1),
    { id: newPaneId, tabs: [tabForPath(path)], activePath: path },
    ...ws.panes.slice(at + 1),
  ];
  const sizes = [...ws.sizes];
  const share = (sizes[at] ?? 1) / 2;
  sizes[at] = share;
  sizes.splice(at + 1, 0, share);
  return { panes, sizes: normalizeSizes(sizes), focused: newPaneId };
}

/**
 * Navigate the island to `path` the way a browser window does: its active tab shows the new surface
 * and remembers the previous one for Back (Forward history is dropped). An island without tabs gets
 * one. A surface already open in some tab is activated there; an active tab with unsaved edits is
 * kept and the surface opens in a new tab beside it.
 */
export function navigateInTab(ws: Workspace, paneId: string, path: string, isDirty: IsDirty): Workspace {
  const existing = findSurface(ws, path);
  if (existing) return activateExisting(ws, existing.pane.id, existing.tab, path);
  const pane = ws.panes.find((p) => p.id === paneId);
  if (!pane) return ws;
  const current = activeTab(pane);
  if (!current) return openInNewTab(ws, paneId, path, isDirty);
  if (isDirty(current.path)) return openInNewTab(ws, paneId, path, isDirty);
  const back = isTransientForm(current.path) ? current.back ?? [] : capBack([...(current.back ?? []), current.path]);
  const tab: WorkspaceTab = { ...tabForPath(path), back, forward: [] };
  return replacePane(ws, paneId, (p) => ({
    ...p,
    tabs: p.tabs.map((t) => (t === current ? tab : t)),
    activePath: path,
  }));
}

// History entries that are open in another tab cannot be restored without duplicating that tab;
// skip them (they are reachable through their own tab).
function nextRestorable(ws: Workspace, entries: string[]): number {
  return entries.findIndex((entry) => !findSurface(ws, entry));
}

/** Step the island's active tab one surface back. Unchanged when there is nowhere to go. */
export function tabBack(ws: Workspace, paneId: string): Workspace {
  const pane = ws.panes.find((p) => p.id === paneId);
  const current = activeTab(pane);
  if (!pane || !current?.back?.length) return ws;
  const reversed = [...current.back].reverse();
  const i = nextRestorable(ws, reversed);
  if (i < 0) return ws;
  const target = reversed[i];
  const back = reversed.slice(i + 1).reverse();
  const forward = capForward([current.path, ...(current.forward ?? [])]);
  const tab: WorkspaceTab = { ...tabForPath(target), back, forward };
  return replacePane(ws, paneId, (p) => ({
    ...p,
    tabs: p.tabs.map((t) => (t === current ? tab : t)),
    activePath: target,
  }));
}

/** Step the island's active tab one surface forward. Unchanged when there is nowhere to go. */
export function tabForward(ws: Workspace, paneId: string): Workspace {
  const pane = ws.panes.find((p) => p.id === paneId);
  const current = activeTab(pane);
  if (!pane || !current?.forward?.length) return ws;
  const i = nextRestorable(ws, current.forward);
  if (i < 0) return ws;
  const target = current.forward[i];
  const forward = current.forward.slice(i + 1);
  const back = capBack([...(current.back ?? []), current.path]);
  const tab: WorkspaceTab = { ...tabForPath(target), back, forward };
  return replacePane(ws, paneId, (p) => ({
    ...p,
    tabs: p.tabs.map((t) => (t === current ? tab : t)),
    activePath: target,
  }));
}

/**
 * Leave a surface that finished its job (a form after save or cancel, a deleted record): its tab
 * returns to the surface it came from without offering Forward back into it. A tab with no history
 * is closed. Returns `null` when no tab shows `path`.
 */
export function leaveSurface(ws: Workspace, path: string): Workspace | null {
  const holder = ws.panes.find((p) => p.tabs.some((t) => t.path === path));
  if (!holder) return null;
  const tab = holder.tabs.find((t) => t.path === path)!;
  const reversed = [...(tab.back ?? [])].reverse();
  const i = nextRestorable(ws, reversed);
  if (i < 0) return null;
  const target = reversed[i];
  const back = reversed.slice(i + 1).reverse();
  const restored: WorkspaceTab = { ...tabForPath(target), back, forward: tab.forward ?? [] };
  return replacePane(ws, holder.id, (p) => ({
    ...p,
    tabs: p.tabs.map((t) => (t === tab ? restored : t)),
    activePath: p.activePath === path ? target : p.activePath,
  }), ws.focused === holder.id);
}

/**
 * The browser moved through its own history (its Back / Forward buttons) to `path`. When that is
 * the focused tab's previous or next surface, step its history; otherwise treat it as a navigation.
 */
export function followBrowserHistory(ws: Workspace, path: string, isDirty: IsDirty): Workspace {
  const pane = focusedPane(ws);
  if (!pane) return ws;
  const current = activeTab(pane);
  if (current?.path === path) return ws;
  // Unsaved edits are never stepped away from: the route opens beside them instead.
  if (current && isDirty(current.path)) return openInNewTab(ws, pane.id, path, isDirty);
  if (current?.back?.length && current.back[current.back.length - 1] === path) return tabBack(ws, pane.id);
  if (current?.forward?.length && current.forward[0] === path) return tabForward(ws, pane.id);
  return navigateInTab(ws, pane.id, path, isDirty);
}

/** Show an open tab of the island and focus the island (a click on the tab). */
export function touchTab(ws: Workspace, paneId: string, path: string): Workspace {
  return replacePane(ws, paneId, (pane) => ({
    ...pane,
    tabs: pane.tabs.map((t) => (t.path === path ? { ...t, touched: nextUse() } : t)),
    activePath: path,
  }));
}

/** Close every tab of the island except `keep`, sparing tabs with unsaved edits. */
export function closeOtherTabs(ws: Workspace, paneId: string, keep: string, isDirty: IsDirty): Workspace {
  return replacePane(ws, paneId, (pane) => ({
    ...pane,
    tabs: pane.tabs.filter((t) => t.path === keep || isDirty(t.path)),
    activePath: keep,
  }));
}
