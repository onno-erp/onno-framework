import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { DivKit, type DivKitProps } from "@divkitframework/react";
import { X } from "lucide-react";
import {
  createGlobalVariablesController,
  createVariable,
} from "@divkitframework/divkit/client-hydratable";
import { useAuth } from "@/providers/auth-provider";
import { useTheme } from "@/providers/theme-provider";
import { api } from "@/lib/api";
import { useUiEvents } from "@/hooks/use-ui-events";
import type { UiEvent } from "@/lib/types";
import { cn } from "@/lib/utils";
import { ContentPane, type LiveRegistry } from "@/views/content-pane";
import "@divkitframework/divkit/dist/client.css";

/**
 * The authenticated app, rendered as DivKit cards: the chrome (/shell — top bar +
 * nav) paints instantly via the React wrapper, and the per-route content renders
 * through core DivKit instances ({@link ContentPane}) so server pushes apply in
 * place. On desktop the content area is a Rider-style set of islands: each island
 * carries its own tab strip, tabs can be dragged onto another island or onto an
 * island's right edge to split into a new island, and the dividers between islands
 * resize them. The client only routes: onec:// action URLs become navigation,
 * persona switches, theme toggle, and sign-out. A live SSE channel (/api/events)
 * refetches whichever islands show the changed surface.
 */
// Phone-portrait gets the full-width bottom bar; large/landscape phones and tablets
// share the "tablet" class (a compact bottom bar pinned bottom-right); desktop gets
// the sidebar + islands.
const MOBILE_MAX = 600;
const TABLET_MAX = 1024;

type Viewport = "mobile" | "tablet" | "desktop";

// The device class reported to the server, which serves a matching Layout/Page.
function viewportFor(width: number): Viewport {
  if (width < MOBILE_MAX) return "mobile";
  if (width < TABLET_MAX) return "tablet";
  return "desktop";
}

// One DivKit variable, shared across the nav cards, holds the current route. Nav
// item colors bind to it server-side (ShellLayoutBuilder.ACTIVE_VAR), so the active
// highlight follows navigation by updating this variable — no shell re-fetch.
const navVars = createGlobalVariablesController();
const activePathVar = createVariable("active_path", "string", window.location.pathname);
navVars.setVariable(activePathVar);

type NavStyle = "topbar" | "sidebar" | "bottom_bar";

type ShellData = {
  navStyle: NavStyle;
  nav: DivKitProps["json"];
  account: DivKitProps["json"];
};

type WorkspaceTab = { path: string; title: string };

// An island: an ordered set of open tabs plus the one currently shown. Several live
// side by side, each sized by a flex weight in {@link Workspace.sizes}.
type Pane = { id: string; tabs: WorkspaceTab[]; activePath: string };

// The desktop content area: islands left-to-right, their flex weights, and which one
// has focus (the island the URL/navigation drives).
type Workspace = { panes: Pane[]; sizes: number[]; focused: string };

const shellCache = new Map<string, ShellData>();

let paneSeq = 0;
function newPaneId(): string {
  paneSeq += 1;
  return `pane-${paneSeq}`;
}

