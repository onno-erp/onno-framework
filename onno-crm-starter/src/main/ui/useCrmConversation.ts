import { text as t, useUiEvents, type EntityRecord } from "@onno/ui";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { request } from "./CrmWorkspace";
import { checkedJson, workspaceCommand, type CrmAttachment, type CrmConversationAction, type CrmDelivery, type CrmMessage, type CrmNote, type CrmTimelineEntry } from "./crmModel";

/** One entry of a contact's activity feed, newest first, as the server sends it. */
export type CrmActivityEntry = {
  id: string; conversationId: string; kind: string; direction: string; channel: string;
  authorName: string; authorAvatarUrl: string | null; mine: boolean; body: string; at: string;
  deliveryStatus: string; attachments?: CrmAttachment[]; bodyHtml?: string | null; senderAccount?: string | null;
};

/**
 * An app's own timeline entries for a conversation: orders, bookings, payments, a widget pinned at a
 * date. `useEntries` is a hook (it may fetch, subscribe, use the app's data hooks); keep the list of
 * sources stable (a module-level array) so the hooks run in the same order on every render.
 */
export type CrmTimelineSource = {
  id: string;
  useEntries: (context: { conversation: EntityRecord; customer: EntityRecord | null; customerId: string }) => readonly CrmTimelineEntry[];
};

export type UseCrmConversationOptions = {
  /** The conversation row (its `id` and `customer`); null while none is chosen. */
  conversation: EntityRecord | null;
  /** The inbox workspace it is used from; scopes delivery, commands and notes. */
  workspace?: string;
  /** Sees each loaded activity page, newest first (the inbox follows the contact's latest channel). */
  onActivity?: (entries: readonly CrmActivityEntry[], conversationId: string) => void;
};

/**
 * Everything one conversation's screen needs, loaded and kept live: the contact's timeline across
 * channels (messages and notes), the contact's fields, how a reply can go out (delivery), the host
 * commands it offers, and the calls that change it. The inbox is built on it, and so is any app
 * screen that shows a conversation somewhere else (a record page, a window, a contact page).
 */
