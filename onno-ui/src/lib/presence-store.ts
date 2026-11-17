import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, type PresenceViewer } from "./api";
import { useUiEvents } from "../hooks/use-ui-events";
import type { UiEvent } from "./types";

/**
 * Ambient presence: one picture of who is viewing which record, so the tab bar, list rows, and
 * sidebar can all show collaborator avatars from a single source. {@link PresenceProvider} holds it
 * for the authenticated app; the hooks below read it.
 *
 * Seeded from `GET /api/presence`, then kept current by the `presence` events on the shell's live
 * stream. Keyed by record id (a uuid, globally unique) so the tab and rows need no name-matching;
 * each entry also carries the route `kind`/`name` for the sidebar's per-entity aggregation. The
 * selectors hide your own marker only on the route THIS tab is itself on (`myRouteId`) — so you never
 * see yourself on the page you're looking at, but your other tabs/devices (on other routes) still show.
 */

type Entry = { kind: string; name: string; viewers: PresenceViewer[] };

type Presence = {
  byId: Map<string, Entry>;
  /** The signed-in user's id, once the snapshot says. */
  you: string | null;
  /** The store id of the route this tab is itself present on. */
  myRouteId: string | null;
  setMyRoute: (id: string | null) => void;
  removeSelfFrom: (routeId: string) => void;
};

/** Outside a provider: nobody is viewing anything. */
const PresenceContext = createContext<Presence>({
  byId: new Map(),
  you: null,
  myRouteId: null,
  setMyRoute: () => {},
  removeSelfFrom: () => {},
});

/**
 * Whether a viewer should be shown on a given record entry. Everyone shows EXCEPT yourself on the route
 * THIS tab is itself present on ({@code myRouteId}) — so you never see your own marker on the page you're
 * looking at, while your other tabs/devices (on other routes) and everyone else stay visible.
 */
function visible(p: Presence, entryId: string, v: PresenceViewer): boolean {
  return !(p.you !== null && v.userId === p.you && entryId === p.myRouteId);
}

/** The store id a route path keys on — the record id for a record route, else the normalized path
 *  (mirrors the server's routeIdentity), so a pane can mark which entry is "this tab's own". */
function routeStoreId(path: string): string {
  const seg = path.split("/").filter(Boolean);
  if (seg.length >= 3 && (seg[0] === "catalogs" || seg[0] === "documents")) return seg[2];
  return "/" + seg.join("/");
}

// Mirror of the server's snake-casing, so a route name matches an entity name across cases.
function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

const RESYNC_MS = 20_000;

