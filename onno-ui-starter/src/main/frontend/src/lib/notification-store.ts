import { useSyncExternalStore } from "react";
import { api, ApiError, type NotificationView } from "@/lib/api";
import type { UiEvent } from "@/lib/types";

/**
 * The per-user notification store: one client-wide timeline of updates concerning the signed-in user,
 * feeding the top-right bell (unread badge) and its drawer panel from a single source.
 *
 * Seeded once from `GET /api/notifications`, then kept current by the `notification` deltas on the shared
 * `onno:dataevent` SSE fan-out (divkit-view's single stream). A delta normally carries the whole row, so
 * it is prepended optimistically with no refetch; a peer-node delta trimmed to fit the cluster payload
 * cap arrives without a title, which just triggers a first-page refetch. When the feature is disabled the
 * seed request 404s and the store marks itself unavailable so the bell hides.
 */

/** The panel's status tab (all vs unread only) and source filter ("all", or any notification type). */
export type StatusFilter = "all" | "unread";
// A notification type string, or "all". Not a closed union — types are whatever producers emit, so the
// panel's tabs are driven by the `types` the server reports (see below), not a hardcoded list.
export type TypeFilter = string;

type State = {
  items: NotificationView[];
  unreadCount: number;
  nextCursor: string | null;
  hasMore: boolean;
  available: boolean;
  // The distinct notification types the user has, most-recent-first — the panel renders one filter tab
  // per type. Modular by construction: a custom producer's type shows a tab with no config, and a type
  // nobody produces anymore drops out. Kept in sync from the feed + unioned with live deltas.
  types: string[];
  // UI state shared between the sidebar trigger and the slide-over panel.
  panelOpen: boolean;
  statusFilter: StatusFilter;
  typeFilter: TypeFilter;
  // How many sidebar/topbar triggers are mounted, so the floating fallback trigger only shows when a
  // layout (mobile/topbar) provides none.
  triggerCount: number;
  // The shell's nav style, reported by the host. The floating fallback bell only appears on the
  // topbar layout — bottom-bar layouts (mobile/tablet) reach notifications via the More menu's
  // row instead, so no floating chrome overlaps the content or the bar.
  navStyle: "sidebar" | "topbar" | "bottom_bar" | "unknown";
};

let state: State = {
  items: [],
  unreadCount: 0,
  nextCursor: null,
  hasMore: false,
  available: true,
  types: [],
  panelOpen: false,
  statusFilter: "all",
  typeFilter: "all",
  triggerCount: 0,
  navStyle: "unknown",
};
let started = false;
const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}
function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}
function getSnapshot(): State {
  return state;
}
function set(next: Partial<State>) {
  state = { ...state, ...next };
  emit();
}

/** (Re)load the newest page as the authoritative head of the timeline. */
function loadFirstPage(): Promise<void> {
  return api
    .getNotifications()
    .then((page) => {
      const types = page.types ?? [];
      set({
        items: page.items,
        unreadCount: page.unreadCount,
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        available: true,
        types,
        // Drop back to "all" if the active tab's type no longer exists (e.g. its source was disabled).
        typeFilter: state.typeFilter === "all" || types.includes(state.typeFilter) ? state.typeFilter : "all",
      });
    })
    .catch((e) => {
      // 404 → the notifications feature is disabled server-side; hide the bell. Any other error
      // (offline, lapsed session) leaves the store as-is; a live delta or the next open retries.
      if (e instanceof ApiError && e.status === 404) set({ available: false });
    });
}

/** Fetch the next older window and append it (the panel's infinite scroll). */
export function loadMoreNotifications(): Promise<void> {
  if (!state.hasMore || !state.nextCursor) return Promise.resolve();
  const cursor = state.nextCursor;
  return api
    .getNotifications({ cursor })
    .then((page) => {
      // Drop any id already present (a live delta may have prepended it) before appending.
      const have = new Set(state.items.map((n) => n.id));
      const fresh = page.items.filter((n) => !have.has(n.id));
      set({
        items: [...state.items, ...fresh],
        unreadCount: page.unreadCount,
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
      });
    })
    .catch(() => {});
}

