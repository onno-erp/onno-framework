import { useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { useNotifications } from "@/lib/notification-store";

/**
 * Bridges DivKit's unread-notification indicator blocks to the live notification store — the
 * server-driven chrome can't know the unread count, so it emits fixed-size custom blocks and this
 * bridge fills them (mirroring the nav-presence bridge):
 *
 * - `onno-notification-dot` — a small corner dot overlaid on the bottom bar's "More" tab icon.
 * - `onno-notification-badge` — a count pill, pinned right in the mobile menu's Notifications row.
 *
 * Both render nothing when there are no unread notifications or the feature is disabled
 * server-side (the store marks itself unavailable off the seed 404), so the server always emits
 * the blocks and layout never shifts.
 */

type Kind = "dot" | "badge";
type Mount = { id: number; el: HTMLElement; kind: Kind };

let mounts: Mount[] = [];
const listeners = new Set<() => void>();
let seq = 0;

function emit() {
  for (const l of listeners) l();
}
function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
function getSnapshot(): Mount[] {
  return mounts;
}

function indicatorElement(kind: Kind) {
  return class extends HTMLElement {
    private readonly _id = ++seq;

    connectedCallback() {
      if (!mounts.some((m) => m.el === this)) {
        mounts = [...mounts, { id: this._id, el: this, kind }];
        emit();
      }
    }
    disconnectedCallback() {
      if (mounts.some((m) => m.el === this)) {
        mounts = mounts.filter((m) => m.el !== this);
        emit();
      }
    }
  };
}

let defined = false;
export function defineNotificationIndicatorElements() {
  if (defined || typeof customElements === "undefined") return;
  if (!customElements.get("onno-notification-dot")) {
    customElements.define("onno-notification-dot", indicatorElement("dot"));
  }
  if (!customElements.get("onno-notification-badge")) {
    customElements.define("onno-notification-badge", indicatorElement("badge"));
  }
  defined = true;
}
defineNotificationIndicatorElements();

/** The DivKit `customComponents` entries: custom_type → element tag. */
export const NOTIFICATION_INDICATOR_CUSTOM_COMPONENTS = new Map<string, { element: string }>([
  ["onno-notification-dot", { element: "onno-notification-dot" }],
  ["onno-notification-badge", { element: "onno-notification-badge" }],
]);

/** A small dot in the block's top-right corner — the block overlaps the tab icon 1:1. */
function UnreadDot() {
  const { available, unreadCount } = useNotifications();
  if (!available || unreadCount === 0) return null;
  return (
    <span className="relative block h-full w-full">
      <span className="absolute right-0 top-0 h-2 w-2 rounded-full bg-primary" aria-hidden />
    </span>
  );
}

/** The unread-count pill, right-aligned in its reserved slot. */
function UnreadBadge() {
  const { available, unreadCount } = useNotifications();
  if (!available || unreadCount === 0) return null;
  return (
    <span className="flex h-full w-full items-center justify-end">
      <span className="flex min-w-5 items-center justify-center rounded-full bg-primary px-1.5 text-[11px] font-semibold leading-5 text-primary-foreground">
        {unreadCount > 99 ? "99+" : unreadCount}
      </span>
    </span>
  );
}

/** Portals each mounted indicator block to its live view. Mount once, inside providers. */
export function NotificationIndicatorPortals() {
  const live = useSyncExternalStore(subscribe, getSnapshot);
  return (
    <>
      {live.map((m) =>
        createPortal(m.kind === "dot" ? <UnreadDot /> : <UnreadBadge />, m.el, String(m.id))
      )}
    </>
  );
}
