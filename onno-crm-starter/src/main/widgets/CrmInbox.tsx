import { ConversationList, type ConversationPagination } from "./ConversationList";
import { composerState } from "./composerState";
import { useConversationStatuses } from "./ConversationStatuses";
import { ExtensionSlot, type ExtensionContext } from "@onno/widget-sdk";
import { ChatGroupActions } from "./ChatGroupActions";
import { useChatGroups, type ChatGroup, type GroupChange } from "./chatGroups";
import { NoteInput } from "./NoteInput";
import { contactChats, replySelection, type ReplySelection } from "./contactChats";
import { ContactRowMenu } from "./ContactRowMenu";
import { useInboxEscape } from "./useInboxEscape";
import { useConversationRead } from "./useConversationRead";
import { ChatMessageBody } from "@onno/widget-sdk";
import { useId } from "react";
import { ChannelLogo } from "./ChannelLogo";
import { request, ContactPanel, useWorkspace, action, rowValue, type Config, type ConversationFolder } from "./CrmWorkspace";
import {
  Badge,
  Input,
  EntityListWidget,
  registerWidget,
  Button,
  Segmented,
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
  Textarea,
  CommentBody,
  api,
  registerListRenderer,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useUiEvents,
  type DashboardWidgetMeta,
  type EntityRecord,
  type ListRendererProps,
} from "@onno/widget-sdk";
import { Activity, Folder, ChevronRight, ArrowLeft, PanelRight, X } from "lucide-react";

type Message = {
  id: string;
  conversationId?: string;
  kind: "CUSTOMER_MESSAGE" | "AGENT_REPLY" | "SYSTEM_EVENT";
  direction: "INBOUND" | "OUTBOUND" | "INTERNAL";
  channel: string;
  authorName: string;
  body: string;
  sentAt: string;
  deliveryStatus: string;
};

type DeliveryConnection = { connected: boolean; label: string; maxTextLength: number; replyCapability: "AVAILABLE" | "READ_ONLY" | "WINDOW_CLOSED"; replyReason: string };

type Comment = {
  id: string;
  authorName: string | null;
  authorAvatarUrl: string | null;
  body: string;
  createdAt: string | null;
  mine: boolean;
};

type ComposerMode = "reply" | "note";

// One surface treatment for the header, composer, and contact-details island.
const islandSurface = "rounded-panel bg-card dark:bg-muted/40 backdrop-blur-md shadow-md shadow-black/10 ring-1 ring-border/60";

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

async function loadMessages(id: string, workspaceKey?: string): Promise<Message[]> {
  const response = await fetch(`/api/crm/conversations/${id}/messages${workspaceKey ? `?workspace=${encodeURIComponent(workspaceKey)}` : ""}`, {
    credentials: "same-origin",
  });
  if (!response.ok) throw new Error("Could not load this conversation");
  return response.json() as Promise<Message[]>;
}