function humanizeRouteToken(token: string): string {
  return token
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function tabForPath(pathname: string): WorkspaceTab {
  const path = pathname || "/";
  if (path === "/") return { path, title: "Dashboard" };

  const [kind, name, detail, action] = path.split("/").filter(Boolean);
  const entity = name ? humanizeRouteToken(name) : humanizeRouteToken(kind ?? "Page");

  if (detail === "new") return { path, title: `New ${entity}` };
  if (action === "edit") return { path, title: `Edit ${entity}` };
  if (detail) return { path, title: `${entity} ${detail.slice(0, 8)}` };
  return { path, title: entity };
}

function initialWorkspace(): Workspace {
  const path = window.location.pathname;
  const id = newPaneId();
  return { panes: [{ id, tabs: [tabForPath(path)], activePath: path }], sizes: [1], focused: id };
}

// Scale flex weights so they sum to the island count (mean 1 each). Flexbox only
// distributes the *fraction* of free space equal to the sum of grow factors when that
// sum is < 1 — so a lone survivor weighted 0.5 would fill only half. Renormalising on
// every structural change keeps the sum >= 1, so the islands always fill the row.
function normalize(sizes: number[]): number[] {
  const sum = sizes.reduce((a, b) => a + b, 0) || 1;
  const k = sizes.length / sum;
  return sizes.map((s) => s * k);
}

// Drop the tab from whichever island holds it. If that empties the island, remove it
// (and its size weight), keeping at least one island alive. Returns the trimmed
// workspace; the caller re-points focus afterwards.
function detachTab(ws: Workspace, path: string): Workspace {
  const panes: Pane[] = [];
  const sizes: number[] = [];
  ws.panes.forEach((pane, i) => {
    if (!pane.tabs.some((t) => t.path === path)) {
      panes.push(pane);
      sizes.push(ws.sizes[i]);
      return;
    }
    const tabs = pane.tabs.filter((t) => t.path !== path);
    if (tabs.length === 0) return; // drop the island entirely
    const activePath =
      pane.activePath === path ? (tabs[tabs.length - 1]?.path ?? tabs[0].path) : pane.activePath;
    panes.push({ ...pane, tabs, activePath });
    sizes.push(ws.sizes[i]);
  });
  if (panes.length === 0) {
    // Was the only island and it would be empty — keep it on the dashboard.
    return initialWorkspace();
  }
  const focused = panes.some((p) => p.id === ws.focused) ? ws.focused : panes[panes.length - 1].id;
  return { panes, sizes: normalize(sizes), focused };
}

// Mirror of UiLayoutResolver.toSnakeCase: nav routes use the snake-cased logical
// name (e.g. "Bank Accounts" -> "bank_accounts"), so we match SSE events the same way.
function toSnake(name: string): string {
  const s = name.replace(/ /g, "");
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c >= "A" && c <= "Z" && i > 0) out += "_";
    out += c.toLowerCase();
  }
  return out;
}

// Does a server event touch a given surface (the path an island is showing)?
function affectsSurface(event: UiEvent, pathname: string): boolean {
  if (!event || event.type === "ready") return false;
  // Home shows counts/charts over many entities — refresh on any data change.
  if (pathname === "/" || pathname === "") return true;

  const seg = pathname.split("/").filter(Boolean); // ["documents","bills",...]
  const kind = seg[0]; // catalogs | documents | registers
  const name = seg[1];
  if (!kind || !name) return false;
  const ename = event.entityName ?? "";

  if (kind === "registers") {
    // Posting emits ("changed","register","*"); any register surface should refresh.
    return event.entityType === "register" && (ename === "*" || toSnake(ename) === name);
  }
  if (kind === "documents") {
    return event.entityType === "document" && toSnake(ename) === name;
  }
  if (kind === "catalogs") {
    return event.entityType === "catalog" && toSnake(ename) === name;
  }
  return false;
}

