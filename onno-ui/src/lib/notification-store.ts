import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, ApiError, type NotificationView } from "./api";
import { useUiEvents } from "../hooks/use-ui-events";
import type { UiEvent } from "./types";

/**
 * The signed-in user's notifications: one timeline of updates concerning them, feeding the bell
 * (unread badge) and its drawer panel. {@link NotificationsProvider} holds it for the authenticated
 * app; components read it with {@link useNotifications}.
 *
 * Seeded from `GET /api/notifications`, then kept current by the `notification` events on the
 * shell's live stream. An event normally carries the whole row, so it is prepended with no
 * refetch; a peer-node event trimmed to fit the cluster payload cap arrives without a title, which
 * just triggers a first-page refetch. When the feature is disabled the seed request 404s and the
 * timeline marks itself unavailable so the bell hides.
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
  // nobody produces anymore drops out. Kept in sync from the feed + unioned with live events.
  types: string[];
  // UI state shared between the sidebar trigger and the slide-over panel.
  panelOpen: boolean;
  statusFilter: StatusFilter;
  typeFilter: TypeFilter;
};

/** The timeline plus what can be done with it. */
export type Notifications = State & {
  /** Fetch the next older window and append it (the panel's infinite scroll). */
  loadMore: () => Promise<void>;
  /** Mark one notification read (optimistically), reconciling the badge from the server's total. */
  markRead: (id: string) => Promise<void>;
  /** Mark every notification read (optimistically), reconciling the badge from the server's total. */
  markAllRead: () => Promise<void>;
  openPanel: () => void;
  closePanel: () => void;
  setStatusFilter: (filter: StatusFilter) => void;
  setTypeFilter: (filter: TypeFilter) => void;
};

const INITIAL: State = {
  items: [],
  unreadCount: 0,
  nextCursor: null,
  hasMore: false,
  available: true,
  types: [],
  panelOpen: false,
  statusFilter: "all",
  typeFilter: "all",
};

const noop = () => {};
const resolved = () => Promise.resolve();

/** Outside a provider (a component rendered on its own): an empty, inert timeline. */
const NotificationsContext = createContext<Notifications>({
  ...INITIAL,
  available: false,
  loadMore: resolved,
  markRead: resolved,
  markAllRead: resolved,
  openPanel: noop,
  closePanel: noop,
  setStatusFilter: noop,
  setTypeFilter: noop,
});

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

/** The items after the active status + type filters — what the panel renders. */
export function filteredItems(s: State): NotificationView[] {
  return s.items.filter(
    (n) =>
      (s.statusFilter === "all" || n.unread) &&
      (s.typeFilter === "all" || n.type === s.typeFilter)
  );
}

/** The signed-in user's notification timeline, for the bell and the panel. */
export function useNotifications(): Notifications {
  return useContext(NotificationsContext);
}

/** Holds the signed-in user's notifications for everything below it (the authenticated app). */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>(INITIAL);
  const stateRef = useRef(state);
  stateRef.current = state;
  const set = useCallback((next: Partial<State> | ((s: State) => Partial<State>)) =>
    setState((s) => ({ ...s, ...(typeof next === "function" ? next(s) : next) })), []);

  /** (Re)load the newest page as the authoritative head of the timeline. */
  const loadFirstPage = useCallback(() => api
    .getNotifications()
    .then((page) => {
      const types = page.types ?? [];
      set((s) => ({
        items: page.rows ?? page.items,
        unreadCount: page.unreadCount,
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        available: true,
        types,
        // Drop back to "all" if the active tab's type no longer exists (e.g. its source was disabled).
        typeFilter: s.typeFilter === "all" || types.includes(s.typeFilter) ? s.typeFilter : "all",
      }));
    })
    .catch((e) => {
      // 404 → the notifications feature is disabled server-side; hide the bell. Any other error
      // (offline, lapsed session) leaves the timeline as-is; a live event or the next open retries.
      if (e instanceof ApiError && e.status === 404) set({ available: false });
    }), [set]);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage]);

  useUiEvents((ev: UiEvent) => {
    if (!ev.id) return;
    // A trimmed peer-node event (no title) can't be rendered from itself — refetch the head instead.
    if (!ev.title) {
      set({ available: true });
      void loadFirstPage();
      playChime();
      return;
    }
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
    // Already have it (e.g. from a concurrent refetch).
    if (stateRef.current.items.some((n) => n.id === row.id)) {
      set({ available: true });
      return;
    }
    playChime();
    set((s) => {
      if (s.items.some((n) => n.id === row.id)) return { available: true };
      return {
        available: true,
        items: [row, ...s.items],
        unreadCount: s.unreadCount + 1,
        // A brand-new type gets its tab immediately (prepended = most recent), no wait for a refetch.
        types: s.types.includes(type) ? s.types : [type, ...s.types],
      };
    });
  }, { types: ["notification"] });

  const loadMore = useCallback(async () => {
    if (!state.hasMore || !state.nextCursor) return;
    try {
      const page = await api.getNotifications({ cursor: state.nextCursor });
      set((s) => {
        // Drop any id already present (a live event may have prepended it) before appending.
        const have = new Set(s.items.map((n) => n.id));
        return {
          items: [...s.items, ...(page.rows ?? page.items).filter((n) => !have.has(n.id))],
          unreadCount: page.unreadCount,
          nextCursor: page.nextCursor,
          hasMore: page.hasMore,
        };
      });
    } catch {
      /* the next scroll retries */
    }
  }, [state.hasMore, state.nextCursor, set]);

  const markRead = useCallback(async (id: string) => {
    const target = state.items.find((n) => n.id === id);
    if (!target || !target.unread) return;
    set((s) => ({
      items: s.items.map((n) => (n.id === id ? { ...n, unread: false, readAt: new Date().toISOString() } : n)),
      unreadCount: Math.max(0, s.unreadCount - 1),
    }));
    try {
      set({ unreadCount: (await api.markNotificationRead(id)).unreadCount });
    } catch {
      /* the optimistic count stands */
    }
  }, [state.items, set]);

  const markAllRead = useCallback(async () => {
    if (state.unreadCount === 0) return;
    const now = new Date().toISOString();
    set((s) => ({
      items: s.items.map((n) => (n.unread ? { ...n, unread: false, readAt: now } : n)),
      unreadCount: 0,
    }));
    try {
      set({ unreadCount: (await api.markAllNotificationsRead()).unreadCount });
    } catch {
      /* the optimistic count stands */
    }
  }, [state.unreadCount, set]);

  const value = useMemo<Notifications>(() => ({
    ...state,
    loadMore,
    markRead,
    markAllRead,
    openPanel: () => set({ panelOpen: true }),
    closePanel: () => set({ panelOpen: false }),
    setStatusFilter: (statusFilter) => set({ statusFilter }),
    setTypeFilter: (typeFilter) => set({ typeFilter }),
  }), [state, loadMore, markRead, markAllRead, set]);

  return createElement(NotificationsContext.Provider, { value }, children);
}
