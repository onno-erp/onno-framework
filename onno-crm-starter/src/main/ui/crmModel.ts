import { locale, text as t, type EntityRecord } from "@onno/ui";
import type { ReactNode } from "react";

/**
 * The CRM's client-side data shapes and the small helpers every part shares. Apps building their own
 * screens from the CRM's parts (`CrmConversation`, `ChatTimeline`, `useCrmConversation`, …) read and
 * write these shapes; the server owns their meaning.
 */

/** A file on a message. The url is a reference issued by `/api/media`, never the bytes themselves. */
export type CrmAttachment = { url: string; filename: string; contentType: string; size: number; image: boolean };

/** One message of a conversation, as the timeline draws it. */
export type CrmMessage = {
  id: string;
  conversationId?: string;
  accountLabel?: string;
  subject?: string | null;
  /** Address an outbound email was queued to; shown so history reflects a changed recipient. */
  recipient?: string | null;
  kind: "CUSTOMER_MESSAGE" | "AGENT_REPLY" | "SYSTEM_EVENT";
  direction: "INBOUND" | "OUTBOUND" | "INTERNAL";
  channel: string;
  authorName: string;
  authorAvatarUrl?: string | null;
  body: string;
  sentAt: string;
  deliveryStatus: string;
  attachments?: CrmAttachment[];
  /** Sanitized email HTML; rendered in a sandboxed frame instead of the plain-text body. */
  bodyHtml?: string | null;
  /** An email's To and Cc lists: `Name <address>` entries joined by ", " (see `emailAddresses.ts`); null when not known. */
  sentTo?: string | null;
  copyTo?: string | null;
};

/** An internal note on a conversation: visible to the team only. */
export type CrmNote = {
  id: string;
  authorName: string | null;
  authorAvatarUrl: string | null;
  body: string;
  createdAt: string | null;
  mine: boolean;
};

/**
 * How a conversation can be answered right now. `senders`/`sender`: the mailboxes a reply may go out
 * from and the default one's id. Absent or a single entry shows no mailbox picker.
 */
export type CrmDelivery = {
  connected: boolean; label: string; maxTextLength: number;
  replyCapability: "AVAILABLE" | "READ_ONLY" | "WINDOW_CLOSED"; replyReason: string;
  maxAttachments?: number; attachmentReason?: string;
  recipient?: string; recipients?: string[];
  senders?: { id: string; label?: string | null }[]; sender?: string | null;
  /** Who a reply copies by default (reply-all): everyone else on the latest received email, as `Name <address>` entries. */
  cc?: string[];
};

/**
 * One entry of a chat's timeline, oldest first. The CRM's own entries are messages and notes; an
 * app adds its own (an order, a booking, a payment) through `timelineSources`, drawn by `render`.
 */
export type CrmTimelineEntry =
  | { id: string; at: string; type: "message"; message: CrmMessage }
  | { id: string; at: string; type: "note"; note: CrmNote }
  | { id: string; at: string; type: "custom"; render: () => ReactNode };

/** A host action (a `Commands<Conversation>` command) as the conversation offers it. */
export type CrmConversationAction = { id: string; title: string; enabled: boolean };

export function string(row: EntityRecord | null | undefined, key: string, fallback = ""): string {
  const value = row?.[key];
  return value == null ? fallback : String(value);
}

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]?.toUpperCase()).join("");
}

/** Today: the time; earlier: the date. */
export function compactTime(value: unknown): string {
  if (!value) return "";
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const sameDay = date.toDateString() === new Date().toDateString();
  return sameDay
    ? new Intl.DateTimeFormat(locale(), { hour: "2-digit", minute: "2-digit" }).format(date)
    : new Intl.DateTimeFormat(locale(), { month: "short", day: "numeric" }).format(date);
}

export function fullTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(locale(), { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
}

/** "2.4 MB": a size a person can judge before clicking, not a byte count. */
export function fileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024, unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

export function conversationChannel(row: EntityRecord | null | undefined): string {
  return string(row, "channel", t("crm.chat.channel"));
}

export function csrfToken(): string | undefined {
  const token = document.cookie.split(";").map(part => part.trim()).find(part => part.startsWith("XSRF-TOKEN="));
  return token ? decodeURIComponent(token.slice("XSRF-TOKEN=".length)) : undefined;
}

export async function checkedJson<T>(response: Response, message: string): Promise<T> {
  if (!response.ok) throw new Error(message);
  return response.json() as Promise<T>;
}

/** A CRM write (POST, CSRF-protected); the server's message becomes the error. */
export async function crmCommand<T>(path: string, body: unknown = {}): Promise<T> {
  const csrf = csrfToken();
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...(csrf ? { "X-XSRF-TOKEN": csrf } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const error = await response.json();
      if (error.message) message = String(error.message);
    } catch {
      // Keep the status fallback.
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

/** A conversation command, scoped to the workspace it is used from (which decides who may run it). */
export function workspaceCommand<T>(workspace: string | undefined, path: string, body: unknown = {}): Promise<T> {
  return crmCommand<T>(workspace && path.startsWith("/api/crm/conversations/")
    ? `${path}?workspace=${encodeURIComponent(workspace)}` : path, body);
}
