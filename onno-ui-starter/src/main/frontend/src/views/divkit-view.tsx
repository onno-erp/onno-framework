import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { DivKit, type DivKitProps } from "@divkitframework/react";
import { Copy, ExternalLink, Link2, Pencil, Trash2, X, type LucideIcon } from "lucide-react";
import { toast } from "sonner";
import {
  createGlobalVariablesController,
  createVariable,
} from "@divkitframework/divkit/client-hydratable";
import { useAuth } from "@/providers/auth-provider";
import { useTheme } from "@/providers/theme-provider";
import { useBranding } from "@/providers/branding-provider";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";
import { api } from "@/lib/api";
import { useUiEvents } from "@/hooks/use-ui-events";
import type { UiEvent } from "@/lib/types";
import { cn, copyToClipboard } from "@/lib/utils";
import { stripBasePath, withBasePath } from "@/lib/base-path";
import { clearFormDirty, isFormDirty } from "@/lib/dirty-forms";
import { ContentPane, type LiveRegistry } from "@/views/content-pane";
import type { ContentAction } from "@/views/divkit-content";
import { ICON_CUSTOM_COMPONENTS, setIconActivePath } from "@/lib/icon-bridge";
import { NAV_PRESENCE_CUSTOM_COMPONENTS } from "@/lib/nav-presence-bridge";
import { NOTIFICATION_INDICATOR_CUSTOM_COMPONENTS } from "@/lib/notification-indicator-bridge";
import { startPresence } from "@/lib/presence-store";
import { TabPresence } from "@/components/presence-surfaces";
import { usePanePresence } from "@/lib/presence-store";
import { NotificationTrigger } from "@/components/notification-center";
import {
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuShortcut,
} from "@/components/ui/context-menu";
import { openPanel as openNotificationPanel, setNotificationsNavStyle } from "@/lib/notification-store";
import { isInteractiveLayerOpen, shortcutLabel, useGlobalKeybindings } from "@/lib/keybindings";
import { actionFeedbackFromError, applyActionResult } from "@/lib/action-feedback";
import { Button } from "@/components/ui/button";
import { DialogShell } from "@/components/ui/dialog-shell";
import "@divkitframework/divkit/dist/client.css";

// The shell nav/account cards render lucide icons, the ambient sidebar presence dots, and the
// unread-notification dot on the bottom bar's More tab as React islands.
const NAV_CARD_CUSTOM_COMPONENTS = new Map([
  ...ICON_CUSTOM_COMPONENTS,
  ...NAV_PRESENCE_CUSTOM_COMPONENTS,
  ...NOTIFICATION_INDICATOR_CUSTOM_COMPONENTS,
]);

