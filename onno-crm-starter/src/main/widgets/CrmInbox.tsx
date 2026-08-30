import {
  Badge,
  Button,
  Segmented,
  Textarea,
  api,
  registerListRenderer,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useUiEvents,
  type EntityRecord,
  type ListRendererProps,
} from "@onno/widget-sdk";
import { Activity, Mail, MessageCircle, MessageSquare, PhoneCall, Send } from "lucide-react";

type Message = {
  id: string;
  kind: "CUSTOMER_MESSAGE" | "AGENT_REPLY" | "SYSTEM_EVENT";
  direction: "INBOUND" | "OUTBOUND" | "INTERNAL";
  channel: string;
  authorName: string;
  body: string;
  sentAt: string;
  deliveryStatus: string;
};

type Comment = {
  id: string;
  authorName: string | null;
  authorAvatarUrl: string | null;
  body: string;
  createdAt: string | null;
  mine: boolean;
};

type ComposerMode = "reply" | "note";

function string(row: EntityRecord | null, key: string, fallback = ""): string {
  const value = row?.[key];
  return value == null ? fallback : String(value);
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");
}

function compactTime(value: unknown): string {
  if (!value) return "";
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  return sameDay
    ? new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(date)
    : new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(date);
}

function fullTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      }).format(date);
}

function csrfToken(): string | undefined {
  const token = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith("XSRF-TOKEN="));
  return token ? decodeURIComponent(token.slice("XSRF-TOKEN=".length)) : undefined;
}

async function crmCommand<T>(path: string, body: unknown = {}): Promise<T> {
  const csrf = csrfToken();
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      ...(csrf ? { "X-XSRF-TOKEN": csrf } : {}),
    },
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

async function loadMessages(id: string): Promise<Message[]> {
  const response = await fetch(`/api/crm/conversations/${id}/messages`, {
    credentials: "same-origin",
  });
  if (!response.ok) throw new Error("Could not load this conversation");
  return response.json() as Promise<Message[]>;
}

async function loadComments(id: string): Promise<Comment[]> {
  const response = await fetch(`/api/comments/catalogs/crm_conversations/${id}`, {
    credentials: "same-origin",
  });
  if (!response.ok) throw new Error("Could not load internal comments");
  return response.json() as Promise<Comment[]>;
}

function Avatar({
  name,
  url,
  channel,
  className = "size-8",
}: {
  name: string;
  url?: string | null;
  channel?: string | null;
  className?: string;
}) {
  const [broken, setBroken] = useState(false);
  const source = url || null;
  useEffect(() => setBroken(false), [source]);
  return (
    <span className={`relative ${className} shrink-0`}>
      <span className="relative flex size-full items-center justify-center overflow-hidden rounded-full border border-border bg-muted text-[10px] font-semibold text-foreground">
        {initials(name)}
        {source && !broken ? (
          <img
            src={source}
            alt=""
            className="absolute inset-0 size-full object-cover"
            onError={() => setBroken(true)}
          />
        ) : null}
      </span>
      {channel ? <ChannelIcon channel={channel} avatarBadge /> : null}
    </span>
  );
}

function ChannelIcon({
  channel,
  comment = false,
  avatarBadge = false,
}: {
  channel: string;
  comment?: boolean;
  avatarBadge?: boolean;
}) {
  const normalized = channel.trim().replaceAll("_", " ").toLocaleLowerCase();
  const label = comment
    ? "Internal comment"
    : normalized ? normalized.replace(/\b\w/g, (letter) => letter.toUpperCase()) : "Conversation source";
  const Icon = comment
    ? MessageSquare
    : normalized === "email"
    ? Mail
    : normalized === "telegram"
    ? Send
    : normalized === "whatsapp" || normalized === "phone"
    ? PhoneCall
    : MessageCircle;
  const avatarBadgeColor = normalized === "telegram"
    ? "bg-[#229ED9] text-white"
    : normalized === "whatsapp"
    ? "bg-[#25D366] text-white"
    : normalized === "email"
    ? "bg-[#6366F1] text-white"
    : "bg-[#8B5CF6] text-white";
  return (
    <span
      aria-label={label}
      title={label}
      className={avatarBadge
        ? `absolute -bottom-0.5 -right-0.5 z-10 flex size-5 items-center justify-center rounded-full border-2 border-card ${avatarBadgeColor}`
        : comment
        ? "flex size-6 shrink-0 items-center justify-center rounded-full bg-amber-500/15 text-amber-700 dark:text-amber-300"
        : "flex size-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"}
    >
      <Icon size={avatarBadge ? 12 : 14} strokeWidth={1.8} />
    </span>
  );
}

