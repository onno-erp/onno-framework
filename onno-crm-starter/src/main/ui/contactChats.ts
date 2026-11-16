/** A unified chat always takes its preview and source from the latest underlying conversation. */
export function contactChats<T extends Record<string, unknown>>(rows: T[]): T[] {
  const contacts = new Map<string, T>();
  const timestamp = (row: T) => Date.parse(String(row.lastMessageAt || "")) || 0;
  for (const row of rows) {
    const key = String(row.customer || row.id);
    const previous = contacts.get(key);
    const latest = !previous || timestamp(row) > timestamp(previous) ? row : previous;
    contacts.set(key, { ...latest, unreadCount: Number(row.unreadCount || 0) + Number(previous?.unreadCount || 0) });
  }
  return [...contacts.values()].sort((a, b) => timestamp(b) - timestamp(a));
}

export type ReplyActivity = {id: string; conversationId: string; direction: string};
export type ReplySelection = {customer: string; inboundId?: string};
/** Initial history uses the latest message either way; later updates follow only new client messages. */
export function replySelection(customer: string, newestFirst: ReplyActivity[], previous: ReplySelection | null, hasDraft: boolean) {
  const inbound = newestFirst.find(entry => entry.direction === "INBOUND");
  const opening = previous?.customer !== customer;
  const target = opening
    ? newestFirst.find(entry => entry.direction === "INBOUND" || entry.direction === "OUTBOUND")
    : inbound?.id !== previous?.inboundId ? inbound : undefined;
  return {state: {customer, inboundId: inbound?.id}, conversationId: hasDraft ? undefined : target?.conversationId};
}