export function DivKitView() {
  const location = useLocation();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const { theme, setTheme } = useTheme();
  const [profile, setProfile] = useState<string | null>(null);
  const [viewport, setViewport] = useState<Viewport>(() => viewportFor(window.innerWidth));
  const [shell, setShell] = useState<ShellData | null>(null);
  const [workspace, setWorkspace] = useState<Workspace>(initialWorkspace);
  const wsRef = useRef(workspace);
  wsRef.current = workspace;

  const resolvedTheme = useMemo<"light" | "dark">(() => {
    if (theme === "dark" || theme === "light") return theme;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }, [theme]);

  useEffect(() => {
    const onResize = () => setViewport(viewportFor(window.innerWidth));
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  // The nav highlight follows the focused island's active tab (not the raw URL), so
  // it clears when that island is emptied and tracks focus between islands — all
  // without re-fetching the shell.
  useEffect(() => {
    const focused = workspace.panes.find((p) => p.id === workspace.focused) ?? workspace.panes[0];
    activePathVar.setValue(focused?.activePath ?? "");
  }, [workspace]);

  // Mirror the URL into the focused island: ensure it has a tab for the route and
  // shows it. (Clicking a tab in another island focuses it and navigates here, so by
  // the time this runs the focused island already matches — a no-op.)
  useEffect(() => {
    setWorkspace((ws) => {
      const focused = ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
      if (
        focused.activePath === location.pathname &&
        focused.tabs.some((t) => t.path === location.pathname)
      ) {
        return ws;
      }
      const tab = tabForPath(location.pathname);
      const panes = ws.panes.map((p) => {
        if (p.id !== focused.id) return p;
        const tabs = p.tabs.some((t) => t.path === tab.path) ? p.tabs : [...p.tabs, tab];
        return { ...p, tabs, activePath: tab.path };
      });
      return { ...ws, panes, focused: focused.id };
    });
  }, [location.pathname]);

  // The shell depends on (viewport, theme, profile) — not the active route — so it's
  // fetched once per combo and reused across navigations.
  const shellEndpoint = useMemo(() => {
    const qs = new URLSearchParams();
    qs.set("viewport", viewport);
    qs.set("theme", resolvedTheme);
    if (profile) qs.set("profile", profile);
    return `/api/divkit/shell?${qs.toString()}`;
  }, [viewport, resolvedTheme, profile]);

  // Chrome: fast, no entity data — paint from cache instantly, else fetch once.
  useEffect(() => {
    const cached = shellCache.get(shellEndpoint);
    if (cached) {
      setShell(cached);
      return;
    }
    let cancelled = false;
    fetch(shellEndpoint, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((json) => {
        if (!cancelled) {
          shellCache.set(shellEndpoint, json as ShellData);
          setShell(json as ShellData);
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [shellEndpoint]);

  // ----- live updates: one SSE stream fans out to every mounted island -----

  const liveRegistry = useRef<LiveRegistry>(new Map());
  const refetchTimers = useRef<Map<string, number>>(new Map());

  const onUiEvent = useCallback((event: UiEvent) => {
    for (const [key, entry] of liveRegistry.current) {
      if (!affectsSurface(event, entry.path)) continue;
      // Coalesce bursts (e.g. a post emits both "posted" and register "changed").
      const timers = refetchTimers.current;
      window.clearTimeout(timers.get(key));
      timers.set(key, window.setTimeout(entry.run, 150));
    }
  }, []);
  useUiEvents(onUiEvent);

  useEffect(() => {
    const timers = refetchTimers.current;
    return () => {
      for (const t of timers.values()) window.clearTimeout(t);
    };
  }, []);

  // Open a route in the focused island: add a tab if missing and make it active, then
  // sync the URL. Doing the workspace update directly (not just navigate) means it
  // works even when the path equals the current URL — e.g. reopening the route whose
  // last tab was just closed, which leaves the URL unchanged so navigate() is a no-op.
  const openPath = useCallback(
    (path: string) => {
      setWorkspace((ws) => {
        const focused = ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
        const panes = ws.panes.map((p) => {
          if (p.id !== focused.id) return p;
          const tabs = p.tabs.some((t) => t.path === path) ? p.tabs : [...p.tabs, tabForPath(path)];
          return { ...p, tabs, activePath: path };
        });
        return { ...ws, panes, focused: focused.id };
      });
      if (path !== location.pathname) navigate(path);
    },
    [location.pathname, navigate]
  );

  const onCustomAction = useCallback(
    (action: { url?: string }) => {
      const url = action?.url;
      if (!url || !url.startsWith("onec://")) return;
      const rest = url.slice("onec://".length); // "logout" | "theme/toggle" | "app?profile=x" | "documents/foo/id"
      if (rest === "logout") {
        shellCache.clear();
        logout().finally(() => navigate("/login"));
        return;
      }
      if (rest === "theme/toggle") {
        setTheme(resolvedTheme === "dark" ? "light" : "dark");
        return;
      }
      if (rest.startsWith("app")) {
        const q = rest.indexOf("?");
        const params = new URLSearchParams(q >= 0 ? rest.slice(q + 1) : "");
        setProfile(params.get("profile"));
        setWorkspace(initialWorkspace());
        if (location.pathname !== "/") navigate("/");
        return;
      }
      if (rest.startsWith("delete/")) {
        // delete/documents/{name}/{id} — confirm, DELETE via REST, then back to the
        // list (the SSE "deleted" event patches any other open list live).
        const [kind, name, id] = rest.slice("delete/".length).split("/");
        if (kind === "documents" && name && id && window.confirm("Delete this document?")) {
          api.deleteDocument(name, id).then(() => navigate("/documents/" + name)).catch(() => {});
        }
        return;
      }
      openPath("/" + rest);
    },
    [navigate, location.pathname, logout, setTheme, resolvedTheme, openPath]
  );

  // ----- island / tab operations -----

  // Show a tab: focus its island, make it active, and sync the URL.
  const activateTab = useCallback(
    (paneId: string, path: string) => {
      setWorkspace((ws) => ({
        ...ws,
        focused: paneId,
        panes: ws.panes.map((p) => (p.id === paneId ? { ...p, activePath: path } : p)),
      }));
      if (path !== location.pathname) navigate(path);
    },
    [location.pathname, navigate]
  );

  const closeTab = useCallback(
    (paneId: string, path: string) => {
      const ws = wsRef.current;
      const pane = ws.panes.find((p) => p.id === paneId);
      if (!pane) return;
      // Closing the last tab of the only island leaves it blank (an empty island);
      // we don't navigate, so the URL effect won't repopulate it until the next nav.
      if (ws.panes.length === 1 && pane.tabs.length === 1) {
        setWorkspace({ panes: [{ ...pane, tabs: [], activePath: "" }], sizes: [1], focused: pane.id });
        return;
      }
      const next = detachTab(ws, path);
      setWorkspace(next);
      // If we closed the active tab of the focused island, follow it to the new active.
      if (path === location.pathname) {
        const focused = next.panes.find((p) => p.id === next.focused) ?? next.panes[0];
        if (focused.activePath && focused.activePath !== location.pathname) {
          navigate(focused.activePath);
        }
      }
    },
    [location.pathname, navigate]
  );

  // ----- drag a tab between / out of islands -----

  const dragRef = useRef<{ path: string; fromPaneId: string } | null>(null);
  const [dropTarget, setDropTarget] = useState<{ paneId: string; mode: "into" | "right" } | null>(
    null
  );
  // The strip + slot a reorder/move would drop into, driving the live preview gap.
  const [tabDrop, setTabDrop] = useState<{ paneId: string; index: number } | null>(null);
  // The tab currently being dragged (reactive mirror of dragRef, for the preview).
  const [dragState, setDragState] = useState<{ path: string; fromPaneId: string } | null>(null);

  // Move a dragged tab into an existing island (append + activate there).
  const moveTabInto = useCallback(
    (targetPaneId: string, path: string) => {
      const ws = wsRef.current;
      const target = ws.panes.find((p) => p.id === targetPaneId);
      if (!target) return;
      if (target.tabs.some((t) => t.path === path)) {
        activateTab(targetPaneId, path);
        return;
      }
      const detached = detachTab(ws, path);
      const tab = tabForPath(path);
      const panes = detached.panes.map((p) =>
        p.id === targetPaneId ? { ...p, tabs: [...p.tabs, tab], activePath: path } : p
      );
      setWorkspace({ panes, sizes: detached.sizes, focused: targetPaneId });
      if (path !== location.pathname) navigate(path);
    },
    [activateTab, location.pathname, navigate]
  );

  // Split: drop the dragged tab to the right of an island as a brand-new island.
  const splitRight = useCallback(
    (afterPaneId: string, path: string) => {
      const ws = wsRef.current;
      const source = ws.panes.find((p) => p.tabs.some((t) => t.path === path));
      // Splitting a single-tab island off itself is a no-op — nothing to split.
      if (source && source.id === afterPaneId && source.tabs.length === 1) {
        activateTab(afterPaneId, path);
        return;
      }
      const detached = detachTab(ws, path);
      const idx = detached.panes.findIndex((p) => p.id === afterPaneId);
      if (idx < 0) {
        moveTabInto(detached.panes[0].id, path);
        return;
      }
      const id = newPaneId();
      const tab = tabForPath(path);
      const panes = [
        ...detached.panes.slice(0, idx + 1),
        { id, tabs: [tab], activePath: path },
        ...detached.panes.slice(idx + 1),
      ];
      // Give the new island half of its left neighbour's weight, then renormalise.
      const sizes = [...detached.sizes];
      const share = sizes[idx] / 2;
      sizes[idx] = share;
      sizes.splice(idx + 1, 0, share);
      setWorkspace({ panes, sizes: normalize(sizes), focused: id });
      if (path !== location.pathname) navigate(path);
    },
    [activateTab, moveTabInto, location.pathname, navigate]
  );

  // Reorder within an island, or move a tab into one at a precise slot — the tab
  // strip's drop. {@code slot} is the insertion index among the target's tabs with
  // the dragged tab excluded (so it matches the live preview order exactly).
  const dropOnStrip = useCallback(
    (targetPaneId: string, slot: number) => {
      const drag = dragRef.current;
      if (!drag) return;
      const { path, fromPaneId } = drag;
      if (fromPaneId === targetPaneId) {
        setWorkspace((ws) => ({
          ...ws,
          focused: targetPaneId,
          panes: ws.panes.map((p) => {
            if (p.id !== targetPaneId) return p;
            const dragged = p.tabs.find((t) => t.path === path);
            if (!dragged) return p;
            const others = p.tabs.filter((t) => t.path !== path);
            const at = Math.max(0, Math.min(others.length, slot));
            return { ...p, tabs: [...others.slice(0, at), dragged, ...others.slice(at)] };
          }),
        }));
        return;
      }
      const ws = wsRef.current;
      const detached = detachTab(ws, path);
      const tab = tabForPath(path);
      const panes = detached.panes.map((p) => {
        if (p.id !== targetPaneId) return p;
        const at = Math.max(0, Math.min(p.tabs.length, slot));
        return { ...p, tabs: [...p.tabs.slice(0, at), tab, ...p.tabs.slice(at)], activePath: path };
      });
      setWorkspace({ panes, sizes: detached.sizes, focused: targetPaneId });
      if (path !== location.pathname) navigate(path);
    },
    [location.pathname, navigate]
  );

  const onTabDragStart = useCallback((paneId: string, path: string, e: React.DragEvent) => {
    dragRef.current = { path, fromPaneId: paneId };
    setDragState({ path, fromPaneId: paneId });
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", path);
  }, []);

  const onTabDragEnd = useCallback(() => {
    dragRef.current = null;
    setDragState(null);
    setDropTarget(null);
    setTabDrop(null);
  }, []);

  // Insertion slot in a strip for the cursor x: the count of tabs (excluding the one
  // being dragged) whose midpoint sits left of the cursor. Excluding the dragged tab
  // keeps the slot stable even as the live preview shuffles tabs around it.
  const stripInsertIndex = (strip: HTMLElement, clientX: number, draggedPath?: string): number => {
    const tabsEls = [...strip.querySelectorAll<HTMLElement>("[data-tab]")].filter(
      (el) => el.dataset.tab !== draggedPath
    );
    let idx = tabsEls.length;
    for (let i = 0; i < tabsEls.length; i++) {
      const r = tabsEls[i].getBoundingClientRect();
      if (clientX < r.left + r.width / 2) {
        idx = i;
        break;
      }
    }
    return idx;
  };

  // Which half of an island the cursor is over: the right third is "split", the rest
  // is "move into". Computed from geometry on the spot so it never lags drop state.
  const dropModeAt = (clientX: number, rect: DOMRect): "into" | "right" =>
    clientX > rect.right - Math.min(120, rect.width * 0.33) ? "right" : "into";

  const onDrop = useCallback(
    (paneId: string, mode: "into" | "right") => {
      const drag = dragRef.current;
      dragRef.current = null;
      setDropTarget(null);
      if (!drag) return;
      if (mode === "right") splitRight(paneId, drag.path);
      else moveTabInto(paneId, drag.path);
    },
    [moveTabInto, splitRight]
  );

  // Esc closes the focused island's active tab (unless you're typing in a field).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || viewport !== "desktop") return;
      const el = document.activeElement as HTMLElement | null;
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT" || el.isContentEditable)) {
        return;
      }
      const ws = wsRef.current;
      const focused = ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
      if (focused?.activePath) closeTab(focused.id, focused.activePath);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [closeTab, viewport]);

  // ----- resize between islands -----

  const containerRef = useRef<HTMLDivElement | null>(null);

  // FLIP: when the tab order shifts (live drag preview, or a committed reorder), slide
  // each tab from its old x to its new one instead of snapping. We record every tab's
  // position after each render and animate the delta on the next.
  const flipRects = useRef(new Map<string, DOMRect>());
  const flipRaf = useRef<number | null>(null);
  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    // A newer render supersedes the previous render's pending "play" frame; if we
    // let that stale frame run it clears the transform we're about to set → snap.
    if (flipRaf.current != null) cancelAnimationFrame(flipRaf.current);

    const els = Array.from(container.querySelectorAll<HTMLElement>("[data-flip]"));
    // Neutralize any in-flight transform *before* measuring. getBoundingClientRect
    // reports the visual box, so a tab caught mid-slide would otherwise record its
    // interpolated position as the baseline and the next delta would be wrong —
    // that's the intermittent snap.
    for (const el of els) {
      el.style.transition = "none";
      el.style.transform = "";
    }

    const prev = flipRects.current;
    const next = new Map<string, DOMRect>();
    for (const el of els) {
      const key = el.dataset.flip!;
      const rect = el.getBoundingClientRect(); // forces a flush of the resets above
      next.set(key, rect);
      // Don't fight the pointer: leave the tab being dragged alone.
      if (dragState && key.endsWith(":" + dragState.path)) continue;
      const old = prev.get(key);
      const dx = old ? old.left - rect.left : 0;
      if (Math.abs(dx) > 0.5) el.style.transform = `translateX(${dx}px)`;
    }
    flipRects.current = next;

    // Play: on the next frame, animate every inverted tab back to its real spot.
    flipRaf.current = requestAnimationFrame(() => {
      flipRaf.current = null;
      for (const el of els) {
        const key = el.dataset.flip!;
        if (dragState && key.endsWith(":" + dragState.path)) continue;
        el.style.transition = "transform 160ms ease";
        el.style.transform = "";
      }
    });
  });

  const startResize = useCallback((index: number, e: React.PointerEvent) => {
    e.preventDefault();
    const container = containerRef.current;
    if (!container) return;
    const totalWidth = container.getBoundingClientRect().width;
    const startX = e.clientX;
    const startSizes = wsRef.current.sizes.slice();
    const total = startSizes.reduce((a, b) => a + b, 0);
    const pair = startSizes[index] + startSizes[index + 1];
    const minWeight = total * 0.12; // each island keeps ~12% of the row

    const onMove = (ev: PointerEvent) => {
      const deltaWeight = ((ev.clientX - startX) / totalWidth) * total;
      let left = startSizes[index] + deltaWeight;
      left = Math.max(minWeight, Math.min(pair - minWeight, left));
      const sizes = startSizes.slice();
      sizes[index] = left;
      sizes[index + 1] = pair - left;
      setWorkspace((ws) => ({ ...ws, sizes }));
    };
    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }, []);

  // ----- presentation tokens (match ShellLayoutBuilder's Palette) -----

  const pageBg = resolvedTheme === "dark" ? "#0A0A0A" : "#FFFFFF";
  const skeletonBg = resolvedTheme === "dark" ? "#1F1F1F" : "#F5F5F5";
  const surfaceBg = resolvedTheme === "dark" ? "#121212" : "#FFFFFF";
  const borderColor = resolvedTheme === "dark" ? "#242424" : "#EBEBEB";
  const tabStripBg = resolvedTheme === "dark" ? "#0E0E0E" : "#FAFAFA";
  // A focused island reads with a slightly brighter border (no loud ring); its active
  // tab carries a thin top accent in the foreground tone.
  const focusBorder = resolvedTheme === "dark" ? "#3A3A3A" : "#D4D4D4";
  const accent = resolvedTheme === "dark" ? "#E5E5E5" : "#171717";
  const navStyle: NavStyle = shell?.navStyle ?? "topbar";

  const shellCard = (json: DivKitProps["json"], idPrefix: string) => (
    <DivKit
      id={`${idPrefix}:${resolvedTheme}:${viewport}`}
      json={json}
      theme={resolvedTheme}
      globalVariablesController={navVars}
      onCustomAction={onCustomAction as NonNullable<DivKitProps["onCustomAction"]>}
    />
  );

  const navEl = shell ? shellCard(shell.nav, "nav") : null;
  const accountEl = shell ? shellCard(shell.account, "account") : null;

  // A single content surface for the non-desktop layouts (no islands/tabs there).
  const plainContent = (
    <ContentPane
      path={location.pathname}
      viewport={viewport}
      theme={resolvedTheme}
      profile={profile}
      onAction={onCustomAction}
      registry={liveRegistry.current}
      skeletonBg={skeletonBg}
    />
  );

  // One island: a tab strip in its header (drop target + drag handles) over its
  // active surface. The whole island is a drop target ("into"); a strip down its
  // right edge is the "split" target that opens a new island.
  const island = (pane: Pane) => {
    const isDropInto = dropTarget?.paneId === pane.id && dropTarget.mode === "into";
    const isDropRight = dropTarget?.paneId === pane.id && dropTarget.mode === "right";
    const focused = pane.id === workspace.focused;

    // While a tab is dragged over this strip, lay the tabs out in their would-be
    // order so the row visibly shifts to open a gap: the dragged tab (if it's ours)
    // moves to the slot; a tab from another island shows a translucent ghost there.
    type Slot = { kind: "tab"; tab: WorkspaceTab; dragged: boolean } | { kind: "ghost"; title: string };
    let displayTabs: Slot[] = pane.tabs.map((tab) => ({ kind: "tab", tab, dragged: false }));
    if (dragState && tabDrop?.paneId === pane.id) {
      const others = pane.tabs.filter((t) => t.path !== dragState.path);
      const at = Math.max(0, Math.min(others.length, tabDrop.index));
      if (dragState.fromPaneId === pane.id) {
        const dragged = pane.tabs.find((t) => t.path === dragState.path);
        const rest: Slot[] = others.map((t) => ({ kind: "tab", tab: t, dragged: false }));
        if (dragged) rest.splice(at, 0, { kind: "tab", tab: dragged, dragged: true });
        displayTabs = rest;
      } else {
        const rest: Slot[] = pane.tabs.map((t) => ({ kind: "tab", tab: t, dragged: false }));
        rest.splice(at, 0, { kind: "ghost", title: tabForPath(dragState.path).title });
        displayTabs = rest;
      }
    }
    return (
      <section
        className="relative flex h-full min-w-0 flex-1 flex-col overflow-hidden rounded-2xl border transition-colors"
        style={{ background: surfaceBg, borderColor: focused ? focusBorder : borderColor }}
        onMouseDownCapture={() => {
          if (!focused) setWorkspace((ws) => ({ ...ws, focused: pane.id }));
        }}
        onDragOver={(e) => {
          if (!dragRef.current) return;
          e.preventDefault();
          const mode = dropModeAt(e.clientX, e.currentTarget.getBoundingClientRect());
          setDropTarget((t) =>
            t?.paneId === pane.id && t.mode === mode ? t : { paneId: pane.id, mode }
          );
        }}
        onDragLeave={(e) => {
          if (e.currentTarget.contains(e.relatedTarget as Node)) return;
          setDropTarget((t) => (t?.paneId === pane.id ? null : t));
        }}
        onDrop={(e) => {
          e.preventDefault();
          onDrop(pane.id, dropModeAt(e.clientX, e.currentTarget.getBoundingClientRect()));
        }}
      >
        {/* tab strip — lives inside the island, Rider-style. Owns the reorder/move
            drop (precise slot); the island body owns the append/split drop. */}
        <div
          className="flex min-h-11 shrink-0 items-center gap-1 overflow-x-auto border-b px-2 py-1.5"
          style={{ background: tabStripBg, borderColor }}
          onDragOver={(e) => {
            if (!dragRef.current) return;
            e.preventDefault();
            e.stopPropagation();
            setDropTarget(null);
            const index = stripInsertIndex(e.currentTarget, e.clientX, dragRef.current.path);
            setTabDrop((t) =>
              t?.paneId === pane.id && t.index === index ? t : { paneId: pane.id, index }
            );
          }}
          onDragLeave={(e) => {
            if (e.currentTarget.contains(e.relatedTarget as Node)) return;
            setTabDrop((t) => (t?.paneId === pane.id ? null : t));
          }}
          onDrop={(e) => {
            e.preventDefault();
            e.stopPropagation();
            const slot = stripInsertIndex(e.currentTarget, e.clientX, dragRef.current?.path);
            dropOnStrip(pane.id, slot); // reads dragRef.current
            dragRef.current = null;
            setDragState(null);
            setTabDrop(null);
            setDropTarget(null);
          }}
        >
          {displayTabs.map((slot) => {
            if (slot.kind === "ghost") {
              // Where a tab from another island will land.
              return (
                <div
                  key="ghost"
                  className="flex h-8 max-w-56 shrink-0 items-center rounded-lg px-3 text-sm text-muted-foreground"
                  style={{ background: `${accent}14`, border: `1px dashed ${focusBorder}` }}
                >
                  <span className="truncate">{slot.title}</span>
                </div>
              );
            }
            const tab = slot.tab;
            const active = tab.path === pane.activePath;
            // Selection reads as a subtle pill in the accent tone; a touch stronger on
            // the focused island so you can tell which one navigation drives.
            const fill = active ? (focused ? `${accent}1f` : `${accent}12`) : "transparent";
            return (
              <div
                key={tab.path}
                data-tab={tab.path}
                data-flip={`${pane.id}:${tab.path}`}
                title={tab.title}
                draggable
                onDragStart={(e) => onTabDragStart(pane.id, tab.path, e)}
                onDragEnd={onTabDragEnd}
                className={cn(
                  "group flex h-8 max-w-56 shrink-0 cursor-grab items-center rounded-lg pl-1 text-sm transition-colors active:cursor-grabbing",
                  active
                    ? "text-foreground"
                    : "text-muted-foreground hover:bg-muted/50 hover:text-foreground",
                  slot.dragged && "opacity-40"
                )}
                style={{ background: fill }}
              >
                <button
                  type="button"
                  className="min-w-0 flex-1 truncate px-2 text-left"
                  onClick={() => activateTab(pane.id, tab.path)}
                >
                  {tab.title}
                </button>
                <button
                  type="button"
                  aria-label={`Close ${tab.title}`}
                  className="mr-1 grid size-5 shrink-0 place-items-center rounded-md opacity-60 hover:bg-muted hover:opacity-100"
                  onClick={(event) => {
                    event.stopPropagation();
                    closeTab(pane.id, tab.path);
                  }}
                >
                  <X className="size-3.5" aria-hidden="true" />
                </button>
              </div>
            );
          })}
        </div>

        {/* active surface — or a blank island once every tab is closed */}
        {pane.tabs.length === 0 ? (
          <div className="grid min-h-0 flex-1 place-items-center">
            <p className="text-sm text-muted-foreground">No open tabs</p>
          </div>
        ) : (
          <div className="min-h-0 flex-1 overflow-auto">
            <ContentPane
              path={pane.activePath}
              viewport={viewport}
              theme={resolvedTheme}
              profile={profile}
              onAction={onCustomAction}
              registry={liveRegistry.current}
              skeletonBg={skeletonBg}
            />
          </div>
        )}

        {/* drop hints while dragging a tab over the body (append / split) */}
        {isDropInto ? (
          <div
            className="pointer-events-none absolute inset-0 rounded-2xl"
            style={{ background: `${accent}14`, boxShadow: `inset 0 0 0 2px ${accent}66` }}
          />
        ) : null}
        {isDropRight ? (
          <div
            className="pointer-events-none absolute inset-y-0 right-0 w-1/3 rounded-r-2xl"
            style={{ background: `${accent}1f`, boxShadow: `inset 0 0 0 2px ${accent}80` }}
          />
        ) : null}
      </section>
    );
  };

  if (navStyle === "sidebar") {
    return (
      <div className="flex h-screen w-full overflow-hidden" style={{ background: pageBg }}>
        <aside className="flex h-screen w-64 shrink-0 flex-col gap-3 p-3">
          <div
            className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden rounded-2xl border"
            style={{ background: surfaceBg, borderColor }}
          >
            {navEl}
          </div>
          {accountEl}
        </aside>
        <div ref={containerRef} className="flex min-w-0 flex-1 items-stretch py-3 pr-3">
          {workspace.panes.map((pane, i) => (
            <div
              key={pane.id}
              className="flex min-w-0"
              style={{ flexGrow: workspace.sizes[i], flexBasis: 0 }}
            >
              {island(pane)}
              {i < workspace.panes.length - 1 ? (
                <div
                  role="separator"
                  aria-orientation="vertical"
                  onPointerDown={(e) => startResize(i, e)}
                  className="group mx-0.5 flex w-2 shrink-0 cursor-col-resize items-center justify-center"
                >
                  <div className="h-10 w-1 rounded-full bg-border transition-colors group-hover:bg-blue-500" />
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (navStyle === "bottom_bar") {
    // Full-width centered on small phones; a compact corner island on tablets and
    // large phones (pinned bottom-right). Account lives on the /account page here.
    return (
      <div className="min-h-screen w-full overflow-x-hidden" style={{ background: pageBg }}>
        <div className="pb-24">{plainContent}</div>
        <nav className="fixed inset-x-0 bottom-0 z-10 p-3">
          <div className={viewport === "tablet" ? "ml-auto w-fit" : "mx-auto max-w-md"}>{navEl}</div>
        </nav>
      </div>
    );
  }

  return (
    <div className="min-h-screen w-full overflow-x-hidden" style={{ background: pageBg }}>
      <div className="p-3">{navEl}</div>
      {plainContent}
    </div>
  );
}