/** Holds ambient presence for everything below it (the authenticated app). */
export function PresenceProvider({ children }: { children: ReactNode }) {
  const [byId, setById] = useState<Map<string, Entry>>(() => new Map());
  const [you, setYou] = useState<string | null>(null);
  const [myRouteId, setMyRouteId] = useState<string | null>(null);
  // Read when leaving a route, so the leave callback stays stable as the snapshot names you.
  const youRef = useRef(you);
  youRef.current = you;

  useEffect(() => {
    let stopped = false;
    let timer: number | undefined;
    // (Re)load the authoritative snapshot.
    const load = () => api
      .getPresenceSnapshot()
      .then((snap) => {
        if (stopped) return;
        setYou(snap.you || null);
        const m = new Map<string, Entry>();
        for (const r of snap.records) m.set(r.id, { kind: r.kind, name: r.name, viewers: r.viewers });
        setById(m);
      })
      .catch((err: unknown) => {
        // Session lapsed: getPresenceSnapshot already handed off to auth recovery (redirect/re-auth).
        // Stop the periodic re-sync so it doesn't re-hit /api/presence every 20s with 401s; the
        // provider remounts after re-auth. Other errors (offline blip) keep polling.
        if ((err as { status?: number })?.status === 401) window.clearInterval(timer);
      });
    void load();
    // Live events fire only on join/leave, never on heartbeats — so a tab that missed one (loaded mid-
    // session, was backgrounded, or had a stream hiccup) wouldn't see another session until it next
    // moved. Re-seed from the snapshot on a slow timer and whenever the tab regains focus, so every
    // session — including your own other tabs — converges without waiting for a move.
    timer = window.setInterval(load, RESYNC_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible") void load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, []);

  useUiEvents((ev: UiEvent) => {
    if (!ev.id) return;
    const id = ev.id;
    const viewers = ev.viewers ?? [];
    setById((current) => {
      if (viewers.length === 0 && !current.has(id)) return current;
      const next = new Map(current);
      if (viewers.length === 0) next.delete(id);
      else next.set(id, { kind: ev.kind ?? "", name: ev.entityName ?? "", viewers });
      return next;
    });
  }, { types: ["presence"] });

  /** Record (or clear) which route this tab is itself present on. */
  const setMyRoute = useCallback((id: string | null) => setMyRouteId(id), []);

  /**
   * Optimistically drop yourself from a route's viewer set the instant you leave it — without waiting
   * for the server's leave to round-trip back as an event. Otherwise, as the route stops being "yours"
   * (so your marker un-hides) you briefly flash in your own old position before the event clears you.
   */
  const removeSelfFrom = useCallback((routeId: string) => {
    const you = youRef.current;
    if (you === null) return;
    setById((current) => {
      const entry = current.get(routeId);
      if (!entry) return current;
      const remaining = entry.viewers.filter((v) => v.userId !== you);
      if (remaining.length === entry.viewers.length) return current; // you weren't in this entry
      const next = new Map(current);
      if (remaining.length) next.set(routeId, { ...entry, viewers: remaining });
      else next.delete(routeId);
      return next;
    });
  }, []);

  const value = useMemo<Presence>(() => ({ byId, you, myRouteId, setMyRoute, removeSelfFrom }),
    [byId, you, myRouteId, setMyRoute, removeSelfFrom]);
  return createElement(PresenceContext.Provider, { value }, children);
}

const EMPTY: PresenceViewer[] = [];

/** The people viewing a specific record — hides only your own marker when this is the route this tab is
 *  on, so your other sessions still show. Tab bar + list rows. */
export function useRecordViewers(id: string | null | undefined): PresenceViewer[] {
  const p = useContext(PresenceContext);
  return useMemo(() => {
    const entry = id ? p.byId.get(id) : undefined;
    if (!id || !entry) return EMPTY;
    const out = entry.viewers.filter((v) => visible(p, id, v));
    return out.length ? out : EMPTY;
  }, [p, id]);
}

/**
 * A by-id map of every record's viewers (your own marker hidden on the route this tab is on), for surfaces
 * that render many records at once and can't call a hook per row — the list looks up {@code map.get(rowId)}.
 */
export function useViewersById(): Map<string, PresenceViewer[]> {
  const p = useContext(PresenceContext);
  return useMemo(() => {
    const out = new Map<string, PresenceViewer[]>();
    for (const [id, entry] of p.byId) {
      const shown = entry.viewers.filter((v) => visible(p, id, v));
      if (shown.length) out.set(id, shown);
    }
    return out;
  }, [p]);
}

/** The distinct OTHER people viewing any record of one entity — for the sidebar. Unlike the record
 *  selectors, this hides your own marker on every route, not just the one this tab is on: the nav is
 *  about where collaborators are, and your own face trailing your other panes/tabs down the rail is
 *  pure noise (you know where you are). */
export function useEntityViewers(kind: string, name: string): PresenceViewer[] {
  const { byId, you } = useContext(PresenceContext);
  return useMemo(() => {
    const want = toSnake(name);
    const seen = new Set<string>();
    const out: PresenceViewer[] = [];
    for (const [, entry] of byId) {
      if (entry.kind !== kind || toSnake(entry.name) !== want) continue;
      for (const v of entry.viewers) {
        if (v.userId !== you && !seen.has(v.userId)) {
          seen.add(v.userId);
          out.push(v);
        }
      }
    }
    return out.length ? out : EMPTY;
  }, [byId, you, kind, name]);
}

const HEARTBEAT_MS = 15_000;

/**
 * Drive presence for one pane from its route: enter on open, heartbeat while open, leave on close or when
 * the pane navigates elsewhere. Mounted once per pane and posts for EVERY route — a record, an entity list,
 * or any page (dashboards/custom pages); the server derives the presence identity from the path. The viewer
 * data itself comes back through the provider (snapshot + live events), so this only owns the lifecycle.
 */
export function usePanePresence(path: string) {
  const { setMyRoute, removeSelfFrom } = useContext(PresenceContext);
  useEffect(() => {
    if (!path) {
      setMyRoute(null); // no focused route → nothing is "this tab's own"
      return;
    }
    // Mark which store entry is "this tab's own", so the selectors hide your marker here (but not on
    // your other tabs/devices, which are on other routes).
    const myId = routeStoreId(path);
    setMyRoute(myId);
    let active = true;
    let denied = false;
    const onFailure = (error: unknown) => {
      if ((error as { status?: number })?.status === 403) denied = true;
    };
    api.presence(path, "enter").catch(onFailure);
    const beat = window.setInterval(() => {
      if (active && !denied) api.presence(path, "heartbeat").catch(onFailure);
    }, HEARTBEAT_MS);
    // Closing the browser tab (or navigating the whole document away) does NOT run React cleanup, so the
    // unmount-time leave below never fires — the viewer would linger until the server TTL reaps them.
    // `pagehide` is the reliable teardown signal (tab close, navigation, bfcache); the keepalive leave
    // request outlives the unloading document. TTL still backstops a crash / lost network.
    const onPageHide = () => {
      active = false;
      if (!denied) api.leavePresence(path);
    };
    window.addEventListener("pagehide", onPageHide);
    return () => {
      active = false;
      window.clearInterval(beat);
      window.removeEventListener("pagehide", onPageHide);
      removeSelfFrom(myId); // optimistically drop self from the route we're leaving (no flash)
      // Don't clear myRouteId here — on a path change the next effect sets the new route directly, so it
      // never passes through null (the !path branch above clears it when there is genuinely no focused route).
      if (!denied) api.leavePresence(path);
    };
  }, [path, setMyRoute, removeSelfFrom]);
}
