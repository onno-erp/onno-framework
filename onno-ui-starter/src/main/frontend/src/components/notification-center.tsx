import { useEffect } from "react";
import { AtSign, Bell, CheckCheck, Inbox, Reply, UserPlus, X } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { initials, notionistsAvatar, tint } from "@/components/presence-avatars";
import { Segmented } from "@/components/ui/segmented";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";
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
    case "reply":
      return Reply;
    default:
      return Bell;
  }
}

// The i18n key for a built-in type's tab/pill label; any other (custom) type is humanized from its
// string. Keeps the tab set fully modular — a producer emitting a new type needs no client change,
// and can still localize its label by defining `notifications.type.<type>` in onno.ui.messages.
const KNOWN_TYPE_LABEL: Record<string, string> = {
  mention: "notifications.typeMention",
  reply: "notifications.typeReply",
  assignment: "notifications.typeAssignment",
};
function humanizeType(type: string): string {
  const s = type.replace(/[_-]+/g, " ").trim();
  return s ? s[0].toUpperCase() + s.slice(1) : type;
}
function typeLabelOf(type: string, t: Translate): string {
  if (KNOWN_TYPE_LABEL[type]) return t(KNOWN_TYPE_LABEL[type]);
  const key = `notifications.type.${type}`;
  const localized = t(key);
  return localized === key ? humanizeType(type) : localized; // t() echoes the key when undefined
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
  // Known types keep their curated (singular) tag label; a custom type shows a humanized pill so its
  // category is still visible in the row.
  const typeLabel =
    item.type === "mention"
      ? t("notifications.tagMention")
      : item.type === "assignment"
      ? t("notifications.tagAssignment")
      : item.type === "reply"
      ? t("notifications.tagReply")
      : humanizeType(item.type);
  return (
    <button
      type="button"
      onClick={() => onOpen(item)}
      className="group relative flex w-full items-start gap-3.5 rounded-card px-3 py-3 text-left transition-colors hover:bg-muted/60"
    >
      {item.actorName ? (
        <Avatar className="h-9 w-9 shrink-0 ring-1 ring-border/60">
          <AvatarImage src={item.actorAvatar || notionistsAvatar(item.actorName)} alt={item.actorName} />
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
          {/* Unread marker rides in the flow next to the timestamp — absolutely positioning it at
              the card corner overlapped the timestamp text. Unread emphasis = dot + semibold
              title; no row tint, so color still means "action/brand", not "new". */}
          <span className="flex shrink-0 items-center gap-1.5 pt-0.5">
            <span className="text-[11px] tabular-nums text-muted-foreground/70">{timeAgo(item.createdAt)}</span>
            {item.unread ? <span className="h-1.5 w-1.5 rounded-full bg-primary" aria-hidden /> : null}
          </span>
        </span>
        {item.body ? (
          <span className="mt-1 line-clamp-2 block text-[13px] leading-relaxed text-muted-foreground">{item.body}</span>
        ) : null}
        {typeLabel ? (
          <span className="mt-2 inline-flex items-center gap-1 rounded-control bg-muted px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
            <Icon className="h-2.5 w-2.5" />
            {typeLabel}
          </span>
        ) : null}
      </span>
    </button>
  );
}

/** The All / Unread segmented control. */
function StatusTabs({ value }: { value: StatusFilter }) {
  const t = useMessages();
  const opts: { value: StatusFilter; label: string }[] = [
    { value: "all", label: t("notifications.all") },
    { value: "unread", label: t("notifications.unread") },
  ];
  return <Segmented value={value} options={opts} onChange={setStatusFilter} />;
}

/**
 * The source (type) filter pills — one per notification type the user actually has (server-reported,
 * most-recent-first), plus a leading "All". Fully modular: the tabs follow the data, so a custom
 * producer's type appears with no client change, and built-in tabs disappear when their source is off
 * (no notifications of that type). Hidden entirely when there's ≤1 type — nothing to filter.
 */
function TypePills({ value, types }: { value: TypeFilter; types: string[] }) {
  const t = useMessages();
  if (types.length <= 1) return null;
  const opts: [TypeFilter, string][] = [
    ["all", t("notifications.typeAll")],
    ...types.map((ty) => [ty, typeLabelOf(ty, t)] as [TypeFilter, string]),
  ];
  return (
    <div className="flex items-center gap-1 overflow-x-auto">
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

/**
 * The sidebar trigger — a row shaped like the account island below it (same surface, border, and
 * radius, passed in by the host so consumer branding wins) that opens the panel. Text and icon
 * sizes mirror the DivKit nav rows (14 regular label, muted 16 glyph) so the whole rail reads as
 * one column of chrome.
 */
export function NotificationTrigger({ style }: { style?: React.CSSProperties }) {
  const t = useMessages();
  const { unreadCount, available, panelOpen } = useNotifications();

  useEffect(() => registerTrigger(), []);

  if (!available) return null;
  return (
    <button
      type="button"
      onClick={openPanel}
      aria-label={t("notifications.title")}
      style={style}
      // Hover/open tint is an ::after overlay, not a brightness filter — a filter brightens
      // the border along with the background, which made the border vanish on hover.
      className={cn(
        "relative flex w-full shrink-0 items-center gap-2.5 overflow-hidden rounded-card border px-3 py-2.5 text-sm text-foreground",
        "after:pointer-events-none after:absolute after:inset-0 after:bg-foreground/[0.04] after:opacity-0 after:transition-opacity",
        panelOpen ? "after:opacity-100" : "hover:after:opacity-100"
      )}
    >
      <Bell className="h-4 w-4 shrink-0 text-muted-foreground" />
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
  const { panelOpen, available, unreadCount, hasMore, statusFilter, typeFilter, types, triggerCount, navStyle } = store;

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
      {/* Floating fallback trigger — only for the topbar layout, which mounts no sidebar trigger:
          a compact top-right button. Bottom-bar layouts (mobile/tablet) reach notifications via
          the More menu's row (with the unread dot on the More tab), so nothing floats over the
          content or the bar there. */}
      {triggerCount === 0 && navStyle === "topbar" ? (
        <button
          type="button"
          onClick={openPanel}
          aria-label={t("notifications.title")}
          className="fixed right-3 top-3 z-40 flex h-9 w-9 items-center justify-center rounded-full border border-border/60 bg-background/90 text-muted-foreground shadow-sm backdrop-blur transition-colors hover:bg-muted hover:text-foreground"
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
        // Only advertise the modal role while open: the panel is position:fixed at all times, so
        // isInteractiveLayerOpen() (which treats fixed nodes as visible) would otherwise always see
        // it and permanently swallow the page-close Escape. Gated on panelOpen, it lets the
        // divkit-view Esc handler bail only when the panel is actually up.
        aria-modal={panelOpen ? "true" : undefined}
        aria-label={t("notifications.title")}
        className={cn(
          // A floating island on every viewport: inset from the edges with a gap, rounded + bordered.
          // On phones max-w keeps the same island shape at nearly full width.
          "fixed right-3 top-3 bottom-3 z-50 flex w-[400px] max-w-[calc(100vw-1.5rem)] flex-col overflow-hidden rounded-card border border-border bg-background shadow-2xl ease-out",
          // Mobile: fade + gentle scale in place — no slide, the island just appears over the scrim.
          "transition-[opacity,transform] duration-200",
          panelOpen ? "opacity-100 scale-100" : "pointer-events-none opacity-0 scale-95",
          // Desktop/tablet: the slide-over — fully past the right edge (100% + the island's 0.75rem
          // gap) so it never peeks when closed; opacity/scale pinned so only the slide animates.
          "sm:scale-100 sm:opacity-100 sm:transition-transform sm:duration-300",
          panelOpen ? "sm:translate-x-0" : "sm:translate-x-[calc(100%+0.75rem)]"
        )}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 pb-4 pt-5">
          <div className="flex items-center gap-2.5">
            <h2 className="text-base font-semibold text-foreground">{t("notifications.title")}</h2>
            {unreadCount > 0 ? (
              <span className="rounded-control bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">
                {unreadCount}
              </span>
            ) : null}
          </div>
          <div className="flex items-center gap-1">
            {unreadCount > 0 ? (
              <button
                type="button"
                onClick={() => void markAllNotificationsRead()}
                className="flex items-center gap-1.5 rounded-control px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              >
                <CheckCheck className="h-3.5 w-3.5" />
                {t("notifications.markAllRead")}
              </button>
            ) : null}
            <button
              type="button"
              onClick={closePanel}
              aria-label={t("action.cancel")}
              className="flex h-7 w-7 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center justify-between gap-2 px-5 pb-3">
          <StatusTabs value={statusFilter} />
          <TypePills value={typeFilter} types={types} />
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
