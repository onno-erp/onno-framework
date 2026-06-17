import { useState } from "react";
import { Bell, Check, CheckCheck, X } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { useNotifications } from "@/hooks/use-notifications";
import type { NotificationView } from "@/lib/api";

// Severity → the dot colour beside each row. Mirrors the badge variants' palette tokens.
const SEVERITY_DOT: Record<NotificationView["severity"], string> = {
  info: "bg-primary",
  success: "bg-[hsl(var(--success))]",
  warning: "bg-[hsl(var(--warning))]",
  error: "bg-destructive",
};

function timeAgo(iso: string | null): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const seconds = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(then).toLocaleDateString();
}

// Reuse the host's navigation bus: an `onec://kind/name/id` url dispatched as an `onec:action`
// window event is routed by divkit-view exactly as a list-row tap or a mention-chip click is.
function navigate(link: string | null) {
  if (link && link.startsWith("onec://")) {
    window.dispatchEvent(new CustomEvent("onec:action", { detail: link }));
  }
}

/**
 * The notification bell: a fixed top-right button with an unread badge and a popover inbox. It is
 * mounted once inside the authenticated app (alongside the server-driven DivKit shell) rather than
 * composed into the shell itself, so it works regardless of the active layout/viewport.
 */
export function NotificationBell() {
  const { items, unread, available, markRead, markAllRead, dismiss } = useNotifications();
  const [open, setOpen] = useState(false);

  // The feature is disabled server-side (onec.notifications.enabled=false) — render no bell at all.
  if (!available) return null;

  const onRowClick = (n: NotificationView) => {
    if (!n.read) markRead(n.id);
    if (n.link) {
      navigate(n.link);
      setOpen(false);
    }
  };

  return (
    <div className="fixed right-3 top-2 z-40">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-label={unread > 0 ? `Notifications (${unread} unread)` : "Notifications"}
            className="relative flex h-9 w-9 items-center justify-center rounded-full border border-border/60 bg-background/80 text-muted-foreground shadow-sm backdrop-blur transition-colors hover:bg-accent hover:text-foreground"
          >
            <Bell className="h-[18px] w-[18px]" />
            {unread > 0 && (
              <span className="absolute -right-0.5 -top-0.5 flex min-w-[18px] items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-[18px] text-destructive-foreground">
                {unread > 99 ? "99+" : unread}
              </span>
            )}
          </button>
        </PopoverTrigger>
        <PopoverContent align="end" sideOffset={8} className="w-80 p-0">
          <div className="flex items-center justify-between border-b px-3 py-2">
            <span className="text-sm font-medium">Notifications</span>
            {unread > 0 && (
              <button
                type="button"
                onClick={() => markAllRead()}
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
              >
                <CheckCheck className="h-3.5 w-3.5" />
                Mark all read
              </button>
            )}
          </div>

          <div className="max-h-[22rem] overflow-y-auto">
            {items.length === 0 ? (
              <div className="px-3 py-8 text-center text-sm text-muted-foreground">
                You're all caught up.
              </div>
            ) : (
              <ul className="divide-y">
                {items.map((n) => (
                  <li
                    key={n.id}
                    className={cn(
                      "group relative flex gap-2.5 px-3 py-2.5 transition-colors hover:bg-accent/50",
                      !n.read && "bg-accent/30"
                    )}
                  >
                    <span
                      className={cn(
                        "mt-1.5 h-2 w-2 shrink-0 rounded-full",
                        n.read ? "bg-transparent" : SEVERITY_DOT[n.severity] ?? "bg-primary"
                      )}
                    />
                    <button
                      type="button"
                      onClick={() => onRowClick(n)}
                      className={cn(
                        "min-w-0 flex-1 text-left",
                        n.link && "cursor-pointer"
                      )}
                    >
                      <div className={cn("truncate text-sm", !n.read && "font-medium")}>
                        {n.title}
                      </div>
                      {n.body && (
                        <div className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
                          {n.body}
                        </div>
                      )}
                      <div className="mt-1 text-[11px] text-muted-foreground/80">
                        {timeAgo(n.createdAt)}
                      </div>
                    </button>
                    <div className="flex shrink-0 flex-col items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
                      {!n.read && (
                        <button
                          type="button"
                          aria-label="Mark read"
                          title="Mark read"
                          onClick={() => markRead(n.id)}
                          className="rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                        >
                          <Check className="h-3.5 w-3.5" />
                        </button>
                      )}
                      <button
                        type="button"
                        aria-label="Dismiss"
                        title="Dismiss"
                        onClick={() => dismiss(n.id)}
                        className="rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                      >
                        <X className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
