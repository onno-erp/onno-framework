import { useNotifications } from "../../lib/notification-store";
import { NotificationBadgeMotion } from "../ui/notification-badge-motion";

/** Live unread-notification indicators: a corner dot and a count pill. Render nothing when unavailable. */

/** A small dot in the block's top-right corner — the block overlaps the tab icon 1:1. */
export function UnreadDot() {
  const { available, unreadCount } = useNotifications();
  if (!available) return null;
  return (
    <span className="relative block h-full w-full">
      <NotificationBadgeMotion count={unreadCount} className="!right-0 !top-0">
        <span className="block h-2 w-2 rounded-full bg-primary" aria-hidden />
      </NotificationBadgeMotion>
    </span>
  );
}

/** The unread-count pill, right-aligned in its reserved slot. */
export function UnreadBadge() {
  const { available, unreadCount } = useNotifications();
  if (!available) return null;
  return (
    <span className="flex h-full w-full items-center justify-end">
      <NotificationBadgeMotion count={unreadCount} className="!static">
        <span className="flex min-w-5 items-center justify-center rounded-full bg-primary px-1.5 text-[11px] font-semibold leading-5 text-primary-foreground">
          {unreadCount > 99 ? "99+" : unreadCount}
        </span>
      </NotificationBadgeMotion>
    </span>
  );
}
