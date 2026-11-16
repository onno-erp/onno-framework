import { text as t } from "@onno/ui";
import { request } from "./CrmWorkspace";

/**
 * The connected-mailbox search behind the inbox's "Find in mailbox". Every path the widget calls
 * lives in this one map, relative to `/api/crm` (see {@link request}), so a backend rename is a
 * one-line change here.
 */
export const MAILBOX_ENDPOINTS = {
  /** `GET` → `[{address, label, canModify}]`; no account (or a 404 on an older backend) hides the search. */
  accounts: "/gmail/accounts",
  /** `GET ?account=&q=&pageToken=` → `{messages:[…], nextPageToken}`, Gmail search syntax in `q`. */
  search: "/gmail/search",
  /** `POST {account, messageId, toInbox}` → `{conversationId, movedToInbox}`. */
  restore: "/gmail/restore",
} as const;

export type MailboxAccount = { address: string; label: string; canModify: boolean };
export type MailboxMessage = {
  id: string; threadId: string; from: string; subject: string; snippet: string;
  /** An ISO timestamp, or Gmail's epoch milliseconds. */
  date: string | number | null;
  archived: boolean;
  conversationId: string | null;
};
export type MailboxPage = { messages: MailboxMessage[]; nextPageToken: string | null };
export type MailboxRestore = { conversationId: string; movedToInbox: boolean };

const text = (value: unknown) => typeof value === "string" ? value : value == null ? "" : String(value);

/** The mailboxes the signed-in user may search; empty when none is connected or the backend has no search. */
export async function mailboxAccounts(): Promise<MailboxAccount[]> {
  const payload = await request<unknown>(MAILBOX_ENDPOINTS.accounts);
  const items = Array.isArray(payload) ? payload : Array.isArray((payload as {accounts?: unknown})?.accounts) ? (payload as {accounts: unknown[]}).accounts : [];
  return items.flatMap(item => {
    const account = item as Record<string, unknown>;
    const address = text(account?.address).trim();
    return address ? [{ address, label: text(account.label).trim() || address, canModify: account.canModify !== false }] : [];
  });
}

export async function searchMailbox(account: string, query: string, pageToken?: string | null): Promise<MailboxPage> {
  const params = new URLSearchParams({ account, q: query });
  if (pageToken) params.set("pageToken", pageToken);
  const payload = await request<{ messages?: unknown; nextPageToken?: unknown }>(`${MAILBOX_ENDPOINTS.search}?${params}`);
  const messages = (Array.isArray(payload?.messages) ? payload.messages : []).flatMap(item => {
    const message = item as Record<string, unknown>;
    const id = text(message?.id);
    return id ? [{
      id, threadId: text(message.threadId), from: text(message.from), subject: text(message.subject), snippet: text(message.snippet),
      date: typeof message.date === "number" || typeof message.date === "string" ? message.date : null,
      archived: message.archived === true,
      conversationId: message.conversationId ? text(message.conversationId) : null,
    }] : [];
  });
  return { messages, nextPageToken: payload?.nextPageToken ? text(payload.nextPageToken) : null };
}

export async function restoreMailboxMessage(account: string, messageId: string): Promise<MailboxRestore> {
  const result = await request<{ conversationId?: unknown; movedToInbox?: unknown }>(MAILBOX_ENDPOINTS.restore, { account, messageId, toInbox: true });
  const conversationId = text(result?.conversationId);
  if (!conversationId) throw new Error(t("crm.mailbox.restoreFailed"));
  return { conversationId, movedToInbox: result.movedToInbox === true };
}
