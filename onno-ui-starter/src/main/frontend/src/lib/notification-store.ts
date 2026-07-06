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

/** The panel's status tab (all vs unread only) and source filter (all types / one type). */
export type StatusFilter = "all" | "unread";
export type TypeFilter = "all" | "mention" | "assignment";

type State = {
  items: NotificationView[];
  unreadCount: number;
  nextCursor: string | null;
  hasMore: boolean;
  available: boolean;
  // UI state shared between the sidebar trigger and the slide-over panel.
  panelOpen: boolean;
  statusFilter: StatusFilter;
  typeFilter: TypeFilter;
  // How many sidebar/topbar triggers are mounted, so the floating fallback trigger only shows when a
  // layout (mobile/topbar) provides none.
  triggerCount: number;
};

let state: State = {
  items: [],
  unreadCount: 0,
  nextCursor: null,
  hasMore: false,
  available: true,
  panelOpen: false,
  statusFilter: "all",
  typeFilter: "all",
  triggerCount: 0,
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
      set({
        items: page.items,
        unreadCount: page.unreadCount,
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        available: true,
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

function applyEvent(ev: UiEvent | undefined) {
  if (!ev || ev.type !== "notification" || !ev.id) return;
  set({ available: true });
  // A trimmed peer-node delta (no title) can't be rendered from itself — refetch the head instead.
  if (!ev.title) {
    loadFirstPage();
    return;
  }
  if (state.items.some((n) => n.id === ev.id)) return; // already have it (e.g. from a concurrent refetch)
  const row: NotificationView = {
    id: ev.id,
    type: ev.notificationType ?? "info",
    title: ev.title,
    body: ev.body ?? null,
    link: ev.link ?? null,
    actorName: ev.actorName ?? null,
    actorAvatar: ev.actorAvatar ?? null,
    createdAt: ev.createdAt ?? ev.timestamp ?? new Date().toISOString(),
    readAt: null,
    unread: true,
  };
  set({ items: [row, ...state.items], unreadCount: state.unreadCount + 1 });
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
