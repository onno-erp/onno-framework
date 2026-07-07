import { useEffect } from "react";
import { useSyncExternalStore } from "react";
import { api, type PresenceViewer } from "@/lib/api";
import type { UiEvent } from "@/lib/types";

/**
 * The ambient-presence store: one client-wide picture of who is viewing which record, so the tab bar,
 * list rows, and sidebar can all show collaborator avatars from a single source.
 *
 * Seeded once from `GET /api/presence`, then kept current by the `presence` deltas on the shared
 * `onno:dataevent` SSE fan-out (divkit-view's single stream). Keyed by record id (a uuid, globally
 * unique) so the tab and rows need no name-matching; each entry also carries the route `kind`/`name`
 * for the sidebar's per-entity aggregation. The selectors hide your own marker only on the route THIS
 * tab is itself on (`myRouteId`) — so you never see yourself on the page you're looking at, but your
 * other tabs/devices (on other routes) still show.
 */

type Entry = { kind: string; name: string; viewers: PresenceViewer[] };

let byId = new Map<string, Entry>();
let you: string | null = null;
let myRouteId: string | null = null;
let started = false;
const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}
function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}
function getSnapshot(): Map<string, Entry> {
  return byId;
}

/**
 * Whether a viewer should be shown on a given record entry. Everyone shows EXCEPT yourself on the route
 * THIS tab is itself present on ({@code myRouteId}) — so you never see your own marker on the page you're
 * looking at, while your other tabs/devices (on other routes) and everyone else stay visible.
 */
function visible(entryId: string, v: PresenceViewer): boolean {
  return !(you !== null && v.userId === you && entryId === myRouteId);
}

/** The store id a route path keys on — the record id for a record route, else the normalized path
 *  (mirrors the server's routeIdentity), so a pane can mark which entry is "this tab's own". */
function routeStoreId(path: string): string {
  const seg = path.split("/").filter(Boolean);
  if (seg.length >= 3 && (seg[0] === "catalogs" || seg[0] === "documents")) return seg[2];
  return "/" + seg.join("/");
}

/** Record (or clear) which route this tab is itself present on, re-rendering the presence surfaces. */
function setMyRoute(id: string | null) {
  if (myRouteId === id) return;
  myRouteId = id;
  byId = new Map(byId); // new ref so useSyncExternalStore recomputes against the new myRouteId
  emit();
}

/**
 * Optimistically drop yourself from a route's viewer set the instant you leave it — without waiting for
 * the server's leave to round-trip back as an SSE delta. Otherwise, as the route stops being "yours"
 * (so your marker un-hides) you briefly flash in your own old position before the delta clears you.
 */
function removeSelfFrom(routeId: string) {
  if (you === null) return;
  const entry = byId.get(routeId);
  if (!entry) return;
  const remaining = entry.viewers.filter((v) => v.userId !== you);
  if (remaining.length === entry.viewers.length) return; // you weren't in this entry
  const next = new Map(byId);
  if (remaining.length) next.set(routeId, { ...entry, viewers: remaining });
  else next.delete(routeId);
  byId = next;
  emit();
}

// Mirror of the server's snake-casing, so a route name matches an entity name across cases.
function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

function applyEvent(ev: UiEvent | undefined) {
  if (!ev || ev.type !== "presence" || !ev.id) return;
  const viewers = ev.viewers ?? [];
  const next = new Map(byId);
  if (viewers.length === 0) {
    if (!next.has(ev.id)) return;
    next.delete(ev.id);
  } else {
    next.set(ev.id, { kind: ev.kind ?? "", name: ev.entityName ?? "", viewers });
  }
  byId = next;
  emit();
}

const RESYNC_MS = 20_000;

let resyncTimer: number | undefined;

/** (Re)load the authoritative snapshot into the store. */
function loadSnapshot(): Promise<void> {
  return api
    .getPresenceSnapshot()
    .then((snap) => {
      you = snap.you || null;
      const m = new Map<string, Entry>();
      for (const r of snap.records) m.set(r.id, { kind: r.kind, name: r.name, viewers: r.viewers });
      byId = m;
      emit();
    })
    .catch((err: unknown) => {
      // Session lapsed: getPresenceSnapshot already handed off to auth recovery (redirect/re-auth).
      // Stop the periodic re-sync — this interval is module-global and outlives the app unmount, so
      // left running it would re-hit /api/presence every 20s forever and flood the console with 401s.
      // A fresh startPresence() after re-auth restarts it. Other errors (offline blip) keep polling.
      if ((err as { status?: number })?.status === 401) {
        if (resyncTimer) window.clearInterval(resyncTimer);
        resyncTimer = undefined;
        started = false;
      }
      /* offline / not yet authed — live deltas will still populate the store */
    });
}