async function loadComments(id: string, workspaceKey?: string): Promise<Comment[]> {
  const response = await fetch(workspaceKey ? `/api/crm/inbox-workspaces/${workspaceKey}/conversations/${id}/comments` : `/api/comments/catalogs/crm_conversations/${id}`, {
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

function ChannelIcon({ channel, comment = false, avatarBadge = false }: { channel: string; comment?: boolean; avatarBadge?: boolean }) {
  return <span title={comment ? "Internal note" : channel} className={avatarBadge
    ? "absolute -bottom-0.5 -right-0.5 z-10 flex size-3.5 items-center justify-center rounded-full bg-card ring-1 ring-card"
    : "flex size-6 shrink-0 items-center justify-center"}>
    <ChannelLogo channel={comment ? "internal" : channel} className={avatarBadge ? "size-3.5" : "size-4"} />
  </span>;
}

function conversationChannel(row: EntityRecord | null): string {
  return string(row,"channel","Channel");
}

function ConversationRow({
  row,
  selected,
  onSelect,
  config,
  groups, groupKey, onGroupChange, workspaceKey,
}: {
  workspaceKey?: string;
  groups?: ChatGroup[]; groupKey?: string; onGroupChange?: (change: GroupChange) => Promise<void>;
  config?: Config;
  row: EntityRecord;
  selected: boolean;
  onSelect: () => void;
}) {
  const customer = string(row, "customerDisplay", string(row, "description", "Unknown customer"));
  const unread = Number(row.unreadCount ?? 0);
  const channel = conversationChannel(row);
  return (
    <ContactRowMenu record={row} workspaceKey={workspaceKey} groups={groups} groupKey={groupKey} conversationId={String(row.id)} onGroupChange={onGroupChange} id={String(row.customer ?? "")} canEdit={!!action(config, "edit")} onOpen={onSelect}>
    <Button variant="ghost"
      type="button"
      onClick={onSelect}
      aria-current={selected ? "true" : undefined}
      className={`h-auto whitespace-normal font-normal relative block min-w-0 w-full max-w-full rounded-field px-3 py-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected
        ? "bg-primary/20"
        : "bg-transparent hover:bg-muted/50 after:absolute after:bottom-0 after:left-16 after:right-3 after:h-px after:bg-border/40 last:after:hidden"}`}
    >
      <div className="flex items-start gap-2.5">
        {config?.showAvatar !== false && <Avatar name={customer} url={string(row, "customerAvatar")} channel={channel} className="size-10" />}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[13px] font-semibold text-foreground">{config ? rowValue(row, config.listTitle) : customer}</span>
            <time className="ml-auto shrink-0 text-[10px] tabular-nums text-muted-foreground">
              {config?.showTimestamps !== false ? compactTime(row.lastMessageAt) : ""}
            </time>
          </div>
          <div className="mt-0.5 truncate text-[11px] font-medium text-foreground/80">
            {string(row, "inboxDisplay", channel)}
          </div>
          <div className="mt-1 flex items-center gap-1.5">
            <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground">
              {config ? rowValue(row, config.listPreview) : string(row, "lastMessagePreview", "No messages yet")}
            </span>
            {unread > 0 ? (
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-[10px] font-bold text-primary-foreground">
                {unread > 9 ? "9+" : unread}
              </span>
            ) : null}
          </div>
        </div>
      </div>
    </Button>
    </ContactRowMenu>
  );
}

function TimelineMessage({ message, avatarUrl, retry, busy, config, channel }: { channel?: string; config?: Config; message: Message; avatarUrl?: string | null; retry: () => void; busy: boolean }) {
  const outbound = message.direction === "OUTBOUND";
  return (
    <div className={outbound ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      {config?.showAvatar !== false && <Avatar name={message.authorName} url={avatarUrl} channel={channel || message.channel} />}
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
        <ChatMessageBody message={message} fallback={<p className="mt-1 whitespace-pre-wrap text-[13px] leading-5">{message.body}</p>} />
        <div className={outbound
          ? "mt-1 flex justify-end gap-1.5 text-[10px] text-primary-foreground/70"
          : "mt-1 flex justify-end gap-1.5 text-[10px] text-muted-foreground"}
        >
          {config?.showTimestamps !== false && <time>{fullTime(message.sentAt)}</time>}
          {outbound && config?.showDeliveryStatus !== false ? <span>{["SENT", "DELIVERED"].includes(message.deliveryStatus) ? "✓ " : ""}{message.deliveryStatus.toLowerCase()}</span> : null}
          {outbound && message.deliveryStatus === "FAILED" && action(config, "retry") ? (
            <Button variant="ghost" type="button" disabled={busy} onClick={retry} className="h-auto p-0 text-inherit underline disabled:opacity-50"
              title="Check Telegram before retrying: an interrupted attempt may already have sent the message.">{action(config, "retry")?.label}</Button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function TimelineSystemEvent({ message, config }: { message: Message; config?: Config }) {
  return (
    <div className="flex items-center justify-center gap-2 px-6 py-1 text-center text-[10px] text-muted-foreground">
      <span className="h-px min-w-5 flex-1 bg-border" />
      <span className="inline-flex max-w-[78%] items-center gap-1.5 rounded-pill bg-muted px-2.5 py-1">
        <Activity size={12} className="shrink-0 text-primary" aria-hidden="true" />
        <span>{message.body}</span>
        {config?.showTimestamps !== false && <time className="shrink-0 text-muted-foreground/70">{fullTime(message.sentAt)}</time>}
      </span>
      <span className="h-px min-w-5 flex-1 bg-border" />
    </div>
  );
}

function TimelineComment({ comment, config }: { comment: Comment; config?: Config }) {
  const author = comment.authorName || "Team member";
  return (
    <div className={comment.mine ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      {config?.showAvatar !== false && <Avatar name={author} url={comment.authorAvatarUrl} />}
      <div className={comment.mine
        ? "max-w-[72%] rounded-panel rounded-br-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"
        : "max-w-[72%] rounded-panel rounded-bl-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"}
      >
        <div className="flex items-center gap-1.5">
          <ChannelIcon channel="" comment />
          <span className="text-[10px] font-semibold text-amber-700 dark:text-amber-300">{author}</span>
          <span className="text-[9px] font-medium uppercase tracking-wide text-muted-foreground">Internal</span>
        </div>
        <p className="mt-1 whitespace-pre-wrap text-[13px] leading-5"><CommentBody body={comment.body} /></p>
        <time className="mt-1 block text-right text-[10px] text-muted-foreground">
          {config?.showTimestamps !== false && comment.createdAt ? fullTime(comment.createdAt) : ""}
        </time>
      </div>
    </div>
  );
}

function matchesFolder(row: EntityRecord, folder: ConversationFolder): boolean {
  return (!folder.conversationIds?.length || folder.conversationIds.includes(String(row.id)))
    && (!folder.channels.length || [...folder.channels, ...(folder.channelIds ?? [])].includes(String(row.channel)))
    && (!folder.statuses.length || [...folder.statuses, ...(folder.statusIds ?? [])].includes(String(row.status)))
    && (!folder.priorities.length || [...folder.priorities, ...(folder.priorityIds ?? [])].includes(String(row.priority)))
    && (!folder.unreadOnly || Number(row.unreadCount ?? 0) > 0);
}

function CrmInbox({ rows: channelRows, open, scopedConfig, workspaceKey, ...pagination }: Pick<ListRendererProps, "rows" | "open"> & ConversationPagination & { scopedConfig?: Config; workspaceKey?: string }) {
  const rows = useMemo(() => contactChats(channelRows), [channelRows]);
  const { workspace, error: configError } = useWorkspace();
  const config = scopedConfig ?? workspace?.config;
  const command = <T,>(path: string, body: unknown = {}) => crmCommand<T>(workspaceKey && path.startsWith("/api/crm/conversations/") ? `${path}?workspace=${encodeURIComponent(workspaceKey)}` : path, body);
  const [folderKey, setFolderKey] = useState<string | null>(null);
  const [lastFolderKey, setLastFolderKey] = useState<string | null>(null);
  const openFolder = (key: string) => { setLastFolderKey(key); setFolderKey(key); };
  const chatGroups = useChatGroups(workspaceKey);
  const customFolders: ConversationFolder[] = chatGroups.groups.map(group => ({key:group.key,label:group.label,conversationIds:[],channelIds:[],statusIds:[],priorityIds:[],channels:[],statuses:[],priorities:[],unreadOnly:false}));
  const folders = [...customFolders, ...(config?.folders ?? [])];
  const rootListRef = useRef<HTMLDivElement | null>(null);
  const detailListRef = useRef<HTMLDivElement | null>(null);
  const previousFolderRef = useRef<string | null>(null);
  useEffect(() => {
    const previous = previousFolderRef.current;
    if (folderKey) detailListRef.current?.querySelector<HTMLButtonElement>("button")?.focus({ preventScroll: true });
    else if (previous) rootListRef.current?.querySelector<HTMLButtonElement>(`[data-folder-key="${previous}"]`)?.focus({ preventScroll: true });
    previousFolderRef.current = folderKey;
  }, [folderKey]);
  const activeFolder = folders.find(folder => folder.key === folderKey);
  // The first matching folder owns a chat in the list, avoiding duplicate entries.
  const owner = (row: EntityRecord) => {
    const group = chatGroups.groups.find(group => group.customerIds.includes(String(row.customer)));
    return group ? customFolders.find(folder => folder.key === group.key) : config?.folders.find(folder => matchesFolder(row, folder));
  };
  const folderRows = (key: string) => rows.filter(row => owner(row)?.key === key);
  // Keep the outgoing detail page populated while it slides away.
  const displayedFolder = activeFolder ?? folders.find(folder => folder.key === lastFolderKey) ?? folders[0];
  const detailRows = displayedFolder ? folderRows(displayedFolder.key) : [];
  useEffect(() => {
    if (folderKey && !folders.some(folder => folder.key === folderKey)) setFolderKey(null);
  }, [config, folderKey, chatGroups.groups]);
  const { statuses: conversationStatuses } = useConversationStatuses();
  const [selectedId, setSelectedId] = useState<string | null>(rows.length ? String(rows[0].id) : null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loadedConversationId, setLoadedConversationId] = useState<string | null>(null);
  const [deliveryFailed, setDeliveryFailed] = useState(false);
  const [delivery, setDelivery] = useState<DeliveryConnection | null>(null);
  const selectionDismissedRef = useRef(false);
  const inboxRootRef = useRef<HTMLDivElement | null>(null);
  const selectionRef = useRef(selectedId);
  selectionRef.current = selectedId;
  const [comments, setComments] = useState<Comment[]>([]);
  const [customer, setCustomer] = useState<EntityRecord | null>(null);

  const [mode, setMode] = useState<ComposerMode>("reply");
  const [draft, setDraft] = useState("");
  const [noteDraft, setNoteDraft] = useState("");
  const [noteBody, setNoteBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [historyPages, setHistoryPages] = useState(1);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [contactPanelOpen, setContactPanelOpen] = useState(false);
  const contactPanelToggleRef = useRef<HTMLButtonElement | null>(null);
  const closeContactPanel = () => { setContactPanelOpen(false); contactPanelToggleRef.current?.focus(); };
  const contactPanelId = useId();
  const replySelectionRef = useRef<ReplySelection | null>(null);
  const draftRef = useRef(draft); draftRef.current = draft || noteDraft;
  const [error, setError] = useState<string | null>(null);
  const timelineEndRef = useRef<HTMLDivElement | null>(null);
  const composerRef = useRef<HTMLDivElement | null>(null);
  const [composerHeight, setComposerHeight] = useState(220);
  useEffect(() => {
    const composer = composerRef.current;
    if (!composer) return;
    const measure = () => setComposerHeight(composer.getBoundingClientRect().height);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(composer);
    return () => observer.disconnect();
  }, [selectedId]);

  const selected = useMemo(
    () => channelRows.find((row) => String(row.id) === selectedId) ?? null,
    [channelRows, selectedId]
  );

  const dismissChat = useCallback(() => {
    selectionDismissedRef.current = true;
    selectionRef.current = null;
    replySelectionRef.current = null;
    setSelectedId(null);
    setContactPanelOpen(false);
    inboxRootRef.current?.focus({ preventScroll: true });
  }, []);
  const dismissFolder = useCallback(() => setFolderKey(null), []);
  useInboxEscape(inboxRootRef, selected ? dismissChat : null, activeFolder ? dismissFolder : null);

  const agentAvatars = new Map<string,string>();

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
    setDelivery(null);
    setError(null);
  }, [selectedId]);
  useEffect(() => { setDraft(""); setNoteDraft(""); setNoteBody(""); setHistoryPages(1); }, [selected?.customer]);

  useEffect(() => {
    timelineEndRef.current?.scrollIntoView({ block: "end" });
  }, [selectedId, timeline.length, composerHeight]);

  useEffect(() => {
    if (selectionDismissedRef.current && selectedId === null) return;
    if (selectedId && channelRows.some((row) => String(row.id) === selectedId)) return;
    setSelectedId(rows.length ? String(rows[0].id) : null);
  }, [rows, channelRows, selectedId]);

  useEffect(() => {

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
    setDeliveryFailed(false);
    try {
      const [activity, nextCustomer, nextDelivery] = await Promise.all([
        (async () => {
          type ActivityEntry = {id:string;conversationId:string;kind:string;direction:string;channel:string;authorName:string;authorAvatarUrl:string|null;mine:boolean;body:string;at:string;deliveryStatus:string};
          const entries:ActivityEntry[]=[];let hasMore=false;
          for(let page=0;page<historyPages;page++) {
            const response=await fetch(`/api/crm/contacts/${customerId}/activity?limit=100&offset=${page*100}`,{credentials:"same-origin"});
            if(!response.ok)throw new Error("Could not load contact history");
            const result=await response.json() as {entries:ActivityEntry[];hasMore:boolean};entries.push(...result.entries);hasMore=result.hasMore;if(!hasMore)break;
          }
          return {entries,hasMore};
        })(),
        customerId ? request<{fields: EntityRecord}>(`/contacts/${customerId}`).then(c=>c.fields) : Promise.resolve(null),
        fetch(`/api/crm/conversations/${id}/delivery${workspaceKey ? `?workspace=${encodeURIComponent(workspaceKey)}` : ""}`, { credentials: "same-origin" }).then(async (response) => {
          if (!response.ok) throw new Error("Could not check channel connection");
          return response.json() as Promise<DeliveryConnection>;
        }),
      ]);
      if (selectionRef.current !== id) return;
      setDelivery(nextDelivery);
      setHasMoreHistory(activity.hasMore);
      const entries = [...activity.entries].reverse();
      setMessages(entries.filter(entry => entry.kind !== "NOTE").map(entry => ({ ...entry, id: entry.id.replace(/^message:/, ""), sentAt: entry.at })) as Message[]);
      setComments(entries.filter(entry => entry.kind === "NOTE").map(entry => ({id:entry.id, authorName:entry.authorName, authorAvatarUrl:entry.authorAvatarUrl, body:entry.body, createdAt:entry.at, mine:entry.mine})));
      const nextReply = replySelection(customerId, activity.entries, replySelectionRef.current, !!draftRef.current.trim());
      replySelectionRef.current = nextReply.state;
      if (nextReply.conversationId && channelRows.some(row => String(row.id) === nextReply.conversationId)) setSelectedId(nextReply.conversationId);
      setLoadedConversationId(id);

      setCustomer(nextCustomer);
      setError(null);
    } catch (loadError) {
      if (selectionRef.current !== id) return;
      setDelivery(null);
      setDeliveryFailed(true);
      setError(loadError instanceof Error ? loadError.message : "Could not load the conversation");
    }
  }, [selected, workspaceKey, channelRows, historyPages]);

  useEffect(() => { void loadSelected(); }, [loadSelected]);

  useUiEvents(
    () => { void loadSelected(); },
    { types: ["created", "updated", "deleted"], entityType: "catalog", entityName: "CrmConversationMessages" }
  );

  useUiEvents(
    () => { void loadSelected(); },
    { types: ["created", "updated", "deleted"], entityType: "comment", entityName: "crm_conversations", id: selectedId || undefined }
  );

  useUiEvents(() => { void loadSelected(); }, { types: ["updated"], entityType: "page", entityName: "crm-inbox-workspaces" });

  const replyState = composerState({canReply:!!action(config,"reply") && !!action(config,"sendReply"), busy,
    loaded:loadedConversationId === selectedId, failed:deliveryFailed, delivery});
  const composerStatusId = useId();

  const acknowledgeRead = useCallback(() => Promise.all(channelRows.filter(row => String(row.customer) === String(selected?.customer) && Number(row.unreadCount || 0)>0).map(row => command(`/api/crm/conversations/${row.id}/read`))), [channelRows, selected?.customer, workspaceKey]);
  const readRevision = selected && loadedConversationId === selectedId
    ? JSON.stringify([workspaceKey, selectedId, selected.unreadCount,
      messages.filter(message => message.direction === "INBOUND").map(message => message.id)])
    : null;
  useConversationRead(timelineEndRef, readRevision, acknowledgeRead);

  const select = (row: EntityRecord) => {
    selectionDismissedRef.current = false;
    replySelectionRef.current = null;
    if (activeFolder && owner(row)?.key !== activeFolder.key) setFolderKey(owner(row)?.key ?? null);
    const id = String(row.id);
    setDelivery(null);
    setDraft("");
    setError(null);
    setSelectedId(id);
  };

  const submit = async () => {
    if (!action(config, mode) || !action(config, mode === "reply" ? "sendReply" : "sendNote") || !selected || !(mode === "note" ? noteDraft : draft).trim() || busy || (mode === "reply" && replyState.disabled)) return;
    setBusy(true);
    setError(null);
    try {
      if (mode === "note") {
        await command(workspaceKey ? `/api/crm/inbox-workspaces/${workspaceKey}/conversations/${String(selected.id)}/comments` : `/api/comments/catalogs/crm_conversations/${String(selected.id)}`, {
          body: noteBody,
          parentId: null,
        });
      } else {
        await command(`/api/crm/conversations/${String(selected.id)}/messages`, {
          body: draft,
        });
      }
      if (mode === "note") { setNoteDraft(""); setNoteBody(""); } else setDraft("");
      await loadSelected();
    } catch (sendError) {
      setError(sendError instanceof Error ? sendError.message : "Could not send");
    } finally {
      setBusy(false);
    }
  };

  const retry = async (messageId: string, conversationId?: string) => {
    if (!action(config, "retry") || !selected || busy || !delivery?.connected) return;
    setBusy(true);
    setError(null);
    try {
      await command(`/api/crm/conversations/${conversationId || String(selected.id)}/messages/${messageId}/retry`);
      await loadSelected();
    } catch (retryError) {
      setError(retryError instanceof Error ? retryError.message : "Could not retry");
    } finally { setBusy(false); }
  };

  useEffect(() => {
    if (config && !action(config, mode)) { const next = config.actions.find(a => a.visible && ["reply", "note"].includes(a.key)); if (next) setMode(next.key as ComposerMode); }
  }, [config, mode]);

  const [hostActions,setHostActions]=useState<{id:string;label:string;enabled:boolean}[]>([]);
  useEffect(()=>{
    let live=true;setHostActions([]);
    if(selectedId && workspaceKey)void request<{id:string;label:string;enabled:boolean}[]>(
      `/inbox-workspaces/${encodeURIComponent(workspaceKey)}/conversations/${selectedId}/actions`)
      .then(items=>{if(live)setHostActions(items);}).catch(()=>{});
    return()=>{live=false;};
  },[selectedId,workspaceKey,selected]);
  const extensionContext: ExtensionContext = {
    surface:"crm-chat", kind:"catalogs", name:"crm_conversations", recordId:selected ? String(selected.id) : undefined,
    record:selected ?? undefined, workspaceKey, permissions:{canWrite:!!action(config,"edit"),canReply:!!action(config,"reply")},
    actions:hostActions.map(a=>({...a,enabled:a.enabled&&!busy})),
    refresh:loadSelected,
    openRecord:(kind,name,id)=>window.dispatchEvent(new CustomEvent("onno:action",{detail:`onno://${kind}/${name}/${id}`})),
    insertDraft:text=>{if(replyState.disabled)throw new Error("Reply editing is unavailable");const next=draft.trim()?`${draft}\n\n${text}`:text;if(next.length>(delivery?.maxTextLength??4096))throw new Error("The reply is too long. Shorten the draft first.");setMode("reply");setDraft(next);},
    execute:async (name,input)=>{
      if(!selected || !workspaceKey || busy || !hostActions.some(a=>a.id===name&&a.enabled))throw new Error("Action unavailable");
      const result=await command(`/api/crm/inbox-workspaces/${encodeURIComponent(workspaceKey)}/conversations/${selected.id}/actions/${encodeURIComponent(name)}`,{inputs:input});
      await loadSelected();return result;
    },
  };
  if (!config) return <p className="p-4 text-sm">{configError || "Loading CRM workspace…"}</p>;

  if (!rows.length && !folders.length) {
    return (
      <div className="rounded-panel border border-dashed border-border px-6 py-16 text-center">
        <div className="text-sm font-medium text-foreground">No conversations match these filters</div>
        <div className="mt-1 text-xs text-muted-foreground">Clear a filter or create a conversation.</div>
      </div>
    );
  }

  return (
    <div
      ref={inboxRootRef}
      tabIndex={-1}
      data-testid="crm-inbox-workspace"
      className="crm-chat-layout relative grid h-full min-h-0 max-h-full w-full overflow-hidden overscroll-none"
      data-contact-open={!!(contactPanelOpen && selected && config)}
      style={{ height: "100%", minHeight: 0, maxHeight: "100%", contain: "layout size" }}
    >
      <section aria-label="Chat selection" className={`${islandSurface} my-1 ml-1 hidden min-h-0 min-w-0 flex-col overflow-hidden md:!flex`}>
        <style>{`
          .crm-chat-layout { --chat-start-inset:4px; --chat-end-inset:4px; --contact-width: min(320px, 44vw); --contact-motion: 280ms; --contact-ease: cubic-bezier(.22,1,.36,1); grid-template-columns: 0px minmax(0,1fr) 0px; transition: grid-template-columns var(--contact-motion) var(--contact-ease); }
          .crm-chat-layout[data-contact-open="true"] { --chat-end-inset:8px; grid-template-columns: 0px minmax(0,1fr) var(--contact-width); }
          .crm-chat-layout > section { grid-column:1; grid-row:1; }
          .crm-chat-layout > main { grid-column:2; grid-row:1; }
          .crm-chat-header { margin:4px var(--chat-end-inset) 0 var(--chat-start-inset); }
          .crm-chat-composer { padding-left:var(--chat-start-inset); padding-right:var(--chat-end-inset); }
          .crm-contact-slot { grid-column:3; grid-row:1; position:relative; min-width:0; min-height:0; overflow:hidden; }
          .crm-contact-island { position:absolute; top:4px; bottom:4px; right:4px; width:calc(var(--contact-width) - 8px); transform:translateX(16px); opacity:0; visibility:hidden; transition:transform var(--contact-motion) var(--contact-ease), opacity var(--contact-motion) var(--contact-ease), visibility 0s var(--contact-motion); }
          .crm-chat-layout[data-contact-open="true"] .crm-contact-island { transform:translateX(0); opacity:1; visibility:visible; transition-delay:0s; }
          @media (min-width:768px) {
            .crm-chat-layout { --chat-start-inset:12px; grid-template-columns:260px minmax(0,1fr) 0px; }
            .crm-chat-layout[data-contact-open="true"] { grid-template-columns:260px minmax(0,1fr) var(--contact-width); }
          }
          @media (min-width:1280px) {
            .crm-chat-layout { grid-template-columns:300px minmax(0,1fr) 0px; }
            .crm-chat-layout[data-contact-open="true"] { grid-template-columns:300px minmax(0,1fr) var(--contact-width); }
          }
          @media (prefers-reduced-motion:reduce) { .crm-chat-layout, .crm-contact-island { transition:none; } }
          .crm-folder-slide { --page-slide-dur: 300ms; --page-slide-ease: cubic-bezier(0.2,0.7,0.2,1); }
          .crm-folder-slide > .crm-folder-page { position:absolute; inset:0; width:100%; max-width:100%; min-width:0; overflow:hidden; transform:translate3d(0,0,0); backface-visibility:hidden; will-change:transform; transition:transform var(--page-slide-dur) var(--page-slide-ease); }
          .crm-folder-slide[data-open="false"] > .crm-folder-detail { transform:translate3d(100%,0,0); pointer-events:none; }
          .crm-folder-slide[data-open="true"] > .crm-folder-root { transform:translate3d(-100%,0,0); pointer-events:none; }
          @media (prefers-reduced-motion: reduce) { .crm-folder-slide > .crm-folder-page { transition:none; } }
        `}</style>
        <div className="crm-folder-slide relative min-h-0 min-w-0 w-full flex-1 overflow-hidden" data-open={!!activeFolder}>
          <div ref={rootListRef} className="crm-folder-page crm-folder-root flex min-h-0 min-w-0 flex-col" aria-hidden={!!activeFolder} {...(activeFolder ? {inert: true} : {})}>
            <div className="flex min-h-16 shrink-0 flex-col justify-center border-b border-border/50 px-3 py-3">
              <div className="text-sm font-semibold">Conversations</div>{chatGroups.error && <p role="alert" className="text-xs text-destructive">{chatGroups.error}</p>}
              <div className="mt-0.5 text-[11px] text-muted-foreground">{rows.length} chats{folders.length ? ` · ${folders.length} folders` : ""}</div>
            </div>
            <ConversationList {...pagination} active={!activeFolder}>
              {(() => {
                const shown = new Set<string>();
                const entries = rows.map(row => {
                  const folder = owner(row);
                  if (!folder) return <ConversationRow key={String(row.id)} row={row} config={config} workspaceKey={workspaceKey} groups={chatGroups.groups} groupKey={chatGroups.groups.find(group => group.customerIds.includes(String(row.customer)))?.key} onGroupChange={chatGroups.ready ? chatGroups.change : undefined} selected={String(row.customer || row.id) === String(selected?.customer || selectedId)} onSelect={() => select(row)} />;
                  if (shown.has(folder.key)) return null;
                  shown.add(folder.key);
                  const members = folderRows(folder.key);
                  return <Button variant="ghost" key={`folder:${folder.key}`} data-folder-key={folder.key} type="button" onClick={() => openFolder(folder.key)} className="h-auto whitespace-normal font-normal flex w-full items-center gap-3 rounded-field px-3 py-3 text-left hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                    <span className="flex size-10 shrink-0 items-center justify-center rounded-field bg-primary/10 text-primary"><Folder className="size-5" /></span>
                    <span className="min-w-0 flex-1"><span className="block truncate text-[13px] font-semibold">{folder.label}</span><span className="mt-1 block truncate text-[11px] text-muted-foreground">{members.map(member => string(member, "customerDisplay", string(member, "description"))).join(", ")}</span></span>
                    <span className="text-xs tabular-nums text-muted-foreground">{members.length}</span><ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                  </Button>;
                });
                return <>{entries}{folders.filter(folder => !shown.has(folder.key)).map(folder => <Button variant="ghost" key={folder.key} data-folder-key={folder.key} type="button" onClick={() => openFolder(folder.key)} className="h-auto whitespace-normal font-normal flex w-full items-center gap-3 rounded-field px-3 py-3 text-left text-muted-foreground hover:bg-muted/50"><Folder className="size-5" /><span className="flex-1 text-sm">{folder.label}</span><span className="text-xs">0</span><ChevronRight className="size-4" /></Button>)}</>;
              })()}
            </ConversationList>
          </div>
          <div ref={detailListRef} className="crm-folder-page crm-folder-detail flex min-h-0 min-w-0 flex-col" aria-hidden={!activeFolder} {...(!activeFolder ? {inert: true} : {})}>
            <div className="flex min-h-16 shrink-0 items-center gap-2 border-b border-border/50 px-3 py-3">
              <Button variant="ghost" size="sm" aria-label="Back to conversations" onClick={() => setFolderKey(null)}><ArrowLeft className="size-4" /></Button>
              <div className="min-w-0"><div className="truncate text-sm font-semibold">{displayedFolder?.label}</div><div className="mt-0.5 text-[11px] text-muted-foreground">{detailRows.length} chats in the current view</div></div>
              {chatGroups.groups.filter(group => group.key === displayedFolder?.key).map(group => <ChatGroupActions key={group.key} group={group} change={chatGroups.change} />)}
            </div>
            <ConversationList {...pagination} active={!!activeFolder}>
              {displayedFolder && detailRows.map(row => <ConversationRow key={String(row.id)} row={row} config={config} workspaceKey={workspaceKey} groups={chatGroups.groups} groupKey={chatGroups.groups.find(group => group.customerIds.includes(String(row.customer)))?.key} onGroupChange={chatGroups.ready ? chatGroups.change : undefined} selected={String(row.customer || row.id) === String(selected?.customer || selectedId)} onSelect={() => select(row)} />)}
              {displayedFolder && !detailRows.length && <p className="px-3 py-8 text-center text-xs text-muted-foreground">No chats in this folder match the current filters.</p>}
            </ConversationList>
          </div>
        </div>
      </section>

      {selected ? (
        <main className="relative flex min-h-0 min-w-0 flex-col overflow-hidden">
          <header className={`${islandSurface} crm-chat-header z-10 flex min-h-16 shrink-0 flex-wrap items-center gap-2 px-4 py-3`}>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold text-foreground">{string(selected, "customerDisplay", "Customer")}</div>
              <div className="mt-0.5 truncate text-[11px] text-muted-foreground">All channels · unified contact history</div>
            </div>
            <ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-wrap items-center gap-2" />
            {config && <Button ref={contactPanelToggleRef} size="toolbar" variant={contactPanelOpen ? "secondary" : "subtle"} aria-label={contactPanelOpen ? "Hide contact details" : "Show contact details"} title="Contact details" aria-expanded={contactPanelOpen} aria-controls={contactPanelId} onClick={() => setContactPanelOpen(value => !value)}><PanelRight className="size-4" /></Button>}
          </header>
          <div data-chat-history className="min-h-0 flex-1 space-y-3 overflow-y-auto overscroll-contain px-4 py-5" style={{ paddingBottom: composerHeight + 16 }}>

            {hasMoreHistory && <Button size="sm" variant="ghost" onClick={() => setHistoryPages(value => value+1)}>Load earlier activity</Button>}
            {timeline.map((entry) => entry.message ? (
              entry.message.kind === "SYSTEM_EVENT" ? (
                config?.showSystemEvents !== false ? <ChatMessageBody key={entry.id} message={entry.message} fallback={<TimelineSystemEvent message={entry.message} config={config} />} /> : null
              ) : (
                <TimelineMessage
                  key={entry.id}
                  config={config}
                  message={entry.message}
                  channel={entry.message.channel}
                  retry={() => void retry(entry.message!.id, entry.message!.conversationId)}
                  busy={busy || !delivery?.connected}
                  avatarUrl={entry.message.direction === "INBOUND"
                    ? customerAvatarUrl
                    : agentAvatars.get(entry.message.authorName)}
                />
              )
            ) : entry.comment ? <TimelineComment key={entry.id} comment={entry.comment} config={config} /> : null)}
            {!timeline.length && !error ? (
              <div className="py-16 text-center text-xs text-muted-foreground">No messages yet</div>
            ) : null}
            <div ref={timelineEndRef} style={{ scrollMarginBottom: composerHeight + 16 }} />
          </div>

          <div ref={composerRef} data-chat-composer className="crm-chat-composer pointer-events-none absolute inset-x-0 bottom-0 z-10 pb-1 pt-2">
            <div className={`${islandSurface} pointer-events-auto p-3 focus-within:ring-ring/40 ${mode === "note" ? "ring-amber-500/30" : ""}`}>
            <div className="mb-2 flex items-center justify-between gap-3">
              <Select value={selectedId || ""} disabled={busy || !!draft.trim() || mode === "note"}
                onValueChange={(id: string) => { const target = channelRows.find(row => String(row.id) === id); if (target) setSelectedId(String(target.id)); }}>
                <SelectTrigger aria-label="Send from" className="w-auto max-w-full min-w-0"
                  title={draft.trim() ? "Send or clear your draft before changing channels" : "Choose a conversation for this contact"}>
                  <SelectValue><span className="inline-flex min-w-0 items-center gap-2"><ChannelLogo channel={conversationChannel(selected)} className="size-4 shrink-0" /><span className="truncate">{"From: " + string(selected, "inboxDisplay", delivery?.label || conversationChannel(selected))}</span></span></SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {channelRows.filter(row => String(row.id) === selectedId || (!!selected.customer && String(row.customer) === String(selected.customer))).map(row => (
                    <SelectItem key={String(row.id)} value={String(row.id)}>
                      <span className="inline-flex items-center gap-2">
                        <ChannelLogo channel={conversationChannel(row)} className="size-4" />
                        {string(row, "inboxDisplay", conversationChannel(row))}
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <span title={mode === "reply" ? delivery?.label : undefined} className="text-[10px] text-muted-foreground">
                {mode === "note" ? "Visible to your team only" : ""}
              </span>
            </div>
            <div hidden={mode !== "note"}>
              <NoteInput key={String(selected.customer)} value={noteDraft}
                disabled={busy || !action(config, "note")} onChange={(text, body) => {setNoteDraft(text); setNoteBody(body);}}
                onSubmit={() => void submit()} />
            </div>
            {mode === "reply" && <Textarea
              value={draft}
              disabled={replyState.disabled}
              onChange={(event: { target: { value: string } }) => setDraft(event.target.value)}
              onKeyDown={(event: { key: string; metaKey: boolean; ctrlKey: boolean; preventDefault: () => void }) => {
                if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
                  event.preventDefault();
                  void submit();
                }
              }}
              placeholder={replyState.placeholder}
              aria-describedby={replyState.message ? composerStatusId : undefined}
              maxLength={mode === "reply" ? delivery?.maxTextLength ?? 4096 : 8000}
              aria-label={mode === "reply" ? "Write a reply" : "Write an internal note"}
              className="min-h-20 resize-none border-0 bg-transparent dark:bg-transparent px-2 shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:shadow-none"
            />}
            {mode === "reply" && replyState.message && <div id={composerStatusId} role="status" className="flex items-center gap-2 rounded-field bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
              <span className="flex-1">{replyState.message}</span>
              {replyState.retry && <Button variant="ghost" onClick={() => void loadSelected()}>Check again</Button>}
            </div>}
            {error && <p role="alert" className="mt-2 text-xs text-destructive">{error}</p>}
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Segmented
                value={mode}
                onChange={(value: ComposerMode) => { if (!busy) setMode(value); }}
                options={(config?.actions ?? []).filter(a => a.visible && ["reply", "note"].includes(a.key)).map(a => ({ value: a.key as ComposerMode, label: a.label }))}
              />
              <ExtensionSlot name="crm.chat.composer" context={{ ...extensionContext,
                insertDraft: mode === "reply" ? extensionContext.insertDraft : undefined,
              }} className="flex items-center gap-2" />
              <span className="flex-1" />
              {action(config, mode === "reply" ? "sendReply" : "sendNote") && <Button disabled={!action(config, mode) || busy || !(mode === "note" ? noteDraft : draft).trim() || (mode === "reply" && replyState.disabled)} onClick={() => void submit()}>
                {busy ? "Sending…" : action(config, mode === "reply" ? "sendReply" : "sendNote")?.label}
              </Button>}
            </div>
            </div>
          </div>
        </main>
      ) : <main className="flex items-center justify-center p-6 text-sm text-muted-foreground">Choose a conversation to start.</main>}

      <div className="crm-contact-slot" aria-hidden={!contactPanelOpen} {...(!contactPanelOpen ? {inert:true} : {})}>
        {selected && config ? <aside id={contactPanelId} aria-label="Contact details"
          className={`${islandSurface} crm-contact-island flex flex-col overflow-hidden`}
          onKeyDown={event => { if (event.key === "Escape" && !event.defaultPrevented) { event.preventDefault(); closeContactPanel(); } }}>
          <div className="flex shrink-0 items-center justify-between gap-2 border-b border-border/50 px-4 py-3">
            <span className="text-sm font-semibold">Contact details</span>
            <Button size="sm" variant="ghost" aria-label="Close contact details" onClick={closeContactPanel}><X className="size-4" /></Button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
            <ContactPanel key={String(selected.customer)} id={String(selected.customer)} row={selected} customer={customer} config={config} />
            <ExtensionSlot name="crm.chat.aside" context={extensionContext} className="space-y-3 p-4" />
          </div>
        </aside> : null}
      </div>
    </div>
  );
}

registerListRenderer("crmInbox", CrmInbox);


type WorkspaceSummary = { key: string; label: string; canWrite: boolean };
type WorkspaceFeed = { list: Record<string,any>; filters: {field:string;label:string;type:string;options:{value:string;label:string;avatarUrl?:string}[]}[]; key: string; label: string; config: Config; canWrite: boolean; rows: EntityRecord[]; total: number; hasMore: boolean };
async function workspaceRead<T>(path: string): Promise<T> {
  const response = await fetch(`/api/crm/inbox-workspaces${path}`, {credentials:"same-origin"});
  if (!response.ok) throw new Error(response.status === 403 ? "You do not have access to this inbox." : "Could not load inbox.");
  return response.json();
}
function InboxWorkspaces({widget}: {widget: DashboardWidgetMeta}) {
  const { statuses } = useConversationStatuses();
  const configuredKey = widget.extraConfig?.workspace || "";
  const conversationId = widget.extraConfig?.conversation || "";
  const conversationPath = conversationId ? `/conversation/${encodeURIComponent(conversationId)}` : "";
  const [feed,setFeed] = useState<WorkspaceFeed | null>(null);
  const [error,setError] = useState("");
  const [refreshKey,setRefreshKey] = useState(0);
  useEffect(() => {
    let live=true;
    void workspaceRead<WorkspaceSummary[]>("").then(async items => {
      const key=configuredKey || items[0]?.key;
      if(!key) throw new Error("No inbox workspaces are available for your account.");
      const result=await workspaceRead<WorkspaceFeed>(`/${encodeURIComponent(key)}${conversationPath}`);
      if(live)setFeed(result);
    }).catch(e=>{if(live)setError(e.message);});
    return ()=>{live=false;};
  },[configuredKey,conversationPath]);
  useUiEvents(()=>setRefreshKey(value=>value+1),{types:["updated"],entityType:"page",entityName:"crm-inbox-workspaces"});
  const renderer = useMemo(() => function WorkspaceBody(props: ListRendererProps) {
    return feed ? <CrmInbox {...props} open={()=>{}} scopedConfig={feed.config} workspaceKey={feed.key} /> : null;
  },[feed]);
  const list = useMemo(()=>feed ? {
    ...feed.list,
    kind:"catalogs", name:"crm_conversations", embedded:true, fill:true, canWrite:false, newUrl:null,
    feed:`/api/crm/inbox-workspaces/${encodeURIComponent(feed.key)}${conversationPath}`,
  } : null,[feed,widget.title,conversationPath,statuses]);
  if(error)return <p role="alert">{error}</p>;
  return list ? <EntityListWidget list={list} renderer={renderer} refreshKey={refreshKey} /> : <p>Loading inbox…</p>;
}
registerWidget("crmInboxWorkspaces", InboxWorkspaces);
