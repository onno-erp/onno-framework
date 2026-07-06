import { useEffect } from "react";
import { AtSign, Bell, CheckCheck, Inbox, UserPlus, X } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { initials, tint } from "@/components/presence-avatars";
import { useMessages } from "@/providers/messages-provider";
import { cn } from "@/lib/utils";
import type { NotificationView } from "@/lib/api";
import {
  closePanel,
  filteredItems,
  loadMoreNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  openPanel,
  registerTrigger,
  setStatusFilter,
  setTypeFilter,
  startNotifications,
  useNotifications,
  type StatusFilter,
  type TypeFilter,
} from "@/lib/notification-store";

/**
 * The notification center: a bell trigger that lives beside the profile in the sidebar (or a floating
 * fallback where no sidebar exists), opening a full-height slide-over panel on the right with All/Unread
 * + per-source filters and a grouped, timestamped timeline. Client-owned chrome fed entirely by the
 * server-backed notification store. Hides itself when the feature is disabled server-side.
 */

/** Lucide glyph per notification type; unknown types fall back to a generic bell. */
function typeIcon(type: string) {
  switch (type) {
    case "mention":
      return AtSign;
    case "assignment":
      return UserPlus;
    default:
      return Bell;
  }
}

/** A short, locale-aware "5m / 3h / 2d ago" from an ISO timestamp. */
function timeAgo(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const secs = Math.round((Date.now() - then) / 1000);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto", style: "narrow" });
  const table: [Intl.RelativeTimeFormatUnit, number][] = [
    ["year", 31_536_000],
    ["month", 2_592_000],
    ["week", 604_800],
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  for (const [unit, size] of table) {
    if (secs >= size) return rtf.format(-Math.floor(secs / size), unit);
  }
  return rtf.format(-Math.max(1, secs), "second");
}

/** Bucket a timestamp into a section for the grouped timeline. */
type Bucket = "today" | "week" | "older";
function bucketOf(iso: string): Bucket {
  const then = new Date(iso).getTime();
  const dayMs = 86_400_000;
  const startOfToday = new Date();
  startOfToday.setHours(0, 0, 0, 0);
  if (then >= startOfToday.getTime()) return "today";
  if (then >= startOfToday.getTime() - 6 * dayMs) return "week";
  return "older";
}

function NotificationItem({ item, onOpen }: { item: NotificationView; onOpen: (n: NotificationView) => void }) {
  const t = useMessages();
  const Icon = typeIcon(item.type);
  const typeLabel =
    item.type === "mention"
      ? t("notifications.tagMention")
      : item.type === "assignment"
      ? t("notifications.tagAssignment")
      : null;
  return (
    <button
      type="button"
      onClick={() => onOpen(item)}
      className={cn(
        "group relative flex w-full items-start gap-3.5 rounded-card px-3 py-3 text-left transition-colors hover:bg-muted/60",
        item.unread && "bg-primary/[0.04]"
      )}
    >
      {item.actorName ? (
        <Avatar className="h-9 w-9 shrink-0 ring-1 ring-border/60">
          {item.actorAvatar ? <AvatarImage src={item.actorAvatar} alt={item.actorName} /> : null}
          <AvatarFallback className="text-xs font-semibold text-white" style={{ backgroundColor: tint(item.actorName) }}>
            {initials(item.actorName)}
          </AvatarFallback>
        </Avatar>
      ) : (
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground ring-1 ring-border/60">
          <Icon className="h-4 w-4" />
        </span>
      )}
      <span className="min-w-0 flex-1">
        <span className="flex items-start justify-between gap-2">
          <span className={cn("text-sm leading-snug text-foreground", item.unread ? "font-semibold" : "font-medium")}>
            {item.title}
          </span>
          <span className="shrink-0 pt-0.5 text-[11px] tabular-nums text-muted-foreground/70">
            {timeAgo(item.createdAt)}
          </span>
        </span>
        {item.body ? (
          <span className="mt-1 line-clamp-2 block text-[13px] leading-relaxed text-muted-foreground">{item.body}</span>
        ) : null}
        {typeLabel ? (
          <span className="mt-2 inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            <Icon className="h-2.5 w-2.5" />
            {typeLabel}
          </span>
        ) : null}
      </span>
      {item.unread ? (
        <span className="absolute right-3 top-3.5 h-2 w-2 shrink-0 rounded-full bg-primary" aria-hidden />
      ) : null}
    </button>
  );
}

/** The All / Unread segmented control. */
function StatusTabs({ value }: { value: StatusFilter }) {
  const t = useMessages();
  const opts: [StatusFilter, string][] = [
    ["all", t("notifications.all")],
    ["unread", t("notifications.unread")],
  ];
  return (
    <div className="inline-flex rounded-control bg-muted p-0.5">
      {opts.map(([key, label]) => (
        <button
          key={key}
          type="button"
          onClick={() => setStatusFilter(key)}
          className={cn(
            "rounded-control px-3 py-1 text-xs font-medium transition-colors",
            value === key ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/** The source (type) filter pills. */
function TypePills({ value }: { value: TypeFilter }) {
  const t = useMessages();
  const opts: [TypeFilter, string][] = [
    ["all", t("notifications.typeAll")],
    ["mention", t("notifications.typeMention")],
    ["assignment", t("notifications.typeAssignment")],
  ];
  return (
    <div className="flex items-center gap-1">
      {opts.map(([key, label]) => (
        <button
          key={key}
          type="button"
          onClick={() => setTypeFilter(key)}
          className={cn(
            "rounded-control px-2.5 py-1 text-xs font-medium transition-colors",
            value === key
              ? "bg-primary text-primary-foreground"
              : "text-muted-foreground hover:bg-muted hover:text-foreground"
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/** The sidebar trigger — a nav-style row that sits beside the profile and opens the panel. */
export function NotificationTrigger() {
  const t = useMessages();
  const { unreadCount, available, panelOpen } = useNotifications();

  useEffect(() => registerTrigger(), []);

  if (!available) return null;
  return (
    <button
      type="button"
      onClick={openPanel}
      aria-label={t("notifications.title")}
      className={cn(
        "flex w-full items-center gap-2.5 rounded-card border px-3 py-2.5 text-sm font-medium transition-colors",
        panelOpen
          ? "border-border bg-muted text-foreground"
          : "border-border/60 bg-background/40 text-muted-foreground hover:bg-muted hover:text-foreground"
      )}
    >
      <Bell className="h-4 w-4 shrink-0" />
      <span className="flex-1 text-left">{t("notifications.title")}</span>
      {unreadCount > 0 ? (
        <span className="flex min-w-5 items-center justify-center rounded-full bg-primary px-1.5 text-[11px] font-semibold leading-5 text-primary-foreground">
          {unreadCount > 99 ? "99+" : unreadCount}
        </span>
      ) : null}
    </button>
  );
}

/**
 * The panel + backdrop, plus a floating bell fallback for layouts (topbar/mobile) that mount no
 * sidebar trigger. Mounted once in the authenticated shell; also boots the store.
 */
export function NotificationCenter() {
  const t = useMessages();
  const store = useNotifications();
  const { panelOpen, available, unreadCount, hasMore, statusFilter, typeFilter, triggerCount } = store;

  useEffect(() => {
    startNotifications();
  }, []);

  // Esc closes the panel.
  useEffect(() => {
    if (!panelOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closePanel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [panelOpen]);

  if (!available) return null;

  const items = filteredItems(store);
  const buckets: [Bucket, string][] = [
    ["today", t("notifications.today")],
    ["week", t("notifications.thisWeek")],
    ["older", t("notifications.older")],
  ];

  const openNotification = (n: NotificationView) => {
    if (n.unread) void markNotificationRead(n.id);
    if (n.link) window.dispatchEvent(new CustomEvent("onno:action", { detail: "onno://" + n.link }));
    closePanel();
  };

  const onScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    if (hasMore && el.scrollTop + el.clientHeight >= el.scrollHeight - 64) void loadMoreNotifications();
  };

  return (
    <>
      {/* Floating fallback trigger — only when no sidebar/topbar trigger is mounted. */}
      {triggerCount === 0 ? (
        <button
          type="button"
          onClick={openPanel}
          aria-label={t("notifications.title")}
          className="fixed right-3 top-3 z-40 flex h-9 w-9 items-center justify-center rounded-full border border-border/60 bg-background/80 text-muted-foreground shadow-sm backdrop-blur transition-colors hover:bg-muted hover:text-foreground"
        >
          <Bell className="h-4 w-4" />
          {unreadCount > 0 ? (
            <span className="absolute -right-0.5 -top-0.5 flex min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-semibold leading-4 text-primary-foreground">
              {unreadCount > 99 ? "99+" : unreadCount}
            </span>
          ) : null}
        </button>
      ) : null}

      {/* Backdrop — a black scrim (not bg-foreground, which is near-white in dark theme and washes the
          app out); dims consistently in both light and dark. */}
      <div
        onClick={closePanel}
        className={cn(
          "fixed inset-0 z-40 bg-black/50 backdrop-blur-[2px] transition-opacity duration-300",
          panelOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        aria-hidden
      />

      {/* Slide-over panel */}
      <aside
        role="dialog"
        aria-label={t("notifications.title")}
        className={cn(
          "fixed inset-y-0 right-0 z-50 flex w-[420px] max-w-full flex-col border-l border-border bg-background shadow-2xl transition-transform duration-300 ease-out",
          panelOpen ? "translate-x-0" : "pointer-events-none translate-x-full"
        )}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 pb-4 pt-5">
          <div className="flex items-center gap-2.5">
            <h2 className="text-base font-semibold text-foreground">{t("notifications.title")}</h2>
            {unreadCount > 0 ? (
              <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">
                {unreadCount}
              </span>
            ) : null}
          </div>
          <div className="flex items-center gap-1">
            {unreadCount > 0 ? (
              <button
                type="button"
                onClick={() => void markAllNotificationsRead()}
                className="flex items-center gap-1.5 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              >
                <CheckCheck className="h-3.5 w-3.5" />
                {t("notifications.markAllRead")}
              </button>
            ) : null}
            <button
              type="button"
              onClick={closePanel}
              aria-label={t("action.cancel")}
              className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center justify-between gap-2 px-5 pb-3">
          <StatusTabs value={statusFilter} />
          <TypePills value={typeFilter} />
        </div>

        <div className="mx-5 border-t border-border" />

        {/* Timeline */}
        <div className="min-h-0 flex-1 overflow-y-auto px-3 py-3" onScroll={onScroll}>
          {items.length === 0 ? (
            <div className="flex flex-col items-center gap-3 px-4 py-16 text-center">
              <span className="flex h-12 w-12 items-center justify-center rounded-card bg-muted text-muted-foreground/70">
                <Inbox className="h-6 w-6" />
              </span>
              <span className="text-sm text-muted-foreground">{t("notifications.empty")}</span>
            </div>
          ) : (
            buckets.map(([bucket, label]) => {
              const group = items.filter((n) => bucketOf(n.createdAt) === bucket);
              if (group.length === 0) return null;
              return (
                <div key={bucket} className="mb-1">
                  <div className="px-3 pb-1 pt-3 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
                    {label}
                  </div>
                  {group.map((n) => (
                    <NotificationItem key={n.id} item={n} onOpen={openNotification} />
                  ))}
                </div>
              );
            })
          )}
        </div>
      </aside>
    </>
  );
}