/** Start the store: load the snapshot and subscribe to live presence deltas. Idempotent. */
export function startPresence() {
  if (started) return;
  started = true;
  loadSnapshot();
  window.addEventListener("onno:dataevent", (e) => applyEvent((e as CustomEvent<UiEvent>).detail));
  // SSE deltas fire only on join/leave, never on heartbeats — so a tab that missed one (loaded mid-
  // session, was backgrounded, or had an SSE hiccup) wouldn't see another session until it next moved.
  // Re-seed from the authoritative snapshot on a slow timer and whenever the tab regains focus, so every
  // session — including your own other tabs — converges without waiting for a move.
  resyncTimer = window.setInterval(loadSnapshot, RESYNC_MS);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") loadSnapshot();
  });
}

const EMPTY: PresenceViewer[] = [];

/** The people viewing a specific record — hides only your own marker when this is the route this tab is
 *  on, so your other sessions still show. Tab bar + list rows. */
export function useRecordViewers(id: string | null | undefined): PresenceViewer[] {
  const map = useSyncExternalStore(subscribe, getSnapshot);
  if (!id) return EMPTY;
  const entry = map.get(id);
  if (!entry) return EMPTY;
  const out = entry.viewers.filter((v) => visible(id, v));
  return out.length ? out : EMPTY;
}

/**
 * A by-id map of every record's viewers (your own marker hidden on the route this tab is on), for surfaces
 * that render many records at once and can't call a hook per row — the list looks up {@code map.get(rowId)}.
 * Recomputed only when the store changes.
 */
export function useViewersById(): Map<string, PresenceViewer[]> {
  const map = useSyncExternalStore(subscribe, getSnapshot);
  const out = new Map<string, PresenceViewer[]>();
  for (const [id, entry] of map) {
    const shown = entry.viewers.filter((v) => visible(id, v));
    if (shown.length) out.set(id, shown);
  }
  return out;
}

/** The distinct OTHER people viewing any record of one entity — for the sidebar. Unlike the record
 *  selectors, this hides your own marker on every route, not just the one this tab is on: the nav is
 *  about where collaborators are, and your own face trailing your other panes/tabs down the rail is
 *  pure noise (you know where you are). */
export function useEntityViewers(kind: string, name: string): PresenceViewer[] {
  const map = useSyncExternalStore(subscribe, getSnapshot);
  const want = toSnake(name);
  const seen = new Set<string>();
  const out: PresenceViewer[] = [];
  for (const [, entry] of map) {
    if (entry.kind !== kind || toSnake(entry.name) !== want) continue;
    for (const v of entry.viewers) {
      if (v.userId !== you && !seen.has(v.userId)) {
        seen.add(v.userId);
        out.push(v);
      }
    }
  }
  return out.length ? out : EMPTY;
}

const HEARTBEAT_MS = 15_000;

/**
 * Drive presence for one pane from its route: enter on open, heartbeat while open, leave on close or when
 * the pane navigates elsewhere. Mounted once per pane and posts for EVERY route — a record, an entity list,
 * or any page (dashboards/custom pages); the server derives the presence identity from the path. The viewer
 * data itself comes back through the store (snapshot + SSE), so this only owns the lifecycle.
 */
export function usePanePresence(path: string) {
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
    api.presence(path, "enter").catch(() => {});
    const beat = window.setInterval(() => {
      if (active) api.presence(path, "heartbeat").catch(() => {});
    }, HEARTBEAT_MS);
    // Closing the browser tab (or navigating the whole document away) does NOT run React cleanup, so the
    // unmount-time leave below never fires — the viewer would linger until the server TTL reaps them.
    // `pagehide` is the reliable teardown signal (tab close, navigation, bfcache); the keepalive leave
    // request outlives the unloading document. TTL still backstops a crash / lost network.
    const onPageHide = () => {
      active = false;
      api.leavePresence(path);
    };
    window.addEventListener("pagehide", onPageHide);
    return () => {
      active = false;
      window.clearInterval(beat);
      window.removeEventListener("pagehide", onPageHide);
      removeSelfFrom(myId); // optimistically drop self from the route we're leaving (no flash)
      // Don't clear myRouteId here — on a path change the next effect sets the new route directly, so it
      // never passes through null (the !path branch above clears it when there is genuinely no focused route).
      api.leavePresence(path);
    };
  }, [path]);
}