function ConversationRow({
  row,
  selected,
  onSelect,
}: {
  row: EntityRecord;
  selected: boolean;
  onSelect: () => void;
}) {
  const customer = string(row, "customerDisplay", string(row, "description", "Unknown customer"));
  const unread = Number(row.unreadCount ?? 0);
  const channel = string(row, "channelDisplay", string(row, "channel", "Channel"));
  return (
    <button
      type="button"
      onClick={onSelect}
      className={selected
        ? "w-full border-l-2 border-primary bg-primary/10 px-3 py-3 text-left"
        : "w-full border-l-2 border-transparent px-3 py-3 text-left transition-colors hover:bg-muted/60"}
    >
      <div className="flex items-start gap-2.5">
        <Avatar name={customer} url={string(row, "customerAvatar")} channel={channel} className="size-9" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[13px] font-semibold text-foreground">{customer}</span>
            <time className="ml-auto shrink-0 text-[10px] tabular-nums text-muted-foreground">
              {compactTime(row.lastMessageAt)}
            </time>
          </div>
          <div className="mt-0.5 truncate text-[11px] font-medium text-foreground/80">
            {string(row, "subject")}
          </div>
          <div className="mt-1 flex items-center gap-1.5">
            <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground">
              {string(row, "lastMessagePreview", "No messages yet")}
            </span>
            {unread > 0 ? (
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-[10px] font-bold text-primary-foreground">
                {unread > 9 ? "9+" : unread}
              </span>
            ) : null}
          </div>
        </div>
      </div>
    </button>
  );
}

