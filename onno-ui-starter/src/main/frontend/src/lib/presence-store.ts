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
 * for the sidebar's per-entity aggregation. Self is filtered out by the selectors via `you`.
 */

type Entry = { kind: string; name: string; viewers: PresenceViewer[] };

let byId = new Map<string, Entry>();
let you: string | null = null;
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

/** Start the store: load the snapshot and subscribe to live presence deltas. Idempotent. */
export function startPresence() {
  if (started) return;
  started = true;
  api
    .getPresenceSnapshot()
    .then((snap) => {
      you = snap.you || null;
      const m = new Map<string, Entry>();
      for (const r of snap.records) m.set(r.id, { kind: r.kind, name: r.name, viewers: r.viewers });
      byId = m;
      emit();
    })
    .catch(() => {
      /* offline / not yet authed — live deltas will still populate the store */
    });
  window.addEventListener("onno:dataevent", (e) => applyEvent((e as CustomEvent<UiEvent>).detail));
}

const EMPTY: PresenceViewer[] = [];

/** The other people viewing a specific record (excludes you). For the tab bar and list rows. */
export function useRecordViewers(id: string | null | undefined): PresenceViewer[] {
  const map = useSyncExternalStore(subscribe, getSnapshot);
  if (!id) return EMPTY;
  const entry = map.get(id);
  if (!entry) return EMPTY;
  const others = entry.viewers.filter((v) => v.userId !== you);
  return others.length ? others : EMPTY;
}

/**
 * A by-id map of every record's other viewers (excludes you), for surfaces that render many records at
 * once and can't call a hook per row — the list looks up {@code map.get(rowId)}. Recomputed only when the
 * store changes.
 */
export function useViewersById(): Map<string, PresenceViewer[]> {
  const map = useSyncExternalStore(subscribe, getSnapshot);
  const out = new Map<string, PresenceViewer[]>();
  for (const [id, entry] of map) {
    const others = entry.viewers.filter((v) => v.userId !== you);
    if (others.length) out.set(id, others);
  }
  return out;
}

/** The distinct other people viewing any record of one entity (excludes you). For the sidebar. */
export function useEntityViewers(kind: string, name: string): PresenceViewer[] {
  const map = useSyncExternalStore(subscribe, getSnapshot);
  const want = toSnake(name);
  const seen = new Set<string>();
  const out: PresenceViewer[] = [];
  for (const entry of map.values()) {
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
    if (!path) return;
    let active = true;
    api.presence(path, "enter").catch(() => {});
    const beat = window.setInterval(() => {
      if (active) api.presence(path, "heartbeat").catch(() => {});
    }, HEARTBEAT_MS);
    return () => {
      active = false;
      window.clearInterval(beat);
      api.leavePresence(path);
    };
  }, [path]);
}
