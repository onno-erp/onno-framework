import { useEffect } from "react";
import {
  NotificationBadgeMotion,
  registerUiFeature,
  type UiFeatureRuntime,
} from "@onno/widget-sdk";
import {
  EntityCommentsWidget,
  type CommentTarget,
} from "./collaboration/entity-comments-widget";
import {
  NotificationCenter,
  NotificationTrigger,
} from "./collaboration/notification-center";
import { PresenceAvatars } from "./collaboration/presence-avatars";
import { PanePresence, TabPresence } from "./collaboration/presence-surfaces";
import {
  openPanel,
  setNotificationsNavStyle,
  startNotifications,
  useNotifications,
} from "./collaboration/notification-store";
import {
  startPresence,
  useEntityViewers,
  useRecordViewers,
} from "./collaboration/presence-store";

function payloadObject(payload: unknown): Record<string, unknown> {
  return payload && typeof payload === "object"
    ? (payload as Record<string, unknown>)
    : {};
}

function CommentsBlock({ payload }: { payload?: unknown }) {
  const target = payloadObject(payload).target as CommentTarget | undefined;
  return target ? <EntityCommentsWidget target={target} /> : null;
}

function navIdentity(path: string): { kind: string; name: string } {
  const segments = path.split("/").filter(Boolean);
  if (
    segments.length >= 2 &&
    (segments[0] === "catalogs" || segments[0] === "documents")
  ) {
    return { kind: segments[0], name: segments[1] };
  }
  return { kind: "page", name: `/${segments.join("/")}` };
}

function NavPresenceBlock({ payload }: { payload?: unknown }) {
  const path = String(payloadObject(payload).path ?? "");
  const { kind, name } = navIdentity(path);
  const viewers = useEntityViewers(kind, name);
  return (
    <PresenceAvatars
      viewers={viewers}
      size={20}
      max={3}
      overlap
      className="h-full w-full justify-end"
    />
  );
}

function NotificationDot() {
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

function NotificationBadge() {
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

function CollaborationRoot({ focusedPath, navStyle }: UiFeatureRuntime) {
  useEffect(() => {
    startPresence();
    startNotifications();
  }, []);
  useEffect(() => setNotificationsNavStyle(navStyle), [navStyle]);
  return (
    <>
      <PanePresence path={focusedPath} />
      <NotificationCenter />
    </>
  );
}

function RowPresence({ rowId }: { kind: string; name: string; rowId: string }) {
  const viewers = useRecordViewers(rowId);
  return <PresenceAvatars viewers={viewers} size={16} max={3} overlap />;
}

registerUiFeature({
  id: "collaboration",
  customComponents: {
    "onno-comments": CommentsBlock,
    "onno-nav-presence": NavPresenceBlock,
    "onno-notification-dot": NotificationDot,
    "onno-notification-badge": NotificationBadge,
  },
  Root: CollaborationRoot,
  TabAdornment: TabPresence,
  SidebarFooter: ({ background, borderColor }) => (
    <NotificationTrigger style={{ background, borderColor }} />
  ),
  RowAdornment: RowPresence,
  handleAction: (url) => {
    if (url !== "onno://notifications") return false;
    openPanel();
    return true;
  },
});