let audioContext: AudioContext | null = null;
function playChime() {
  const AudioContextCtor =
    window.AudioContext ||
    (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AudioContextCtor) return;
  try {
    audioContext ??= new AudioContextCtor();
    const now = audioContext.currentTime;
    const gain = audioContext.createGain();
    const tone = audioContext.createOscillator();
    tone.type = "sine";
    tone.frequency.setValueAtTime(880, now);
    tone.frequency.exponentialRampToValueAtTime(1320, now + 0.08);
    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.exponentialRampToValueAtTime(0.08, now + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.18);
    tone.connect(gain).connect(audioContext.destination);
    tone.start(now);
    tone.stop(now + 0.2);
  } catch {
    // Browsers can block audio until the first user gesture; silent is fine.
  }
}

function applyEvent(ev: UiEvent | undefined) {
  if (!ev || ev.type !== "notification" || !ev.id) return;
  set({ available: true });
  // A trimmed peer-node delta (no title) can't be rendered from itself — refetch the head instead.
  if (!ev.title) {
    loadFirstPage();
    playChime();
    return;
  }
  if (state.items.some((n) => n.id === ev.id)) return; // already have it (e.g. from a concurrent refetch)
  playChime();
  const type = ev.notificationType ?? "info";
  const row: NotificationView = {
    id: ev.id,
    type,
    title: ev.title,
    body: ev.body ?? null,
    link: ev.link ?? null,
    actorName: ev.actorName ?? null,
    actorAvatar: ev.actorAvatar ?? null,
    createdAt: ev.createdAt ?? ev.timestamp ?? new Date().toISOString(),
    readAt: null,
    unread: true,
  };
  set({
    items: [row, ...state.items],
    unreadCount: state.unreadCount + 1,
    // A brand-new type gets its tab immediately (prepended = most recent), no wait for a refetch.
    types: state.types.includes(type) ? state.types : [type, ...state.types],
  });
}

/** Mark one notification read (optimistically), reconciling the badge from the server's fresh total. */
export function markNotificationRead(id: string): Promise<void> {
  const target = state.items.find((n) => n.id === id);
  if (!target || !target.unread) return Promise.resolve();
  set({
    items: state.items.map((n) => (n.id === id ? { ...n, unread: false, readAt: new Date().toISOString() } : n)),
    unreadCount: Math.max(0, state.unreadCount - 1),
  });
  return api
    .markNotificationRead(id)
    .then((r) => set({ unreadCount: r.unreadCount }))
    .catch(() => {});
}

/** Mark every notification read (optimistically), reconciling the badge from the server's fresh total. */
export function markAllNotificationsRead(): Promise<void> {
  if (state.unreadCount === 0) return Promise.resolve();
  const now = new Date().toISOString();
  set({
    items: state.items.map((n) => (n.unread ? { ...n, unread: false, readAt: now } : n)),
    unreadCount: 0,
  });
  return api
    .markAllNotificationsRead()
    .then((r) => set({ unreadCount: r.unreadCount }))
    .catch(() => {});
}

// --- panel + filter UI actions ---------------------------------------------------------------

export function openPanel() {
  if (!state.panelOpen) set({ panelOpen: true });
}
export function closePanel() {
  if (state.panelOpen) set({ panelOpen: false });
}
export function togglePanel() {
  set({ panelOpen: !state.panelOpen });
}
export function setStatusFilter(statusFilter: StatusFilter) {
  set({ statusFilter });
}
export function setTypeFilter(typeFilter: TypeFilter) {
  set({ typeFilter });
}

/** The host reports the shell's nav style so the fallback bell knows whether to show. */
export function setNotificationsNavStyle(navStyle: State["navStyle"]) {
  if (state.navStyle !== navStyle) set({ navStyle });
}

/** A mounted sidebar/topbar trigger registers so the floating fallback hides itself. */
export function registerTrigger(): () => void {
  set({ triggerCount: state.triggerCount + 1 });
  return () => set({ triggerCount: Math.max(0, state.triggerCount - 1) });
}

/** The items after the active status + type filters — what the panel renders. */
export function filteredItems(s: State): NotificationView[] {
  return s.items.filter(
    (n) =>
      (s.statusFilter === "all" || n.unread) &&
      (s.typeFilter === "all" || n.type === s.typeFilter)
  );
}

/** Start the store: load the head and subscribe to live notification deltas. Idempotent. */
export function startNotifications() {
  if (started) return;
  started = true;
  loadFirstPage();
  window.addEventListener("onno:dataevent", (e) => applyEvent((e as CustomEvent<UiEvent>).detail));
}

/** The whole notification store, for the bell + panel. */
export function useNotifications(): State {
  return useSyncExternalStore(subscribe, getSnapshot);
}