/**
 * The authenticated app, rendered as DivKit cards: the chrome (/shell — top bar +
 * nav) paints instantly via the React wrapper, and the per-route content renders
 * through core DivKit instances ({@link ContentPane}) so server pushes apply in
 * place. On desktop the content area is a Rider-style set of islands: each island
 * carries its own tab strip, tabs can be dragged onto another island or onto an
 * island's right edge to split into a new island, and the dividers between islands
 * resize them. The client only routes: onno:// action URLs become navigation,
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
// Server-emitted nav paths are router-relative (e.g. "/catalogs/properties", no base-path prefix),
// so the seed must be too — strip the prefix off the raw URL. (This initial value is overwritten by
// the focus effect anyway, which reads the already-stripped useLocation pathname.)
const activePathVar = createVariable("active_path", "string", stripBasePath(window.location.pathname));
navVars.setVariable(activePathVar);

type NavStyle = "topbar" | "sidebar" | "bottom_bar";

type ShellData = {
  navStyle: NavStyle;
  // Where "/" should land. The dashboard ("/") when there is one, otherwise the first
  // real nav item — so a dashboard-less app opens on a real screen instead of a phantom
  // "Dashboard". The client redirects "/" here on load (see the redirect effect).
  home: string;
  nav: DivKitProps["json"];
  account: DivKitProps["json"];
  // Route path → localized entity title (e.g. "/catalogs/customers" → "Клиенты"), from the same
  // nav the sidebar renders. Workspace tabs title themselves from this instead of humanizing the
  // URL segment, so a tab reads in the chrome language. Absent for entities not placed in the nav.
  titles?: Record<string, string>;
};

type WorkspaceTab = { path: string; title: string };

// An island: an ordered set of open tabs plus the one currently shown. Several live
// side by side, each sized by a flex weight in {@link Workspace.sizes}.
type Pane = { id: string; tabs: WorkspaceTab[]; activePath: string };

// The desktop content area: islands left-to-right, their flex weights, and which one
// has focus (the island the URL/navigation drives).
type Workspace = { panes: Pane[]; sizes: number[]; focused: string };

// A pending confirmation shown in the in-app modal (replaces window.confirm), e.g.
// delete confirmations. onConfirm runs the action; the modal closes either way.
type ConfirmState = {
  title: string;
  message?: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => void;
};

const shellCache = new Map<string, ShellData>();

let paneSeq = 0;
function newPaneId(): string {
  paneSeq += 1;
  return `pane-${paneSeq}`;
}

// Percent-decode a single route segment for display. Route segments can be
// percent-encoded (a non-ASCII or spaced entity name leaks into the URL as
// e.g. "%D0%97..."), and a tab title built from the raw segment would show that
// encoded form. Falls back to the raw segment if it isn't valid encoding.
function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

function humanizeRouteToken(token: string): string {
  return decodeSegment(token)
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

// A path may carry a query (a prefilled New form: …/new?startsAt=…). Keep the full string as the
// stored tab path (the surface fetch needs the query), but read route segments from the query-less
// form so verb detection and title/identity aren't thrown off by the "?…" tail.
function stripQuery(pathname: string): string {
  return pathname.split("?")[0];
}

function tabForPath(pathname: string): WorkspaceTab {
  const path = pathname || "/";
  if (stripQuery(path) === "/") return { path, title: "Dashboard" };

  const [kind, name, detail, action] = stripQuery(path).split("/").filter(Boolean);
  const entity = name ? humanizeRouteToken(name) : humanizeRouteToken(kind ?? "Page");

  if (detail === "new") return { path, title: `New ${entity}` };
  if (action === "edit") return { path, title: `Edit ${entity}` };
  if (action === "duplicate") return { path, title: `Duplicate ${entity}` };
  if (detail) return { path, title: `${entity} ${decodeSegment(detail).slice(0, 8)}` };
  return { path, title: entity };
}

// The tab's display title, resolved at render time (not stored) so it tracks the shell's
// title map — which loads async after the first tab is already open — and the active chrome
// language. Prefers the entity's localized title from {@code titles}; falls back to the
// humanized route token when the entity isn't in the nav (a directly-routed, unlisted entity)
// or the shell hasn't loaded yet. The new/edit/duplicate verbs come from UiMessages so the
// whole tab localizes, not just the entity name.
function titleForPath(
  pathname: string,
  titles: Record<string, string> | undefined,
  t: Translate
): string {
  const path = stripQuery(pathname || "/");
  if (path === "/") return titles?.["/"] ?? t("nav.dashboard");

  const [kind, name, detail, action] = path.split("/").filter(Boolean);
  const basePath = name ? `/${kind}/${name}` : `/${kind ?? ""}`;
  const entity =
    titles?.[basePath] ?? (name ? humanizeRouteToken(name) : humanizeRouteToken(kind ?? "Page"));

  if (detail === "new") return t("tab.new", { entity });
  if (action === "edit") return t("tab.edit", { entity });
  if (action === "duplicate") return t("tab.duplicate", { entity });
  if (detail) return `${entity} ${decodeSegment(detail).slice(0, 8)}`;
  return entity;
}

// A record surface opened from a list — a document/catalog detail or a "new" form
// (3 segments). These open in their own island beside the list, master-detail style;
// 2-segment lists and 4-segment edit forms stay in the focused island.
function isRecordDetail(pathname: string): boolean {
  const seg = stripQuery(pathname).split("/").filter(Boolean);
  return seg.length === 3 && (seg[0] === "documents" || seg[0] === "catalogs");
}

function editBasePath(pathname: string): string | null {
  const seg = stripQuery(pathname).split("/").filter(Boolean);
  if (seg.length === 4 && (seg[0] === "documents" || seg[0] === "catalogs") && seg[3] === "edit") {
    return `/${seg[0]}/${seg[1]}/${seg[2]}`;
  }
  return null;
}

function recordBasePath(pathname: string): string | null {
  return editBasePath(pathname) ?? (isRecordDetail(pathname) ? stripQuery(pathname) : null);
}

function sameRecordTab(a: string, b: string): boolean {
  const ar = recordBasePath(a);
  const br = recordBasePath(b);
  return ar != null && ar === br;
}

function tabsWithRecordPath(tabs: WorkspaceTab[], path: string): WorkspaceTab[] {
  if (!recordBasePath(path)) {
    return tabs.some((t) => t.path === path) ? tabs : [...tabs, tabForPath(path)];
  }
  return [...tabs.filter((t) => !sameRecordTab(t.path, path)), tabForPath(path)];
}

// Turn a row's open url ("onno://{kind}/{name}/{id}") into the delete action url
// ("onno://delete/{kind}/{name}/{id}") handled by onCustomAction.
function rowDeleteUrl(rowUrl: string): string {
  return "onno://delete/" + rowUrl.slice("onno://".length);
}

// Turn a row's open url ("onno://{kind}/{name}/{id}") into its route path
// ("/{kind}/{name}/{id}") — the same path the URL bar shows when the row is open.
function rowOpenPath(rowUrl: string): string {
  return "/" + rowUrl.slice("onno://".length);
}

// The absolute, shareable URL for an in-app route path. The app uses real browser URLs, so
// origin + the base-path-prefixed path is a working deep link: pasted into a new tab it cold-loads
// straight onto that surface inside the router's basename. `path` is router-relative (no prefix);
// withBasePath adds the mount prefix (e.g. "/ui") so the link lands where the router expects it.
function shareableUrl(path: string): string {
  return window.location.origin + withBasePath(path);
}

function initialWorkspace(): Workspace {
  // Router-relative path (prefix stripped) so the first tab matches the useLocation pathname the
  // mirror effect compares against — otherwise a cold-loaded deep link would open a duplicate tab.
  const path = stripBasePath(window.location.pathname);
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
  // Comment-thread changes live-sync through the comments widget's own listener; they never alter
  // a list/detail/dashboard surface, so keep them off the content-pane refetch path — otherwise
  // every comment post would needlessly refetch the home dashboard (which refreshes on any change).
  if (event.entityType === "comment") return false;
  // Likewise presence (collaboration-marker) pings: handled only by the presence bar's own listener.
  // Without this, every heartbeat/join would refetch the open detail pane (and the home dashboard),
  // remounting the bar, which re-enters and emits another ping — an endless refetch/flicker loop.
  if (event.entityType === "presence") return false;
  // Home/dashboard widgets subscribe to the shared data-event fan-out and refresh their own
  // datasets. Remounting the whole page on every change resets charts and controls under load.
  if (pathname === "/" || pathname === "") return false;

  const seg = pathname.split("/").filter(Boolean); // ["documents","bills",...]
  const kind = seg[0]; // catalogs | documents | registers
  const name = seg[1];
  if (!kind || !name) return false;
  const ename = event.entityName ?? "";

  // Create/edit/duplicate forms are user-owned state. Live events should refresh lists,
  // dashboards, and read-only detail, but never remount a form while someone is working in it:
  // that closes open dropdowns, resets scroll, and can discard in-flight UI state.
  if (seg.length >= 3 && (seg[2] === "new" || seg[3] === "edit" || seg[3] === "duplicate")) return false;

  // A 2-segment catalog/document/register path is a list surface — now the self-refreshing
  // onno-list island (it reloads its own window via the "onno:dataevent" fan-out), so the DivKit
  // content pane must NOT refetch/remount it. Detail surfaces (3 segments) still refresh in place.
  // Registers are virtualized islands too (movements + balance), fed from /api/list/registers/...;
  // posting emits ("changed","register","*"), which the island picks up via the fan-out.
  if (seg.length === 2 && (kind === "catalogs" || kind === "documents" || kind === "registers")) return false;

  if (kind === "documents") {
    if (event.entityType !== "document" || toSnake(ename) !== name) return false;
    // A detail pane should only remount for its own record, not for every write to the same
    // document type under load. List islands refresh themselves from the raw data-event above.
    return seg.length === 3 && event.id === seg[2];
  }
  if (kind === "catalogs") {
    if (event.entityType !== "catalog" || toSnake(ename) !== name) return false;
    return seg.length === 3 && event.id === seg[2];
  }
  return false;
}

export function DivKitView() {
  const location = useLocation();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const { theme, setTheme } = useTheme();
  const branding = useBranding();
  const t = useMessages();
  const [profile, setProfile] = useState<string | null>(null);
  const [viewport, setViewport] = useState<Viewport>(() => viewportFor(window.innerWidth));
  const [shell, setShell] = useState<ShellData | null>(null);
  const [workspace, setWorkspace] = useState<Workspace>(initialWorkspace);
  const wsRef = useRef(workspace);
  wsRef.current = workspace;
  // A single, always-mounted presence driver for the focused pane's route. Feeding it by prop (not a
  // per-pane mounted component) means closing/opening/Esc-ing panes only changes its `path` — the hook
  // never remounts, so the "this tab's own route" marker moves old→new without flashing through null
  // (the self-dot no longer blinks on rapid pane churn).
  usePanePresence((workspace.panes.find((p) => p.id === workspace.focused) ?? workspace.panes[0])?.activePath ?? "");
  // Right-click menu for a list row: screen position + the row's onno:// open url.
  const [rowMenu, setRowMenu] = useState<{ x: number; y: number; url: string; writable: boolean } | null>(null);
  // Right-click menu for a workspace tab: screen position + the tab's route path.
  const [tabMenu, setTabMenu] = useState<{ x: number; y: number; path: string } | null>(null);
  // The in-app confirmation modal (delete, etc.); null when closed.
  const [confirm, setConfirm] = useState<ConfirmState | null>(null);
  // Mirror the menu/modal open-state into refs so the window-level Delete-key handler can read
  // it without re-subscribing on every toggle.
  const rowMenuOpenRef = useRef(false);
  rowMenuOpenRef.current = rowMenu !== null;
  const confirmOpenRef = useRef(false);
  confirmOpenRef.current = confirm !== null;

  // Resolve a tab's localized display title from its path. Memoized on the shell's title map and
  // the translator so every open tab re-titles the moment the shell loads or the language changes.
  const tabTitle = useCallback(
    (path: string) => titleForPath(path, shell?.titles, t),
    [shell?.titles, t]
  );

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
  // without re-fetching the shell. The icon bridge gets the same value, so glyphs and
  // labels can't disagree about which item is active.
  useEffect(() => {
    const focused = workspace.panes.find((p) => p.id === workspace.focused) ?? workspace.panes[0];
    const path = focused?.activePath ?? "";
    activePathVar.setValue(path);
    setIconActivePath(path);
  }, [workspace]);

  // Mirror the URL into the focused island: ensure it has a tab for the route and
  // shows it. (Clicking a tab in another island focuses it and navigates here, so by
  // the time this runs the focused island already matches — a no-op.)
  useEffect(() => {
    setWorkspace((ws) => {
      const focused = ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
      // An island intentionally emptied (last tab closed → URL reset to "/") stays blank;
      // don't re-open a "/" tab for it. Real routes still open normally.
      if (location.pathname === "/" && focused.tabs.length === 0) {
        return ws;
      }
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
    // Drop the previous shell while the new one (e.g. after a persona switch) loads, so
    // the landing redirect never acts on a stale `home` belonging to the old profile.
    setShell(null);
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

  // Set just before closeTab navigates a now-empty island to "/", so the landing effect below
  // skips the one auto-land that close would otherwise trigger. Without this, closing the last
  // tab of a dashboard-less app instantly reopens the home screen (Esc never appears to close it).
  const suppressLandingRef = useRef(false);

  // When the app has no dashboard, "/" isn't a real surface — the shell reports the path
  // to land on instead (the first nav item). Redirect there on load: drop the placeholder
  // "/" tab so no stray "Dashboard" tab lingers, and replace history so Back doesn't
  // return to the empty "/". An explicit "close the last tab" also routes to "/", but there the
  // user wants a blank island, not a re-land — suppressLandingRef distinguishes the two.
  useEffect(() => {
    const home = shell?.home;
    if (!home || home === "/" || location.pathname !== "/") return;
    if (suppressLandingRef.current) {
      suppressLandingRef.current = false;
      return;
    }
    setWorkspace((ws) => {
      const focused = ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
      const panes = ws.panes.map((p) => {
        if (p.id !== focused.id) return p;
        const kept = p.tabs.filter((t) => t.path !== "/");
        const tabs = kept.some((t) => t.path === home) ? kept : [...kept, tabForPath(home)];
        return { ...p, tabs, activePath: home };
      });
      return { ...ws, panes, focused: focused.id };
    });
    navigate(home, { replace: true });
  }, [shell, location.pathname, navigate]);

  // When the focused surface 404s — a stale post-login `from`, a role switch, or a deep link to a
  // route this profile can't reach — don't strand the user on a not-found card: drop the dead tab
  // and land on the shell's home (the first real nav item). Guarded so it only acts on the *active*
  // route and only when home is somewhere else, so it can't loop.
  const pendingNotFoundRef = useRef<string | null>(null);
  const landHome = useCallback((badPath: string, home: string) => {
    setWorkspace((ws) => {
      const focused = ws.panes.find((p) => p.id === ws.focused) ?? ws.panes[0];
      const panes = ws.panes.map((p) => {
        if (p.id !== focused.id) return p;
        const kept = p.tabs.filter((t) => t.path !== badPath);
        const tabs = kept.some((t) => t.path === home) ? kept : [...kept, tabForPath(home)];
        return { ...p, tabs, activePath: home };
      });
      return { ...ws, panes, focused: focused.id };
    });
    navigate(home, { replace: true });
  }, [navigate]);
  const handleContentNotFound = useCallback((badPath: string) => {
    if (badPath !== location.pathname) return;
    const home = shell?.home;
    // Shell not loaded yet (a cold post-login 404 can beat it) — remember the dead route and let
    // the effect below redirect once `home` arrives, so we never flash the not-found card.
    if (!home) {
      pendingNotFoundRef.current = badPath;
      return;
    }
    if (home === badPath) return;
    landHome(badPath, home);
  }, [shell, location.pathname, landHome]);
  useEffect(() => {
    const bad = pendingNotFoundRef.current;
    const home = shell?.home;
    if (bad && home && home !== bad && bad === location.pathname) {
      pendingNotFoundRef.current = null;
      landHome(bad, home);
    }
  }, [shell, location.pathname, landHome]);

  // ----- live updates: one SSE stream fans out to every mounted island -----

  const liveRegistry = useRef<LiveRegistry>(new Map());
  const refetchTimers = useRef<Map<string, number>>(new Map());

  const onUiEvent = useCallback((event: UiEvent) => {
    // Fan the raw event out to any onno-list islands (they self-refresh their window) — one shared
    // SSE stream instead of each island opening its own connection.
    window.dispatchEvent(new CustomEvent("onno:dataevent", { detail: event }));
    for (const [key, entry] of liveRegistry.current) {
      if (!affectsSurface(event, entry.path)) continue;
      // Coalesce bursts (e.g. a post emits both "posted" and register "changed").
      const timers = refetchTimers.current;
      window.clearTimeout(timers.get(key));
      timers.set(
        key,
        window.setTimeout(() => {
          // The record surface is an editable form: a refetch remounts the island, which would
          // wipe in-progress typing. Skip while dirty (checked at fire time — a save clears the
          // flag before its own SSE event lands, so the post-save refresh still goes through).
          if (isFormDirty(entry.path)) return;
          entry.run();
        }, 150)
      );
    }
  }, []);
  useUiEvents(onUiEvent);

  // Seed the ambient-presence store once and keep it live off the same SSE fan-out the islands consume.
  useEffect(() => {
    startPresence();
  }, []);

  // Tell the notification store which nav style the shell uses, so its floating fallback bell
  // only appears on the topbar layout (bottom-bar layouts route through the More menu instead).
  useEffect(() => {
    setNotificationsNavStyle(shell?.navStyle ?? "unknown");
  }, [shell?.navStyle]);

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
          if (p.id !== focused.id) {
            return recordBasePath(path) ? { ...p, tabs: p.tabs.filter((t) => !sameRecordTab(t.path, path)) } : p;
          }
          const tabs = tabsWithRecordPath(p.tabs, path);
          return { ...p, tabs, activePath: path };
        });
        return { ...ws, panes, focused: focused.id };
      });
      // Always navigate — never guard on location.pathname. The nav card's action handler is captured
      // once by the DivKit renderer, so a stale `location.pathname` (the landing route "/") would make
      // the guard `path !== location.pathname` wrongly skip navigating to the home page (Dashboard),
      // leaving it unreachable from the nav. React Router no-ops a navigate to the current location.
      navigate(path);
    },
    [navigate]
  );

  // Open a record beside the focused island, master-detail style — but cap auto-opened
  // columns at two so nested opens (a form, then "+ New" from a dropdown, then its edit…)
  // tab into the existing detail column instead of cramming the screen with ever more
  // panes. If it's already open anywhere, focus it; if a second column already exists,
  // pile the record on there as a tab; otherwise spawn the detail column to the right.
  const MAX_AUTO_COLUMNS = 2;
  const openDetailRight = useCallback(
    (path: string) => {
      setWorkspace((ws) => {
        const holder = ws.panes.find((p) =>
          p.tabs.some((t) => t.path === path || sameRecordTab(t.path, path))
        );
        if (holder) {
          return {
            ...ws,
            focused: holder.id,
            panes: ws.panes.map((p) =>
              p.id === holder.id
                ? { ...p, tabs: tabsWithRecordPath(p.tabs, path), activePath: path }
                : recordBasePath(path)
                  ? { ...p, tabs: p.tabs.filter((t) => !sameRecordTab(t.path, path)) }
                  : p
            ),
          };
        }
        const idx = Math.max(0, ws.panes.findIndex((p) => p.id === ws.focused));
        // Already at the column cap → tab into the rightmost pane (the detail column)
        // rather than splitting a new one.
        if (ws.panes.length >= MAX_AUTO_COLUMNS) {
          const target = ws.panes[ws.panes.length - 1];
          return {
            ...ws,
            focused: target.id,
            panes: ws.panes.map((p) =>
              p.id === target.id
                ? { ...p, tabs: tabsWithRecordPath(p.tabs, path), activePath: path }
                : recordBasePath(path)
                  ? { ...p, tabs: p.tabs.filter((t) => !sameRecordTab(t.path, path)) }
                  : p
            ),
          };
        }
        const id = newPaneId();
        const panes = [
          ...ws.panes.slice(0, idx + 1),
          { id, tabs: [tabForPath(path)], activePath: path },
          ...ws.panes.slice(idx + 1),
        ];
        const sizes = [...ws.sizes];
        const share = sizes[idx] / 2;
        sizes[idx] = share;
        sizes.splice(idx + 1, 0, share);
        return { panes, sizes: normalize(sizes), focused: id };
      });
      if (path !== location.pathname) navigate(path);
    },
    [location.pathname, navigate]
  );

  // Close a tab by its path wherever it lives (e.g. an edit form after saving). Repoints
  // focus to what remains in that island and follows it with the URL.
  const closePath = useCallback(
    (path: string) => {
      // Programmatic close (post-save, post-delete): the initiator owns the data outcome, so any
      // dirty flag is stale by definition — drop it rather than asking to discard.
      clearFormDirty(path);
      const ws = wsRef.current;
      const base = editBasePath(path);
      const editPane = base
        ? ws.panes.find((p) => p.tabs.some((t) => t.path === path))
        : undefined;
      if (base && editPane) {
        setWorkspace((current) => ({
          ...current,
          focused: editPane.id,
          panes: current.panes.map((p) => {
            if (p.id !== editPane.id) return p;
            return {
              ...p,
              tabs: tabsWithRecordPath(p.tabs, base),
              activePath: base,
            };
          }),
        }));
        if (base !== location.pathname) navigate(base);
        return;
      }
      if (!ws.panes.some((p) => p.tabs.some((t) => t.path === path))) return;
      const next = detachTab(ws, path);
      setWorkspace(next);
      const focused = next.panes.find((p) => p.id === next.focused) ?? next.panes[0];
      if (focused?.activePath && focused.activePath !== location.pathname) navigate(focused.activePath);
    },
    [location.pathname, navigate]
  );

  // Copy the shareable deep link for a route path to the clipboard, with a toast. Used by the
  // row right-click menu (a list row's record) and the tab right-click menu (any open surface —
  // detail, list, page, dashboard, register), so every screen the app can show is shareable by URL.
  const copyLink = useCallback((path: string) => {
    void copyToClipboard(shareableUrl(path)).then((ok) =>
      ok ? toast.success("Link copied") : toast.error("Couldn't copy link")
    );
  }, []);

  const onCustomAction = useCallback(
    (action: ContentAction) => {
      const url = action?.url;
      if (!url || !url.startsWith("onno://")) return;
      const rest = url.slice("onno://".length); // "logout" | "theme/toggle" | "app?profile=x" | "documents/foo/id"
      if (rest === "logout") {
        shellCache.clear();
        logout().finally(() => navigate("/login"));
        return;
      }
      if (rest === "theme/toggle") {
        setTheme(resolvedTheme === "dark" ? "light" : "dark");
        return;
      }
      if (rest === "notifications") {
        // The mobile menu's Notifications row — opens the client-owned panel, no route change.
        openNotificationPanel();
        return;
      }
      if (rest.startsWith("download/")) {
        // download/{path} — a server endpoint that returns a file with Content-Disposition:
        // attachment. A full-page GET lets the browser save it (the attachment header means the
        // current SPA view stays put). Used by print/export actions, e.g. Print to Word.
        window.location.href = "/" + rest.slice("download/".length);
        return;
      }
      if (rest.startsWith("open/")) {
        // open/{url} — view a stored file (a detail surface's file chip). An absolute http(s)
        // URL opens verbatim; an app-relative media path is re-rooted. New tab so the SPA stays put.
        const target = rest.slice("open/".length);
        const href = /^https?:\/\//i.test(target) ? target : "/" + target;
        window.open(href, "_blank", "noopener,noreferrer");
        return;
      }
      if (rest.startsWith("redirect/")) {
        // redirect/{url} — full-page navigation to an external URL (verbatim, query string and all),
        // e.g. an OAuth "Connect with X" consent screen that redirects back to our callback. Unlike
        // open/ this replaces the current page so the provider's round-trip lands back in the app.
        const target = rest.slice("redirect/".length);
        if (/^https?:\/\//i.test(target)) window.location.href = target;
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
        // delete/{kind}/{name}/{id} — confirm in the in-app modal, DELETE via REST, then
        // back to the list (the SSE "deleted" event patches any other open list live).
        const [kind, name, id] = rest.slice("delete/".length).split("/");
        if (!name || !id) return;
        // After deleting, close the record's pane (and any open edit pane) and let the
        // list refresh over SSE — don't navigate the list into the detail column.
        const after = () => {
          closePath("/" + kind + "/" + name + "/" + id + "/edit");
          closePath("/" + kind + "/" + name + "/" + id);
        };
        if (kind === "documents") {
          setConfirm({
            title: t("confirm.delete.document.title"),
            message: t("confirm.delete.document.message"),
            confirmLabel: t("action.delete"),
            danger: true,
            onConfirm: () => api.deleteDocument(name, id).then(after).catch(() => {}),
          });
        } else if (kind === "catalogs") {
          setConfirm({
            title: t("confirm.delete.item.title"),
            message: t("confirm.delete.item.message"),
            confirmLabel: t("action.delete"),
            danger: true,
            onConfirm: () => api.deleteCatalogItem(name, id).then(after).catch(() => {}),
          });
        }
        return;
      }
      if (rest.startsWith("action/")) {
        // action/{kind}/{name}/{key}/{id} — a custom server action from the detail header.
        // POST it (CSRF-aware, errors self-toast) and apply the ActionResult.
        const [kind, name, key, id] = rest.slice("action/".length).split("/");
        if (!kind || !name || !key) return;
        // The detail-header button is DivKit-rendered (no React control to spin), so a loading
        // toast gives feedback while a slow/async handler runs.
        const loadingId = toast.loading(t("loading.working"));
        api
          .runAction(kind, name, key, id)
          .then((result) => {
            toast.dismiss(loadingId);
            applyActionResult(result, { navigate: (url) => onCustomAction({ url }) });
            // A "refresh" result re-renders via the same SSE data stream the handler's writes
            // emit; the detail surface reloads itself, so no manual navigation is needed here.
          })
          .catch((error) => {
            toast.dismiss(loadingId);
            actionFeedbackFromError(error);
          });
        return;
      }
      if (rest.startsWith("post/") || rest.startsWith("unpost/")) {
        // post/{name}/{id} or unpost/{name}/{id} — drive the document's posting state via
        // REST. Posting emits ("posted"/"unposted", document) + ("changed","register","*")
        // over SSE, so the detail surface and any open register refresh themselves; no
        // manual navigation needed. The api layer toasts any failure (validation/balance).
        const unpost = rest.startsWith("unpost/");
        const [name, id] = rest.slice((unpost ? "unpost/" : "post/").length).split("/");
        if (!name || !id) return;
        const op = unpost ? api.unpostDocument(name, id) : api.postDocument(name, id);
        op.then(() => toast.success(t(unpost ? "toast.unposted" : "toast.posted"))).catch(() => {});
        return;
      }
      const path = "/" + rest;
      // On the desktop islands layout, a record opens in its own island to the right;
      // elsewhere (single content pane) it just navigates.
      if (shell?.navStyle === "sidebar" && recordBasePath(path)) {
        openDetailRight(path);
      } else {
        openPath(path);
      }
    },
    [
      navigate,
      location.pathname,
      logout,
      setTheme,
      resolvedTheme,
      openPath,
      openDetailRight,
      closePath,
      shell?.navStyle,
      t,
    ]
  );

  // A stable dispatcher over the latest onCustomAction. The DivKit wrapper tears down and
  // re-renders its whole Svelte card whenever any prop identity changes (its effect deps on the
  // props object), so the shell cards must receive a handler whose identity never changes — the
  // ref indirection keeps the behaviour of the freshest closure without the churn.
  const onCustomActionRef = useRef(onCustomAction);
  onCustomActionRef.current = onCustomAction;
  const onShellAction = useCallback((action: ContentAction) => onCustomActionRef.current(action), []);
  const hoveredRowUrl = useCallback(() => {
    const el = document.querySelector("[data-onno-row]:hover") as HTMLElement | null;
    return el?.dataset.onnoRow;
  }, []);
  // Whether the hovered row's entity is writable by the viewer. Rows stamp
  // data-onno-row-writable="0" when the server said canWrite=false (see ListDescriptor.canWrite);
  // absent means writable (older servers, DivKit-native rows) so behavior doesn't regress.
  const hoveredRowWritable = useCallback(() => {
    const el = document.querySelector("[data-onno-row]:hover") as HTMLElement | null;
    return el?.dataset.onnoRowWritable !== "0";
  }, []);
  const noActiveTextSelection = useCallback(() => {
    const selection = window.getSelection();
    return !selection || selection.isCollapsed || !selection.toString().trim();
  }, []);

  // Right-click on a list row (stamped with data-onno-row by the DivKit "row" extension)
  // opens a small Open / Edit menu. One window-level listener serves every island; any
  // left-click / Esc / resize dismisses it.
  useEffect(() => {
    const onCtx = (e: MouseEvent) => {
      // The onno-list island owns its rows' menu (custom actions, submenus, batch ops) and
      // preventDefaults the event — this DOM-sniffing fallback serves the remaining DivKit-native
      // and grouped rows.
      if (e.defaultPrevented) return;
      // Yield to an active text selection: if the user highlighted a value and right-clicks,
      // let the browser's native Copy menu through instead of hijacking it with the row menu.
      const selection = window.getSelection();
      if (selection && !selection.isCollapsed && selection.toString().trim()) return;
      const el = (e.target as HTMLElement)?.closest?.("[data-onno-row]") as HTMLElement | null;
      const url = el?.dataset.onnoRow;
      if (!url) return;
      e.preventDefault();
      setTabMenu(null);
      // writable mirrors hoveredRowWritable: "0" = the server denied write on this entity.
      setRowMenu({ x: e.clientX, y: e.clientY, url, writable: el?.dataset.onnoRowWritable !== "0" });
    };
    const close = () => {
      setRowMenu(null);
      setTabMenu(null);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("contextmenu", onCtx);
    window.addEventListener("click", close);
    window.addEventListener("resize", close);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("contextmenu", onCtx);
      window.removeEventListener("click", close);
      window.removeEventListener("resize", close);
      window.removeEventListener("keydown", onKey);
    };
  }, []);

  const rowKeybindings = useMemo(
    () => [
      {
        key: "Enter",
        mod: true,
        run: () => {
          if (rowMenuOpenRef.current || confirmOpenRef.current) return;
          const url = hoveredRowUrl();
          if (url) onCustomAction({ url });
        },
      },
      {
        key: "e",
        mod: true,
        run: () => {
          if (rowMenuOpenRef.current || confirmOpenRef.current) return;
          const url = hoveredRowUrl();
          if (url && hoveredRowWritable()) onCustomAction({ url: `${url}/edit` });
        },
      },
      {
        key: "d",
        mod: true,
        shift: true,
        run: () => {
          if (rowMenuOpenRef.current || confirmOpenRef.current) return;
          const url = hoveredRowUrl();
          if (url && hoveredRowWritable()) onCustomAction({ url: `${url}/duplicate` });
        },
      },
      {
        key: "c",
        mod: true,
        shift: true,
        run: () => {
          if (rowMenuOpenRef.current || confirmOpenRef.current || !noActiveTextSelection()) return;
          const url = hoveredRowUrl();
          if (url) copyLink(rowOpenPath(url));
        },
      },
      {
        key: "Delete",
        run: () => {
          if (rowMenuOpenRef.current || confirmOpenRef.current) return;
          const url = hoveredRowUrl();
          if (url && hoveredRowWritable()) onCustomAction({ url: rowDeleteUrl(url) });
        },
      },
    ],
    [copyLink, hoveredRowUrl, hoveredRowWritable, noActiveTextSelection, onCustomAction]
  );
  useGlobalKeybindings(rowKeybindings);

  // The React form widgets (onno-form) and ref picker live outside DivKit's action flow,
  // so they reach the host through window events: "onno:action" routes an onno:// url
  // through the same handler as a DivKit action (open record, open catalog new-form),
  // and "onno:closepath" closes a form pane after save/cancel.
  useEffect(() => {
    const onAction = (e: Event) => {
      const url = (e as CustomEvent).detail;
      if (typeof url === "string") onCustomAction({ url });
    };
    const onClose = (e: Event) => {
      const path = (e as CustomEvent).detail;
      if (typeof path === "string") closePath(path);
    };
    window.addEventListener("onno:action", onAction);
    window.addEventListener("onno:closepath", onClose);
    return () => {
      window.removeEventListener("onno:action", onAction);
      window.removeEventListener("onno:closepath", onClose);
    };
  }, [onCustomAction, closePath]);

  // Esc dismisses the confirmation modal.
  useEffect(() => {
    if (!confirm) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setConfirm(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [confirm]);

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

  // Self-reference so the discard-confirm can re-enter the (by then flag-cleared) close without
  // capturing a stale callback identity.
  const closeTabRef = useRef<((paneId: string, path: string) => void) | null>(null);
  const closeTab = useCallback(
    (paneId: string, path: string) => {
      // A form tab with unsaved edits doesn't close silently — confirm the discard first (the
      // form marks/clears its flag; save and Cancel clear it before they close, so only a stray
      // X / Esc / middle-click lands here). Confirming re-enters with the flag cleared.
      if (isFormDirty(path)) {
        setConfirm({
          title: t("confirm.discard.title"),
          message: t("confirm.discard.message"),
          confirmLabel: t("action.discard"),
          danger: true,
          onConfirm: () => {
            clearFormDirty(path);
            closeTabRef.current?.(paneId, path);
          },
        });
        return;
      }
      const ws = wsRef.current;
      const pane = ws.panes.find((p) => p.id === paneId);
      if (!pane) return;
      // Closing the last tab of the only island leaves it blank (an empty island).
      // Reset the URL to the app root so it no longer points at the just-closed surface, and tell
      // the landing effect to leave it blank rather than re-landing the home path (which would make
      // the just-closed tab reappear instantly on dashboard-less apps).
      if (ws.panes.length === 1 && pane.tabs.length === 1) {
        setWorkspace({ panes: [{ ...pane, tabs: [], activePath: "" }], sizes: [1], focused: pane.id });
        if (location.pathname !== "/") {
          suppressLandingRef.current = true;
          navigate("/", { replace: true });
        }
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
    [location.pathname, navigate, t]
  );
  closeTabRef.current = closeTab;

  // ----- drag a tab between / out of islands -----

  const dragRef = useRef<{ path: string; fromPaneId: string } | null>(null);
  const [dropTarget, setDropTarget] = useState<{ paneId: string; mode: "left" | "into" | "right" } | null>(
    null
  );
  // The strip + slot a reorder/move would drop into, driving the live preview gap.
  const [tabDrop, setTabDrop] = useState<{ paneId: string; index: number } | null>(null);
  // The tab currently being dragged (reactive mirror of dragRef, for the preview).
  const [dragState, setDragState] = useState<{ path: string; fromPaneId: string } | null>(null);

  const clearTabDrag = useCallback(() => {
    dragRef.current = null;
    setDragState(null);
    setDropTarget(null);
    setTabDrop(null);
  }, []);

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

  // Split: drop the dragged tab to the left of an island as a brand-new island inserted
  // before it (dropping on the leftmost island's left edge pins the tab to the far left).
  const splitLeft = useCallback(
    (beforePaneId: string, path: string) => {
      const ws = wsRef.current;
      const source = ws.panes.find((p) => p.tabs.some((t) => t.path === path));
      if (source && source.id === beforePaneId && source.tabs.length === 1) {
        activateTab(beforePaneId, path);
        return;
      }
      const detached = detachTab(ws, path);
      const idx = detached.panes.findIndex((p) => p.id === beforePaneId);
      if (idx < 0) {
        moveTabInto(detached.panes[0].id, path);
        return;
      }
      const id = newPaneId();
      const tab = tabForPath(path);
      const panes = [
        ...detached.panes.slice(0, idx),
        { id, tabs: [tab], activePath: path },
        ...detached.panes.slice(idx),
      ];
      // Split the target's weight with the new island inserted before it.
      const sizes = [...detached.sizes];
      const share = sizes[idx] / 2;
      sizes[idx] = share;
      sizes.splice(idx, 0, share);
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
    clearTabDrag();
  }, [clearTabDrag]);

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

  // Where over an island the cursor is: the left edge splits a new island before it, the
  // right edge splits one after it, the middle moves the tab into it. Computed from
  // geometry on the spot so it never lags drop state.
  const dropModeAt = (clientX: number, rect: DOMRect): "left" | "into" | "right" => {
    const edge = Math.min(120, rect.width * 0.33);
    if (clientX < rect.left + edge) return "left";
    if (clientX > rect.right - edge) return "right";
    return "into";
  };

  const onDrop = useCallback(
    (paneId: string, mode: "left" | "into" | "right") => {
      const drag = dragRef.current;
      clearTabDrag();
      if (!drag) return;
      if (mode === "right") splitRight(paneId, drag.path);
      else if (mode === "left") splitLeft(paneId, drag.path);
      else moveTabInto(paneId, drag.path);
    },
    [clearTabDrag, moveTabInto, splitRight, splitLeft]
  );

  useEffect(() => {
    const clearIfDragging = () => {
      if (dragRef.current || dragState) clearTabDrag();
    };
    const clearAfterNativeDrop = () => window.setTimeout(clearIfDragging, 0);
    const clearAfterPointerRelease = () => window.setTimeout(clearIfDragging, 0);
    const clearOnEscape = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || (!dragRef.current && !dragState)) return;
      e.preventDefault();
      clearTabDrag();
    };
    const clearOnVisibilityChange = () => {
      if (document.visibilityState === "hidden") clearIfDragging();
    };

    window.addEventListener("dragend", clearIfDragging);
    window.addEventListener("drop", clearAfterNativeDrop);
    window.addEventListener("pointerup", clearAfterPointerRelease);
    window.addEventListener("blur", clearIfDragging);
    window.addEventListener("keydown", clearOnEscape);
    document.addEventListener("visibilitychange", clearOnVisibilityChange);
    return () => {
      window.removeEventListener("dragend", clearIfDragging);
      window.removeEventListener("drop", clearAfterNativeDrop);
      window.removeEventListener("pointerup", clearAfterPointerRelease);
      window.removeEventListener("blur", clearIfDragging);
      window.removeEventListener("keydown", clearOnEscape);
      document.removeEventListener("visibilitychange", clearOnVisibilityChange);
    };
  }, [clearTabDrag, dragState]);

  // Esc closes the focused island's active tab (unless you're typing in a field).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      if (e.key !== "Escape" || viewport !== "desktop") return;
      if (dragRef.current || dragState) return;
      if (isInteractiveLayerOpen()) return;
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
  }, [closeTab, dragState, viewport]);

  // ----- resize between islands -----

  const containerRef = useRef<HTMLDivElement | null>(null);

  // FLIP: when the tab order shifts (live drag preview, or a committed reorder), slide
  // each tab from its old x to its new one instead of snapping. We record every tab's
  // position after each render and animate the delta on the next.
  const flipRects = useRef(new Map<string, number>());
  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const prev = flipRects.current;
    const next = new Map<string, number>();
    for (const el of container.querySelectorAll<HTMLElement>("[data-flip]")) {
      const key = el.dataset.flip!;
      // offsetLeft, not getBoundingClientRect: the layout position, immune to the
      // tab's own in-flight transform and to the strip's scroll. Measuring the
      // visual box instead would record a mid-slide tab's interpolated position as
      // the baseline, so the next delta — and the animation back — would be wrong.
      const left = el.offsetLeft;
      next.set(key, left);
      // Don't fight the pointer: leave the tab being dragged alone.
      if (dragState && key.endsWith(":" + dragState.path)) continue;
      const old = prev.get(key);
      const dx = old != null ? old - left : 0;
      if (Math.abs(dx) > 0.5) {
        el.style.transition = "none";
        el.style.transform = `translateX(${dx}px)`;
        // Play: next frame, release to the real position with a transition. A stale
        // frame from an earlier render is harmless — every play does the same thing.
        requestAnimationFrame(() => {
          el.style.transition = "transform 160ms ease";
          el.style.transform = "";
        });
      }
    }
    flipRects.current = next;
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

  // The React shell paints its own islands/tabs (not server-rendered DivKit), so it
  // mirrors the Palette here. A consumer's brand overrides arrive via /api/branding and
  // win per slot — the strip uses the branded surface/border and the active tab uses
  // the palette's soft primary accent instead of a hard-coded neutral or red-tinted pill.
  const brand = (resolvedTheme === "dark" ? branding.palette?.dark : branding.palette?.light) ?? {};
  const pageBg = brand.page ?? (resolvedTheme === "dark" ? "#0D0D0D" : "#FFFFFF");
  const skeletonBg = resolvedTheme === "dark" ? "#1F1F1F" : "#F5F5F5";
  const surfaceBg = brand.surface ?? (resolvedTheme === "dark" ? "#121212" : "#FFFFFF");
  const borderColor = brand.border ?? (resolvedTheme === "dark" ? "#242424" : "#EBEBEB");
  const tabStripBg = brand.surface ?? (resolvedTheme === "dark" ? "#0E0E0E" : "#FAFAFA");
  // The focused island's border signals *which pane is active* — that's state, not structure, so
  // it carries the brand when one is set, but only as a faint ~25%-opacity wash so it reads as a
  // soft hint rather than a loud frame (the focus ring is desaturated for the same reason). Resting
  // borders stay neutral; the focused one lights up just enough to tell panes apart.
  const focusBorder = brand.primary ? `${brand.primary}40` : (resolvedTheme === "dark" ? "#3A3A3A" : "#D4D4D4");
  const accent = brand.primary ?? (resolvedTheme === "dark" ? "#E5E5E5" : "#171717");
  const activeTabBg = brand.primarySoft ?? "hsl(var(--accent))";
  const activeTabText = brand.primary ?? "hsl(var(--accent-foreground))";
  const navStyle: NavStyle = shell?.navStyle ?? "topbar";

  // Memoized so the element identity only changes when the card actually needs a re-render
  // (new shell JSON / theme / viewport). The DivKit wrapper destroys and re-creates its Svelte
  // instance on every prop-object change, so handing it a fresh element per DivKitView render
  // rebuilt the whole nav card dozens of times per second under render churn.
  const shellCard = useCallback(
    (json: DivKitProps["json"], idPrefix: string) => (
      <DivKit
        id={`${idPrefix}:${resolvedTheme}:${viewport}`}
        json={json}
        theme={resolvedTheme}
        globalVariablesController={navVars}
        onCustomAction={onShellAction as NonNullable<DivKitProps["onCustomAction"]>}
        customComponents={NAV_CARD_CUSTOM_COMPONENTS}
      />
    ),
    [resolvedTheme, viewport, onShellAction]
  );

  const navEl = useMemo(() => (shell ? shellCard(shell.nav, "nav") : null), [shell, shellCard]);
  const accountEl = useMemo(() => (shell ? shellCard(shell.account, "account") : null), [shell, shellCard]);

  // A single content surface for the non-desktop layouts (no islands/tabs there).
  // Driven by the focused pane's activePath — which openPath() updates synchronously in
  // the tap handler — not by useLocation: react-router v7 wraps navigation in
  // startTransition, so sustained default-lane render churn can starve the location
  // update and freeze bottom-bar/topbar navigation. The workspace seeds from the URL on
  // first mount (initialWorkspace) and mirrors location changes (popstate/back) via the
  // URL-mirror effect above, so deep links and history navigation still land here; the
  // URL itself is just a cosmetic sync.
  const focusedPane = workspace.panes.find((p) => p.id === workspace.focused) ?? workspace.panes[0];
  const plainContent = (
    <ContentPane
      path={focusedPane?.activePath || location.pathname}
      viewport={viewport}
      theme={resolvedTheme}
      profile={profile}
      onAction={onCustomAction}
      registry={liveRegistry.current}
      skeletonBg={skeletonBg}
      onNotFound={handleContentNotFound}
    />
  );

  // Shared by the list-row menu and workspace-tab menu so right-click affordances look and behave
  // alike. The row shortcuts operate on the same hovered row this menu targets.
  type MenuItem = {
    label: string;
    icon: LucideIcon;
    run: () => void;
    danger?: boolean;
    divider?: boolean;
    shortcut?: string;
  };
  const contextMenu = (pos: { x: number; y: number }, items: MenuItem[], close: () => void) => (
    <ContextMenuContent
      open
      position={pos}
      onOpenChange={(open) => {
        if (!open) close();
      }}
      width={216}
      estimatedHeight={items.length * 38 + 24}
      style={{ background: surfaceBg, borderColor }}
    >
      {items.map(({ label, icon: Icon, run, danger, divider, shortcut }) => (
        <div key={label}>
          {divider ? <ContextMenuSeparator style={{ backgroundColor: borderColor }} /> : null}
          <ContextMenuItem
            variant={danger ? "destructive" : "default"}
            onSelect={() => {
              run();
              close();
            }}
          >
            <Icon className={cn(danger ? "text-destructive" : "text-muted-foreground")} aria-hidden="true" />
            <span>{label}</span>
            {shortcut ? <ContextMenuShortcut>{shortcut}</ContextMenuShortcut> : null}
          </ContextMenuItem>
        </div>
      ))}
    </ContextMenuContent>
  );

  // The row right-click menu, rendered once per layout. Write actions (Edit/Duplicate/Delete)
  // drop out when the row's entity isn't writable by the viewer (data-onno-row-writable="0").
  const rowMenuEl = rowMenu
    ? contextMenu(
        rowMenu,
        [
          {
            label: t("action.open"),
            icon: ExternalLink,
            run: () => onCustomAction({ url: rowMenu.url }),
            shortcut: shortcutLabel({ key: "Enter", mod: true }),
          },
          ...(rowMenu.writable
            ? [
                {
                  label: t("action.edit"),
                  icon: Pencil,
                  run: () => onCustomAction({ url: rowMenu.url + "/edit" }),
                  shortcut: shortcutLabel({ key: "e", mod: true }),
                },
                {
                  label: t("action.duplicate"),
                  icon: Copy,
                  run: () => onCustomAction({ url: rowMenu.url + "/duplicate" }),
                  shortcut: shortcutLabel({ key: "d", mod: true, shift: true }),
                },
              ]
            : []),
          {
            label: t("action.copyLink"),
            icon: Link2,
            run: () => copyLink(rowOpenPath(rowMenu.url)),
            divider: rowMenu.writable,
            shortcut: shortcutLabel({ key: "c", mod: true, shift: true }),
          },
          // delete/{kind}/{name}/{id} — routes through the same confirm + REST flow as the
          // detail header's delete (handled in onCustomAction).
          ...(rowMenu.writable
            ? [
                {
                  label: t("action.delete"),
                  icon: Trash2,
                  run: () => onCustomAction({ url: rowDeleteUrl(rowMenu.url) }),
                  danger: true,
                  divider: true,
                  shortcut: shortcutLabel({ key: "Delete" }),
                },
              ]
            : []),
        ],
        () => setRowMenu(null)
      )
    : null;

  // The tab right-click menu — copy a shareable link to whatever surface the tab shows
  // (record detail, list, page, dashboard, register), covering the cases a list row can't.
  const tabMenuEl = tabMenu
    ? contextMenu(
        tabMenu,
        [{ label: t("action.copyLink"), icon: Link2, run: () => copyLink(tabMenu.path) }],
        () => setTabMenu(null)
      )
    : null;

  // The in-app confirmation modal (replaces window.confirm): a backdrop over a centered
  // card with Cancel / confirm actions. Backdrop click or Esc cancels.
  const confirmEl = confirm ? (
    <DialogShell
      role="alertdialog"
      title={confirm.title}
      description={confirm.message}
      tone={confirm.danger ? "error" : "info"}
      size="sm"
      onOpenChange={(open) => {
        if (!open) setConfirm(null);
      }}
      footer={
        <>
          <Button
            type="button"
            variant="outline"
            onClick={() => setConfirm(null)}
          >
            {t("action.cancel")}
          </Button>
          <Button
            type="button"
            autoFocus
            variant={confirm.danger ? "destructive" : "default"}
            onClick={() => {
              confirm.onConfirm();
              setConfirm(null);
            }}
          >
            {confirm.confirmLabel}
          </Button>
        </>
      }
    />
  ) : null;

  // One island: a tab strip in its header (drop target + drag handles) over its
  // active surface. The whole island is a drop target ("into"); a strip down its
  // right edge is the "split" target that opens a new island.
  const island = (pane: Pane) => {
    const isDropInto = dropTarget?.paneId === pane.id && dropTarget.mode === "into";
    const isDropRight = dropTarget?.paneId === pane.id && dropTarget.mode === "right";
    const isDropLeft = dropTarget?.paneId === pane.id && dropTarget.mode === "left";
    const hasTabs = pane.tabs.length > 0;
    const focused = pane.id === workspace.focused;
    const showFocusedChrome = focused && hasTabs;

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
        rest.splice(at, 0, { kind: "ghost", title: tabTitle(dragState.path) });
        displayTabs = rest;
      }
    }
    return (
      <section
        className="relative flex h-full min-w-0 flex-1 flex-col overflow-hidden rounded-card border transition-colors"
        style={{ background: surfaceBg, borderColor: showFocusedChrome ? focusBorder : borderColor }}
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
            clearTabDrag();
          }}
        >
          {displayTabs.map((slot) => {
            if (slot.kind === "ghost") {
              // Where a tab from another island will land.
              return (
                <div
                  key="ghost"
                  className="flex h-8 max-w-56 shrink-0 items-center rounded-field px-3 text-sm text-muted-foreground"
                  style={{ background: `${accent}14`, border: `1px dashed ${focusBorder}` }}
                >
                  <span className="truncate">{slot.title}</span>
                </div>
              );
            }
            const tab = slot.tab;
            const label = tabTitle(tab.path);
            const active = tab.path === pane.activePath;
            // Selection reads in the configured brand accent. With an explicit
            // primarySoft this matches the server-rendered DivKit tabs/nav; otherwise it
            // falls back to the shadcn accent variable that BrandingProvider derives from
            // the brand primary.
            const fill = active ? activeTabBg : "transparent";
            const activeText = active ? activeTabText : undefined;
            return (
              <div
                key={tab.path}
                data-tab={tab.path}
                data-flip={`${pane.id}:${tab.path}`}
                title={label}
                draggable
                onDragStart={(e) => onTabDragStart(pane.id, tab.path, e)}
                onDragEnd={onTabDragEnd}
                onContextMenu={(e) => {
                  e.preventDefault();
                  setRowMenu(null);
                  setTabMenu({ x: e.clientX, y: e.clientY, path: tab.path });
                }}
                className={cn(
                  "group flex h-8 max-w-56 shrink-0 cursor-grab items-center rounded-field pl-1 text-sm transition-colors active:cursor-grabbing",
                  active
                    ? "text-foreground"
                    : "text-muted-foreground hover:bg-muted/50 hover:text-foreground",
                  // Hide the source tab while it's dragged, but keep its slot so the
                  // empty space reads as the live "drop here" gap that follows the cursor.
                  slot.dragged && "opacity-0"
                )}
                style={{ background: fill, color: activeText }}
              >
                <button
                  type="button"
                  className="min-w-0 flex-1 truncate px-2 text-left"
                  onClick={() => activateTab(pane.id, tab.path)}
                >
                  {label}
                </button>
                <button
                  type="button"
                  aria-label={`Close ${label}`}
                  className="mr-1 grid size-5 shrink-0 place-items-center rounded-md opacity-60 transition hover:bg-foreground/10 hover:opacity-100"
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
          {/* Collaborator avatars for the focused pane's record — pinned right of the tabs. */}
          {showFocusedChrome && pane.activePath ? (
            <div className="ml-auto flex shrink-0 items-center pl-2 pr-1">
              <TabPresence path={pane.activePath} />
            </div>
          ) : null}
        </div>
        {/* Presence for the focused pane is driven once at the top of DivKitView (a single stable hook),
            not per-pane — see usePanePresence(focused pane's activePath) there. */}

        {/* Every open tab stays mounted in its own scroll container; only the active
            one is shown. Keeping them alive preserves each tab's scroll position,
            DivKit state, and form inputs across switches (no remount, no refetch). */}
        <div className="relative min-h-0 flex-1">
          {pane.tabs.length === 0 ? (
            <div className="grid h-full place-items-center">
              <p className="text-sm text-muted-foreground">No open tabs</p>
            </div>
          ) : (
            pane.tabs.map((tab) => (
              <div
                key={tab.path}
                // Inactive tabs stay laid out (visibility, not display:none) so widgets
                // that measure on mount — e.g. @hello-pangea/dnd boards — keep working,
                // and each tab holds its own scroll position.
                className="absolute inset-0 overflow-auto"
                style={{ visibility: tab.path === pane.activePath ? "visible" : "hidden" }}
              >
                <ContentPane
                  path={tab.path}
                  viewport={viewport}
                  theme={resolvedTheme}
                  profile={profile}
                  onAction={onCustomAction}
                  registry={liveRegistry.current}
                  skeletonBg={skeletonBg}
                />
              </div>
            ))
          )}
          {/* Drag overlay over the body, live only while a tab is being dragged. The body's
              content can be a React portal (the onno-list grid mounts via createPortal into
              its <onno-list> host), and a portal's events bubble to its React parent — not to
              this <section>. So a tab dragged over the list never reaches the island's own
              onDragOver/onDrop, and the drop is silently rejected (no split). This overlay
              lives in the section's own subtree and sits above the content, so it catches the
              drag for every surface type and routes it to the same split/move handlers. */}
          {dragState ? (
            <div
              data-testid="tab-drag-overlay"
              className="absolute inset-0 z-10"
              onDragOver={(e) => {
                if (!dragRef.current) return;
                e.preventDefault();
                e.stopPropagation();
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
                e.stopPropagation();
                onDrop(pane.id, dropModeAt(e.clientX, e.currentTarget.getBoundingClientRect()));
              }}
            />
          ) : null}
        </div>

        {/* drop hints while dragging a tab over the body (append / split) */}
        {isDropInto ? (
          <div
            className="pointer-events-none absolute inset-0 rounded-card"
            style={{ background: `${accent}14`, boxShadow: `inset 0 0 0 2px ${accent}66` }}
          />
        ) : null}
        {isDropRight ? (
          <div
            className="pointer-events-none absolute inset-y-0 right-0 w-1/3 rounded-r-2xl"
            style={{ background: `${accent}1f`, boxShadow: `inset 0 0 0 2px ${accent}80` }}
          />
        ) : null}
        {isDropLeft ? (
          <div
            className="pointer-events-none absolute inset-y-0 left-0 w-1/3 rounded-l-2xl"
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
            className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden rounded-card border"
            style={{ background: surfaceBg, borderColor }}
          >
            {navEl}
          </div>
          <NotificationTrigger style={{ background: surfaceBg, borderColor }} />
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
                  <div className="h-10 w-1 rounded-control bg-border transition-colors group-hover:bg-blue-500" />
                </div>
              ) : null}
            </div>
          ))}
        </div>
        {rowMenuEl}
        {tabMenuEl}
        {confirmEl}
      </div>
    );
  }

  if (navStyle === "bottom_bar") {
    // Full-width centered on small phones; a compact corner island on tablets and
    // large phones (pinned bottom-right). Account lives on the /account page here.
    return (
      <div className="min-h-screen w-full overflow-x-hidden" style={{ background: pageBg }}>
        <div className="pb-24">{plainContent}</div>
        <nav className="fixed inset-x-0 bottom-0 z-10">
          <div className={viewport === "tablet" ? "ml-auto w-fit" : "mx-auto max-w-md"}>{navEl}</div>
        </nav>
        {rowMenuEl}
        {confirmEl}
      </div>
    );
  }

  return (
    <div className="min-h-screen w-full overflow-x-hidden" style={{ background: pageBg }}>
      {navEl}
      {plainContent}
      {rowMenuEl}
        {confirmEl}
    </div>
  );
}