function TimelineMessage({ message, avatarUrl }: { message: Message; avatarUrl?: string | null }) {
  const outbound = message.direction === "OUTBOUND";
  return (
    <div className={outbound ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      <Avatar name={message.authorName} url={avatarUrl} channel={message.channel} />
      <div className={outbound
        ? "max-w-[72%] rounded-panel rounded-br-field bg-primary px-3.5 py-2.5 text-primary-foreground"
        : "max-w-[72%] rounded-panel rounded-bl-field border border-border bg-card px-3.5 py-2.5 text-foreground"}
      >
        <div className="flex items-center gap-1.5">
          <span className={outbound
            ? "text-[10px] font-semibold text-primary-foreground/75"
            : "text-[10px] font-semibold text-muted-foreground"}
          >
            {message.authorName}
          </span>
        </div>
        <p className="mt-1 whitespace-pre-wrap text-[13px] leading-5">{message.body}</p>
        <div className={outbound
          ? "mt-1 flex justify-end gap-1.5 text-[10px] text-primary-foreground/70"
          : "mt-1 flex justify-end gap-1.5 text-[10px] text-muted-foreground"}
        >
          <time>{fullTime(message.sentAt)}</time>
          {outbound ? <span>✓ {message.deliveryStatus.toLowerCase()}</span> : null}
        </div>
      </div>
    </div>
  );
}

function TimelineSystemEvent({ message }: { message: Message }) {
  return (
    <div className="flex items-center justify-center gap-2 px-6 py-1 text-center text-[10px] text-muted-foreground">
      <span className="h-px min-w-5 flex-1 bg-border" />
      <span className="inline-flex max-w-[78%] items-center gap-1.5 rounded-pill bg-muted px-2.5 py-1">
        <Activity size={12} className="shrink-0 text-primary" aria-hidden="true" />
        <span>{message.body}</span>
        <time className="shrink-0 text-muted-foreground/70">{fullTime(message.sentAt)}</time>
      </span>
      <span className="h-px min-w-5 flex-1 bg-border" />
    </div>
  );
}

function TimelineComment({ comment }: { comment: Comment }) {
  const author = comment.authorName || "Team member";
  return (
    <div className={comment.mine ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      <Avatar name={author} url={comment.authorAvatarUrl} />
      <div className={comment.mine
        ? "max-w-[72%] rounded-panel rounded-br-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"
        : "max-w-[72%] rounded-panel rounded-bl-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"}
      >
        <div className="flex items-center gap-1.5">
          <ChannelIcon channel="" comment />
          <span className="text-[10px] font-semibold text-amber-700 dark:text-amber-300">{author}</span>
          <span className="text-[9px] font-medium uppercase tracking-wide text-muted-foreground">Internal</span>
        </div>
        <p className="mt-1 whitespace-pre-wrap text-[13px] leading-5">{comment.body}</p>
        <time className="mt-1 block text-right text-[10px] text-muted-foreground">
          {comment.createdAt ? fullTime(comment.createdAt) : ""}
        </time>
      </div>
    </div>
  );
}

function CustomerPanel({ customer, row }: { customer: EntityRecord | null; row: EntityRecord }) {
  const name = string(customer, "description", string(row, "customerDisplay", "Customer"));
  const avatarUrl = string(customer, "avatarUrl", string(row, "customerAvatar"));
  const tags = string(customer, "tags").split(",").map((tag) => tag.trim()).filter(Boolean);
  return (
    <aside className="hidden min-h-0 min-w-0 overflow-y-auto border-l border-border bg-card xl:!block">
      <div className="border-b border-border px-4 py-4 text-center">
        <div className="flex justify-center">
          <Avatar
            name={name}
            url={avatarUrl}
            channel={string(row, "channelDisplay", string(row, "channel"))}
            className="size-14"
          />
        </div>
        <div className="mt-2 text-sm font-semibold text-foreground">{name}</div>
        <div className="text-[11px] text-muted-foreground">{string(customer, "company", "Individual customer")}</div>
        <div className="mt-2 flex flex-wrap justify-center gap-1">
          <Badge variant="secondary">{string(customer, "stageDisplay", "Lead")}</Badge>
          {tags.map((tag) => <Badge key={tag} variant="outline">{tag}</Badge>)}
        </div>
      </div>
      <div className="space-y-4 px-4 py-4 text-xs">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Contact</div>
          <div className="mt-2 space-y-1.5 text-foreground">
            <div className="truncate">{string(customer, "email", "No email")}</div>
            <div>{string(customer, "phone", "No phone")}</div>
            <div>{string(customer, "city", "No city")}</div>
          </div>
        </div>
        <div className="border-t border-border pt-4">
          <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Relationship</div>
          <dl className="mt-2 space-y-2">
            <div className="flex justify-between gap-3"><dt className="text-muted-foreground">Owner</dt><dd className="text-right text-foreground">{string(customer, "ownerDisplay", "Unassigned")}</dd></div>
            <div className="flex justify-between gap-3"><dt className="text-muted-foreground">Source</dt><dd className="text-right text-foreground">{string(customer, "source", "Unknown")}</dd></div>
            <div className="flex justify-between gap-3"><dt className="text-muted-foreground">Priority</dt><dd className="text-right text-foreground">{string(row, "priorityDisplay", "Normal")}</dd></div>
          </dl>
        </div>
        <div className="border-t border-border pt-4">
          <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">Assignee</div>
          <div className="mt-2 text-foreground">{string(row, "assigneeDisplay", "Unassigned")}</div>
        </div>
      </div>
    </aside>
  );
}

function CrmInbox({ rows, open }: ListRendererProps) {
  const [selectedId, setSelectedId] = useState<string | null>(rows.length ? String(rows[0].id) : null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [comments, setComments] = useState<Comment[]>([]);
  const [customer, setCustomer] = useState<EntityRecord | null>(null);
  const [agents, setAgents] = useState<EntityRecord[]>([]);
  const [mode, setMode] = useState<ComposerMode>("reply");
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const timelineEndRef = useRef<HTMLDivElement | null>(null);

  const selected = useMemo(
    () => rows.find((row) => String(row.id) === selectedId) ?? null,
    [rows, selectedId]
  );

  const agentAvatars = useMemo(() => new Map(
    agents.map((agent) => [string(agent, "description"), string(agent, "avatarUrl")])
  ), [agents]);

  const customerAvatarUrl = string(customer, "avatarUrl", string(selected, "customerAvatar"));

  const timeline = useMemo(() => [
    ...messages.map((message) => ({ id: `message:${message.id}`, at: message.sentAt, message, comment: null as Comment | null })),
    ...comments.map((comment) => ({
      id: `comment:${comment.id}`,
      at: comment.createdAt || "",
      message: null as Message | null,
      comment,
    })),
  ].sort((left, right) => new Date(left.at).getTime() - new Date(right.at).getTime()), [comments, messages]);

  useEffect(() => {
    timelineEndRef.current?.scrollIntoView({ block: "end" });
  }, [selectedId, timeline.length]);

  useEffect(() => {
    if (selectedId && rows.some((row) => String(row.id) === selectedId)) return;
    setSelectedId(rows.length ? String(rows[0].id) : null);
  }, [rows, selectedId]);

  useEffect(() => {
    void api.listCatalog("crm_agents").then(setAgents).catch(() => setAgents([]));
  }, []);

  const loadSelected = useCallback(async () => {
    if (!selected) {
      setMessages([]);
      setComments([]);
      setCustomer(null);
      return;
    }
    const id = String(selected.id);
    const customerId = String(selected.customer ?? "");
    try {
      const [nextMessages, nextComments, nextCustomer] = await Promise.all([
        loadMessages(id),
        loadComments(id),
        customerId ? api.getCatalogItem("crm_customers", customerId) : Promise.resolve(null),
      ]);
      setMessages(nextMessages);
      setComments(nextComments);
      setCustomer(nextCustomer);
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Could not load the conversation");
    }
  }, [selected]);

  useEffect(() => { void loadSelected(); }, [loadSelected]);

  useUiEvents(
    () => { void loadSelected(); },
    { types: ["created", "updated", "deleted"], entityType: "catalog", entityName: "CrmConversationMessages" }
  );

  useUiEvents(
    () => { void loadSelected(); },
    { types: ["created", "updated", "deleted"], entityType: "comment", entityName: "crm_conversations", id: selectedId || undefined }
  );

  const select = (row: EntityRecord) => {
    const id = String(row.id);
    setSelectedId(id);
    if (Number(row.unreadCount ?? 0) > 0) {
      void crmCommand(`/api/crm/conversations/${id}/read`).catch(() => {});
    }
  };

  const submit = async () => {
    if (!selected || !draft.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      if (mode === "note") {
        await crmCommand(`/api/comments/catalogs/crm_conversations/${String(selected.id)}`, {
          body: draft,
          parentId: null,
        });
      } else {
        await crmCommand(`/api/crm/conversations/${String(selected.id)}/messages`, {
          body: draft,
        });
      }
      setDraft("");
      await loadSelected();
    } catch (sendError) {
      setError(sendError instanceof Error ? sendError.message : "Could not send");
    } finally {
      setBusy(false);
    }
  };

  const assignToMe = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await crmCommand(`/api/crm/conversations/${String(selected.id)}/assign-to-me`);
      await loadSelected();
    } catch (assignError) {
      setError(assignError instanceof Error ? assignError.message : "Could not assign");
    } finally {
      setBusy(false);
    }
  };

  const toggleClosed = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await crmCommand(`/api/crm/conversations/${String(selected.id)}/closed`, {
        closed: string(selected, "statusDisplay") !== "Closed",
      });
      await loadSelected();
    } catch (closeError) {
      setError(closeError instanceof Error ? closeError.message : "Could not update status");
    } finally {
      setBusy(false);
    }
  };

  if (!rows.length) {
    return (
      <div className="rounded-panel border border-dashed border-border px-6 py-16 text-center">
        <div className="text-sm font-medium text-foreground">No conversations match these filters</div>
        <div className="mt-1 text-xs text-muted-foreground">Clear a filter or create a conversation.</div>
      </div>
    );
  }

  return (
    <div
      data-testid="crm-inbox-workspace"
      className="grid h-full min-h-0 w-full max-h-full !grid-cols-1 overflow-hidden rounded-panel border border-border bg-background md:!grid-cols-[260px_minmax(360px,1fr)] xl:!grid-cols-[300px_minmax(420px,1fr)_280px]"
      style={{ height: "100%" }}
    >
      <section className="hidden min-h-0 min-w-0 flex-col border-r border-border bg-card md:!flex">
        <div className="shrink-0 border-b border-border px-3 py-3">
          <div className="text-sm font-semibold text-foreground">Conversations</div>
          <div className="mt-0.5 text-[11px] text-muted-foreground">{rows.length} in the current view</div>
        </div>
        <div className="min-h-0 flex-1 divide-y divide-border overflow-y-auto">
          {rows.map((row) => (
            <ConversationRow
              key={String(row.id)}
              row={row}
              selected={String(row.id) === selectedId}
              onSelect={() => select(row)}
            />
          ))}
        </div>
      </section>

      {selected ? (
        <main className="flex min-h-0 min-w-0 flex-col bg-background">
          <header className="flex shrink-0 flex-wrap items-center gap-2 border-b border-border bg-card px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold text-foreground">{string(selected, "customerDisplay", "Customer")}</div>
              <div className="truncate text-[11px] text-muted-foreground">{string(selected, "subject")}</div>
            </div>
            <Badge variant="secondary">{string(selected, "statusDisplay", string(selected, "status"))}</Badge>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void assignToMe()}>Assign to me</Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => open(selected)}>Details</Button>
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void toggleClosed()}>
              {string(selected, "statusDisplay") === "Closed" ? "Reopen" : "Close"}
            </Button>
          </header>

          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-5">
            <div className="mx-auto w-fit rounded-pill bg-muted px-3 py-1 text-[10px] text-muted-foreground">Today</div>
            {timeline.map((entry) => entry.message ? (
              entry.message.kind === "SYSTEM_EVENT" ? (
                <TimelineSystemEvent key={entry.id} message={entry.message} />
              ) : (
                <TimelineMessage
                  key={entry.id}
                  message={entry.message}
                  avatarUrl={entry.message.direction === "INBOUND"
                    ? customerAvatarUrl
                    : agentAvatars.get(entry.message.authorName)}
                />
              )
            ) : entry.comment ? <TimelineComment key={entry.id} comment={entry.comment} /> : null)}
            {!timeline.length && !error ? (
              <div className="py-16 text-center text-xs text-muted-foreground">No messages yet</div>
            ) : null}
            <div ref={timelineEndRef} />
          </div>

          <div className="shrink-0 border-t border-border bg-card p-3">
            <div className="mb-2 flex items-center justify-between gap-3">
              <Segmented
                value={mode}
                onChange={(value: ComposerMode) => setMode(value)}
                options={[{ value: "reply", label: "Reply" }, { value: "note", label: "Internal note" }]}
              />
              <span className="text-[10px] text-muted-foreground">
                {mode === "reply" ? string(selected, "channelDisplay", "Channel") : "Visible to your team only"}
              </span>
            </div>
            <Textarea
              value={draft}
              onChange={(event: { target: { value: string } }) => setDraft(event.target.value)}
              onKeyDown={(event: { key: string; metaKey: boolean; ctrlKey: boolean; preventDefault: () => void }) => {
                if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
                  event.preventDefault();
                  void submit();
                }
              }}
              placeholder={mode === "reply" ? "Write a reply…" : "Leave an internal note…"}
              className="min-h-24 resize-none"
            />
            <div className="mt-2 flex items-center gap-2">
              {error ? <span className="min-w-0 flex-1 truncate text-xs text-destructive">{error}</span> : <span className="flex-1 text-[10px] text-muted-foreground">⌘ Enter to send</span>}
              <Button disabled={busy || !draft.trim()} onClick={() => void submit()}>
                {busy ? "Sending…" : mode === "reply" ? "Send reply" : "Add note"}
              </Button>
            </div>
          </div>
        </main>
      ) : null}

      {selected ? <CustomerPanel customer={customer} row={selected} /> : null}
    </div>
  );
}

registerListRenderer("crmInbox", CrmInbox);
