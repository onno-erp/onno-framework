import { useCallback, useEffect, useRef, useState } from "react";
import { api, type NotificationView } from "@/lib/api";
import type { UiEvent } from "@/lib/types";

// Fallback poll cadence. The inbox normally updates instantly off the shared SSE fan-out (below);
// the poll just guarantees the count is never more than this stale if an event is missed.
const POLL_MS = 45_000;
const ENDPOINT = "/api/notifications";

/** A quiet read that never toasts — the bell is non-critical chrome. Returns null on any failure. */
async function quietGet<T>(url: string): Promise<{ ok: true; data: T } | { ok: false; status: number }> {
  try {
    const res = await fetch(url, {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return { ok: false, status: res.status };
    return { ok: true, data: (await res.json()) as T };
  } catch {
    return { ok: false, status: 0 };
  }
}

/**
 * Backs the notification bell: the current user's recent inbox plus its unread count, kept live.
 * It refreshes on three triggers — initial mount, a periodic poll, and the shared `onec:dataevent`
 * window fan-out (divkit-view's one SSE stream) whenever the server reports a `notification` change —
 * so a notification raised in another tab, or by another user's action, lights the badge without a
 * reload. Reads are quiet (no error toasts); a `404` means the feature is disabled server-side, so the
 * hook reports `available: false` and the caller hides the bell entirely. Mutations are optimistic and
 * reconcile against the server on failure.
 */
export function useNotifications() {
  const [items, setItems] = useState<NotificationView[]>([]);
  const [unread, setUnread] = useState(0);
  const [available, setAvailable] = useState(true);
  const availableRef = useRef(true);

  const refresh = useCallback(async () => {
    if (!availableRef.current) return;
    const [list, count] = await Promise.all([
      quietGet<NotificationView[]>(ENDPOINT),
      quietGet<{ count: number }>(`${ENDPOINT}/unread-count`),
    ]);
    // A 404 means the endpoint isn't wired (onec.notifications.enabled=false) — stand down for good.
    if ((!list.ok && list.status === 404) || (!count.ok && count.status === 404)) {
      availableRef.current = false;
      setAvailable(false);
      return;
    }
    if (list.ok) setItems(list.data);
    if (count.ok) setUnread(count.data.count);
  }, []);

  // Initial load + poll fallback.
  useEffect(() => {
    refresh();
    const timer = window.setInterval(refresh, POLL_MS);
    return () => window.clearInterval(timer);
  }, [refresh]);

  // Instant refresh when the shared stream reports an inbox change.
  useEffect(() => {
    const onData = (e: Event) => {
      const event = (e as CustomEvent).detail as UiEvent;
      if (event?.entityType === "notification") refresh();
    };
    window.addEventListener("onec:dataevent", onData);
    return () => window.removeEventListener("onec:dataevent", onData);
  }, [refresh]);

  const markRead = useCallback(
    async (id: string) => {
      setItems((prev) => prev.map((n) => (n.id === id && !n.read ? { ...n, read: true } : n)));
      setUnread((u) => Math.max(0, u - 1));
      try {
        await api.markNotificationRead(id);
      } catch {
        refresh();
      }
    },
    [refresh]
  );

  const markAllRead = useCallback(async () => {
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
    setUnread(0);
    try {
      await api.markAllNotificationsRead();
    } catch {
      refresh();
    }
  }, [refresh]);

  const dismiss = useCallback(
    async (id: string) => {
      setItems((prev) => prev.filter((n) => n.id !== id));
      try {
        await api.dismissNotification(id);
      } finally {
        // Resync the count: a dismissed item may or may not have been unread.
        refresh();
      }
    },
    [refresh]
  );

  return { items, unread, available, refresh, markRead, markAllRead, dismiss };
}