export function useCrmConversation({ conversation, workspace, onActivity }: UseCrmConversationOptions) {
  const id = conversation ? String(conversation.id) : null;
  const customerId = String(conversation?.customer ?? "");
  const [messages, setMessages] = useState<CrmMessage[]>([]);
  const [notes, setNotes] = useState<CrmNote[]>([]);
  const [customer, setCustomer] = useState<EntityRecord | null>(null);
  const [delivery, setDelivery] = useState<CrmDelivery | null>(null);
  const [deliveryFailed, setDeliveryFailed] = useState(false);
  const [loadedActivityId, setLoadedActivityId] = useState<string | null>(null);
  const [loadedDeliveryId, setLoadedDeliveryId] = useState<string | null>(null);
  const [historyPages, setHistoryPages] = useState(1);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actions, setActions] = useState<CrmConversationAction[]>([]);
  const sequence = useRef(0);
  const idRef = useRef(id);
  idRef.current = id;
  const onActivityRef = useRef(onActivity);
  onActivityRef.current = onActivity;

  useEffect(() => { setDelivery(null); setError(null); }, [id]);
  useEffect(() => { setHistoryPages(1); }, [customerId]);

  const reload = useCallback(async () => {
    const current = ++sequence.current;
    if (!conversation || !id) {
      setMessages([]); setNotes([]); setCustomer(null); setLoadedActivityId(null);
      return;
    }
    const live = () => idRef.current === id && sequence.current === current;
    setDeliveryFailed(false);
    setError(null);
    // History/read acknowledgement must not wait for contact decoration or transport availability.
    await Promise.all([
      (async () => {
        try {
          const entries: CrmActivityEntry[] = []; let more = false; let cursor: string | null = null;
          for (let page = 0; page < historyPages; page++) {
            const query = new URLSearchParams({ limit: "100" });
            if (cursor) query.set("cursor", cursor);
            const response = await fetch(`/api/crm/contacts/${customerId}/activity?${query}`, { credentials: "same-origin" });
            const result = await checkedJson<{ rows?: CrmActivityEntry[]; hasMore: boolean; nextCursor: string | null }>(response, t("crm.error.history"));
            entries.push(...(result.rows ?? [])); more = result.hasMore; cursor = result.nextCursor;
            if (!more) break;
            if (!cursor) { if (live()) setError(t("crm.error.history")); return; }
          }
          if (!live()) return;
          setHasMoreHistory(more);
          const chronological = [...entries].reverse();
          setMessages(chronological.filter(entry => entry.kind !== "NOTE").map(entry => ({ ...entry, id: entry.id.replace(/^message:/, ""), sentAt: entry.at })) as CrmMessage[]);
          setNotes(chronological.filter(entry => entry.kind === "NOTE").map(entry => ({ id: entry.id, authorName: entry.authorName, authorAvatarUrl: entry.authorAvatarUrl, body: entry.body, createdAt: entry.at, mine: entry.mine })));
          setLoadedActivityId(id);
          onActivityRef.current?.(entries, id);
        } catch (loadError) {
          if (live()) setError(loadError instanceof Error ? loadError.message : t("crm.error.history"));
        }
      })(),
      (async () => {
        try {
          const next = customerId ? await request<{ fields: EntityRecord }>(`/contacts/${customerId}`) : null;
          if (live()) setCustomer(next?.fields ?? null);
        } catch (loadError) {
          if (live()) setError(loadError instanceof Error ? loadError.message : t("crm.error.conversation"));
        }
      })(),
      (async () => {
        try {
          const response = await fetch(`/api/crm/conversations/${id}/delivery${workspace ? `?workspace=${encodeURIComponent(workspace)}` : ""}`, { credentials: "same-origin" });
          const next = await checkedJson<CrmDelivery>(response, t("crm.error.connection"));
          if (!live()) return;
          setDelivery(next);
          setLoadedDeliveryId(id);
        } catch (loadError) {
          if (!live()) return;
          setDelivery(null);
          setDeliveryFailed(true);
          setError(loadError instanceof Error ? loadError.message : t("crm.error.connection"));
        }
      })(),
    ]);
  }, [conversation, workspace, historyPages]);

  useEffect(() => { void reload(); }, [reload]);
  useUiEvents(() => { void reload(); }, { types: ["created", "updated", "deleted"], entityType: "catalog", entityName: "CrmConversationMessages" });
  useUiEvents(() => { void reload(); }, { types: ["created", "updated", "deleted"], entityType: "comment", entityName: "crm_conversations", id: id || undefined });
  useUiEvents(() => { void reload(); }, { types: ["updated"], entityType: "page", entityName: "crm-inbox-workspaces" });

  // The app's commands for this conversation (Commands<Conversation>), as the workspace offers them.
  useEffect(() => {
    let live = true; setActions([]);
    if (id && workspace) void request<CrmConversationAction[]>(`/inbox-workspaces/${encodeURIComponent(workspace)}/conversations/${id}/actions`)
      .then(items => { if (live) setActions(items); }).catch(() => {});
    return () => { live = false; };
  }, [id, workspace, conversation]);

  const timeline = useMemo<CrmTimelineEntry[]>(() => [
    ...messages.map(message => ({ id: `message:${message.id}`, at: message.sentAt, type: "message" as const, message })),
    ...notes.map(note => ({ id: `comment:${note.id}`, at: note.createdAt || "", type: "note" as const, note })),
  ].sort((left, right) => new Date(left.at).getTime() - new Date(right.at).getTime()), [messages, notes]);

  /** Runs one of {@link actions} and reloads; throws the server's reason when it is refused. */
  const execute = useCallback(async (key: string, inputs: Readonly<Record<string, unknown>> = {}) => {
    if (!id || !workspace || !actions.some(a => a.id === key && a.enabled)) throw new Error(t("crm.error.action"));
    const result = await workspaceCommand(workspace, `/api/crm/inbox-workspaces/${encodeURIComponent(workspace)}/conversations/${id}/actions/${encodeURIComponent(key)}`, { inputs });
    await reload();
    return result;
  }, [id, workspace, actions, reload]);

  return {
    id, customerId, conversation, workspace,
    messages, notes, timeline, customer,
    /** How a reply can go out now; null until loaded or when the channel could not be asked. */
    delivery, deliveryFailed,
    /** The delivery and the timeline above belong to the current conversation (not a previous one). */
    deliveryLoaded: !!id && loadedDeliveryId === id,
    activityLoaded: !!id && loadedActivityId === id,
    hasMoreHistory, loadEarlier: () => setHistoryPages(pages => pages + 1),
    error, setError,
    actions, execute, reload,
  };
}

export type CrmConversationState = ReturnType<typeof useCrmConversation>;

/** Merges the app's timeline sources into a conversation's own timeline, in date order. */
export function useTimelineWithSources(state: CrmConversationState, sources: readonly CrmTimelineSource[] = []): CrmTimelineEntry[] {
  const extra: CrmTimelineEntry[] = [];
  for (const source of sources) {
    // A stable sources array keeps these hook calls in the same order (see CrmTimelineSource).
    // eslint-disable-next-line react-hooks/rules-of-hooks
    const entries = source.useEntries({ conversation: state.conversation ?? {}, customer: state.customer, customerId: state.customerId });
    extra.push(...entries.map(entry => ({ ...entry, id: `${source.id}:${entry.id}` }) as CrmTimelineEntry));
  }
  if (!extra.length) return state.timeline;
  return [...state.timeline, ...extra].sort((left, right) => new Date(left.at).getTime() - new Date(right.at).getTime());
}
