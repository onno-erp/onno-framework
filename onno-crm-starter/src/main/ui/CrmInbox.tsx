import { VoicePlayer } from "./VoicePlayer";
import { InboxChannelNavigation } from "./InboxChannelNavigation";
import { InboxSkeleton, ConversationListSkeleton, ConversationRowContent, type ConversationRowOptions, conversationCount, ConversationList, type ConversationPagination } from "./ConversationList";
import { CallActivityCard, callActivity } from "./CallActivityCard";
import { ContactAvatarImage } from "./ContactAvatarImage";
import { ScheduleCall } from "./ScheduleCall";
import { LogActivity } from "./LogActivity";
import { composerState } from "./composerState";
import { useConversationStatuses } from "./ConversationStatuses";
import { ExtensionSlot, openRoute, type ExtensionContext } from "@onno/ui";
import { ChatGroupActions, NewChatGroup } from "./ChatGroupActions";
import { composerDrafts, installDraftUnloadGuard, hasDraftText, EMPTY_DRAFT, type ComposerDraft, type ComposerMode } from "./composerDrafts";
import { useAutoGrow } from "./useAutoGrow";
import { MailboxSearch } from "./MailboxSearch";
import { mailboxAccounts, type MailboxAccount } from "./mailboxApi";
import { useChatGroups, type ChatGroup, type GroupChange } from "./chatGroups";
import { NoteInput } from "./NoteInput";
import { contactChats, replySelection, type ReplySelection } from "./contactChats";
import { ContactRowMenu } from "./ContactRowMenu";
import { useInboxEscape } from "./useInboxEscape";
import { useConversationRead } from "./useConversationRead";
import { locale, text as hostText, useLocale, useTranslate, useMobileDetail, usePageSlide, WorkspaceToolbar, type WorkspaceToolbarLayout } from "@onno/ui";

/**
 * Every label in this widget resolves through the app's chrome strings, so the inbox reads in the
 * deployment's language instead of shipping hardcoded English. `t` is the non-hook accessor, usable
 * in handlers and thrown errors; the root component calls {@link useTranslate} once so the whole
 * tree re-renders when the server's `onno.ui.messages` overlay arrives.
 */
const t = hostText;
import { useId, type DragEvent } from "react";
import { ChannelLogo } from "./ChannelLogo";
import { LinkedText } from "./LinkedText";
import { EmailHtmlBody } from "./EmailHtmlBody";
import { MediaGrid } from "./MediaGrid";
import { request, ContactPanel, useWorkspace, action, rowValue, resolveConfig, useCrmPresentation, CrmPresentationProvider, type ConversationFolder, type CrmIcon, type CrmPresentation, type ServerConfig, type Config } from "./CrmWorkspace";
import { ChatMessageBody, ChatMessageRenderers, type ChatMessageRenderer } from "./chatMessageRenderers";
import { ResponsiveActionPanel, DialogShell, Badge, Input, EntityListView, Button, Segmented, Select, SelectTrigger, SelectValue, SelectContent, SelectItem, Textarea, CommentBody, api, useUiEvents, applyListOptions, type EntityRecord, type ListRendererProps, type ListRendererComponent, type ListCustomization, type ListDescriptor, type ListFilter, type ListView } from "@onno/ui";
import React, { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Settings2, ContactRound, Plus, Activity, Folder, ChevronRight, ArrowLeft, X, Paperclip, MoreHorizontal, Send, RefreshCw, Maximize2, Minimize2, PenLine, MailSearch } from "@onno/ui/icons";

type Message = {
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
  attachments?: Attachment[];
  /** Sanitized email HTML; rendered in a sandboxed frame instead of the plain-text body. */
  bodyHtml?: string | null;
};

/** A file on a message. The url is a reference issued by `/api/media`, never the bytes themselves. */
type Attachment = { url: string; filename: string; contentType: string; size: number; image: boolean };

/**
 * `senders`/`sender`: the mailboxes a reply may go out from and the default one's id. Absent or a
 * single entry (an older backend, or a provider without a choice) shows no mailbox picker.
 */
type DeliveryConnection = { connected: boolean; label: string; maxTextLength: number; replyCapability: "AVAILABLE" | "READ_ONLY" | "WINDOW_CLOSED"; replyReason: string; maxAttachments?: number; attachmentReason?: string; recipient?: string; recipients?: string[]; senders?: { id: string; label?: string | null }[]; sender?: string | null };

type Comment = {
  id: string;
  authorName: string | null;
  authorAvatarUrl: string | null;
  body: string;
  createdAt: string | null;
  mine: boolean;
};

// One surface treatment for the header, composer, and contact-details island.
const islandSurface = "rounded-panel bg-card border border-border/60";

/**
 * The large composer window is the shared DialogShell, sized from inside: about 90% of the viewport,
 * the whole screen when maximised, and always the whole screen on a phone. The shell's own size
 * steps stop at a comparison dialog, so these rules reach its modal, overlay and panel through the
 * window's marker rather than restyling every dialog.
 */
const COMPOSER_WINDOW_CSS = `
  div:has(> section[role="dialog"] > div > [data-crm-composer-window]) { width:90vw; max-width:90vw; height:85vh; height:85dvh; max-height:none; }
  section[role="dialog"] > div:has(> [data-crm-composer-window]) { display:flex; flex-direction:column; }
  div:has(> section[role="dialog"] > div > [data-crm-composer-window][data-maximized="true"]) { width:100vw; max-width:100vw; height:100vh; height:100dvh; }
  div:has(> div > section[role="dialog"] > div > [data-crm-composer-window][data-maximized="true"]) { padding:0; }
  section[role="dialog"]:has(> div > [data-crm-composer-window][data-maximized="true"]) { border-radius:0; border-width:0; }
  @media (max-width: 639.98px) {
    div:has(> section[role="dialog"] > div > [data-crm-composer-window]) { width:100vw; max-width:100vw; height:100vh; height:100dvh; }
    div:has(> div > section[role="dialog"] > div > [data-crm-composer-window]) { padding:0; }
    section[role="dialog"]:has(> div > [data-crm-composer-window]) { border-radius:0; border-width:0; }
    section[role="dialog"] [data-composer-maximize] { display:none; }
  }
`;

/** The app route this inbox is on, without the SPA's mount prefix, for an `onno://` hand-off. */
function currentRoute(): string {
  const base = (window as { __onnoBasePath?: string }).__onnoBasePath;
  let path = window.location.pathname;
  const prefix = base && base !== "__ONNO_BASE_PATH__" ? base.replace(/\/+$/, "") : "";
  if (prefix && path.startsWith(`${prefix}/`)) path = path.slice(prefix.length);
  return path.replace(/^\/+/, "");
}

/** Puts the caret at the end of the composer field in `scope`, the way a returning writer expects. */
function focusComposerField(scope: ParentNode | null | undefined, mode: ComposerMode) {
  const field = scope?.querySelector<HTMLTextAreaElement>(mode === "note" ? "[data-composer-note] textarea" : "textarea[data-composer-reply]");
  if (!field || field.disabled) return;
  field.focus({ preventScroll: true });
  field.setSelectionRange(field.value.length, field.value.length);
}

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
    ? new Intl.DateTimeFormat(locale(), { hour: "2-digit", minute: "2-digit" }).format(date)
    : new Intl.DateTimeFormat(locale(), { month: "short", day: "numeric" }).format(date);
}

function fullTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(locale(), {
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

async function checkedJson<T>(response: Response, message: string): Promise<T> {
  if (!response.ok) throw new Error(message);
  return response.json() as Promise<T>;
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
  return (
    <span className={`relative ${className} shrink-0`}>
      <span className="relative flex size-full items-center justify-center overflow-hidden rounded-full border border-border bg-muted text-[10px] font-semibold text-foreground">
        {initials(name)}
        <ContactAvatarImage name={name} url={url} />
      </span>
      {channel ? <ChannelIcon channel={channel} avatarBadge /> : null}
    </span>
  );
}

function ChannelIcon({ channel, comment = false, avatarBadge = false }: { channel: string; comment?: boolean; avatarBadge?: boolean }) {
  return <span title={comment ? "Internal note" : channel} className={avatarBadge
    ? "absolute -bottom-0.5 -right-0.5 z-10 flex size-[18px] items-center justify-center rounded-full bg-card ring-2 ring-card"
    : "flex size-6 shrink-0 items-center justify-center"}>
    <ChannelLogo channel={comment ? "internal" : channel} className={avatarBadge ? "size-3.5" : "size-4"} />
  </span>;
}

function conversationChannel(row: EntityRecord | null): string {
  return string(row,"channel",t("crm.chat.channel"));
}

function ConversationRow({
  row,
  selected,
  onSelect,
  config,
  groups, groupKey, onGroupChange, workspaceKey, appearance = {},
}: {
  appearance?: ConversationRowOptions;
  workspaceKey?: string;
  groups?: ChatGroup[]; groupKey?: string; onGroupChange?: (change: GroupChange) => Promise<void>;
  config?: Config;
  row: EntityRecord;
  selected: boolean;
  onSelect: () => void;
}) {
  const customer = string(row, "customerDisplay", string(row, "description", t("crm.chat.unknownCustomer")));
  const unread = Number(row.unreadCount ?? 0);
  const channel = conversationChannel(row);
  const badges = (appearance.badges ?? []).filter(badge => rowValue(row, badge.field) === badge.equals);
  // Host facets that opt into showOnRows (for example contact tags) arrive resolved with their colours.
  const facetBadges = Array.isArray(row.rowBadges) ? (row.rowBadges as {label?: unknown; color?: unknown}[])
    .filter(b => typeof b?.label === "string").map(b => ({label: String(b.label), color: typeof b.color === "string" && /^#[0-9a-fA-F]{6}$/.test(b.color) ? b.color : ""})) : [];
  const chipCount = badges.length + facetBadges.length;
  return (
    <ContactRowMenu record={row} workspaceKey={workspaceKey} groups={groups} groupKey={groupKey} conversationId={String(row.id)} onGroupChange={onGroupChange} id={String(row.customer ?? "")} canEdit={!!action(config, "edit")} onOpen={onSelect}>
    <Button variant="ghost"
      type="button"
      onClick={onSelect}
      aria-current={selected ? "true" : undefined}
      className={`h-auto whitespace-normal font-normal relative block min-w-0 w-full max-w-full rounded-field px-2 ${chipCount ? "py-2" : "py-3"} text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected
        ? "bg-primary/20"
        : "bg-transparent hover:bg-muted/50 after:absolute after:bottom-0 after:left-[68px] after:right-3 after:h-px after:bg-border/40 last:after:hidden"}`}
    >
      <ConversationRowContent
        avatar={config?.showAvatar !== false && <Avatar name={customer} url={string(row, "customerAvatar")} channel={appearance.showChannelBadge !== false ? channel : undefined} className="size-12" />}
        title={config ? rowValue(row, config.listTitle) : customer}
        timestamp={config?.showTimestamps !== false && <time>{compactTime(row.lastMessageAt)}</time>}
        metadata={appearance.showAccount && string(row, "inboxDisplay", channel)}
        preview={config ? rowValue(row, config.listPreview) : string(row, "lastMessagePreview", t("crm.chat.noMessages"))}
        badges={chipCount > 0 ? <>
          {/* Like Telegram folder labels: a tinted chip normally, inverted to a solid light chip on the highlighted row. */}
          {facetBadges.map((badge,index)=><span key={`facet-${badge.label}-${index}`}
            className={`max-w-full min-w-0 shrink-0 truncate rounded px-1 text-[9px] leading-4 font-semibold uppercase tracking-wide ${selected ? "bg-white shadow-sm" : "bg-primary/15"} text-primary`}
            style={badge.color ? (selected ? {color:badge.color} : {backgroundColor:`${badge.color}26`, color:badge.color}) : undefined}>{badge.label}</span>)}
          {badges.map((badge,index)=><span key={`${badge.label}-${index}`} className={`max-w-full min-w-0 truncate rounded px-1 text-[9px] leading-4 font-medium uppercase text-primary ${selected ? "bg-white shadow-sm" : "bg-primary/15"}`}>{badge.label}</span>)}
        </> : null}
        previewLines={appearance.previewLines}
        trailing={unread > 0 && <span className="flex h-5 min-w-5 shrink-0 items-center justify-center rounded-full bg-primary px-1 text-xs font-medium text-primary-foreground">{unread > 99 ? "99+" : unread}</span>}
      />
    </Button>
    </ContactRowMenu>
  );
}

/** "2.4 MB" — a size a person can judge before clicking, not a byte count. */
function fileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024, unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/**
 * Non-image files on a message: a player for audio, a download chip for everything else. Images are
 * drawn separately, as one album above the caption ({@link MediaGrid}).
 */
function Attachments({ files, outbound }: { files: Attachment[]; outbound: boolean }) {
  if (!files.length) return null;
  return (
    <div className="mt-1.5 flex flex-col gap-1.5">
      {files.map(file => /^audio\//i.test(file.contentType.trim()) ? (
        <VoicePlayer key={file.url} src={file.url} label={file.filename} download={file.filename} outbound={outbound} />
      ) : (
        <a key={file.url} href={file.url} target="_blank" rel="noreferrer" download={file.filename}
          className={outbound
            ? "flex items-center gap-2 rounded-field bg-primary-foreground/15 px-2.5 py-2 text-[12px] hover:bg-primary-foreground/25"
            : "flex items-center gap-2 rounded-field bg-muted px-2.5 py-2 text-[12px] hover:bg-muted/70"}>
          <Paperclip className="size-3.5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate font-medium">{file.filename}</span>
          {file.size > 0 && <span className="shrink-0 tabular-nums opacity-70">{fileSize(file.size)}</span>}
        </a>
      ))}
    </div>
  );
}

function TimelineMessage({ message, avatarUrl, retry, busy, config, channel }: { channel?: string; config?: Config; message: Message; avatarUrl?: string | null; retry: () => void; busy: boolean }) {
  const outbound = message.direction === "OUTBOUND";
  const html = message.bodyHtml?.trim() ? message.bodyHtml : null;
  // Inline email images already appear inside the HTML; list only the files it does not show.
  const files = (message.attachments ?? []).filter(file => !html || !html.includes(file.url));
  const images = files.filter(file => file.image);
  const others = files.filter(file => !file.image);
  return (
    <div className={outbound ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      {config?.showAvatar !== false && <Avatar name={message.authorName} url={avatarUrl} channel={channel || message.channel} />}
      <div className={(outbound
        ? "min-w-0 max-w-[85%] break-words rounded-panel rounded-br-field bg-primary px-3.5 py-2.5 text-primary-foreground"
        : "min-w-0 max-w-[85%] break-words rounded-panel rounded-bl-field border border-border bg-card px-3.5 py-2.5 text-foreground")
        + (html ? " w-full sm:w-[85%]" : "")}
      >
        <div className="flex items-center gap-1.5">
          <span className={outbound
            ? "text-[10px] font-semibold text-primary-foreground/75"
            : "text-[10px] font-semibold text-muted-foreground"}
          >
            {message.authorName}
          </span>
        </div>
        {outbound && (message.subject || message.recipient) ? <p className="mt-0.5 truncate text-[11px] text-primary-foreground/75">
          {[message.recipient && `→ ${message.recipient}`, message.subject].filter(Boolean).join(" · ")}
        </p> : null}
        <MediaGrid images={images} />
        <ChatMessageBody message={message} fallback={html ? <EmailHtmlBody html={html} /> : message.body?.trim()
          ? <p className="mt-1 whitespace-pre-wrap text-sm leading-6"><LinkedText text={message.body} /></p> : null} />
        <Attachments files={others} outbound={outbound} />
        <div className={outbound
          ? "mt-1 flex justify-end gap-1.5 text-[10px] text-primary-foreground/70"
          : "mt-1 flex justify-end gap-1.5 text-[10px] text-muted-foreground"}
        >
          {config?.showTimestamps !== false && <time>{fullTime(message.sentAt)}</time>}
          {outbound && config?.showDeliveryStatus !== false ? <span>{["SENT", "DELIVERED"].includes(message.deliveryStatus) ? "✓ " : ""}{message.deliveryStatus.toLowerCase()}</span> : null}
          {outbound && message.deliveryStatus === "FAILED" && action(config, "retry") ? (
            <Button variant="ghost" type="button" disabled={busy} onClick={retry} className="h-auto p-0 text-inherit underline disabled:opacity-50"
              title={t("crm.chat.retryWarning")}>{action(config, "retry")?.label}</Button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function TimelineSystemEvent({ message, config }: { message: Message; config?: Config }) {
  const activity = callActivity(message.body);
  if (activity) return <CallActivityCard activity={activity} author={message.authorName} at={config?.showTimestamps !== false ? fullTime(message.sentAt) : undefined} />;
  return (
    <div className="flex items-center justify-center gap-2 px-6 py-1 text-center text-[10px] text-muted-foreground">
      <span className="h-px min-w-5 flex-1 bg-border" />
      <span className="inline-flex max-w-[78%] items-center gap-1.5 rounded-pill bg-muted px-2.5 py-1">
        <Activity size={12} className="shrink-0 text-primary" aria-hidden="true" />
        <span className="whitespace-pre-wrap break-words text-left"><CommentBody body={message.body} /></span>
        {config?.showTimestamps !== false && <time className="shrink-0 text-muted-foreground/70">{fullTime(message.sentAt)}</time>}
      </span>
      <span className="h-px min-w-5 flex-1 bg-border" />
    </div>
  );
}

function TimelineComment({ comment, config }: { comment: Comment; config?: Config }) {
  const author = comment.authorName || t("crm.chat.teamMember");
  return (
    <div className={comment.mine ? "flex flex-row-reverse items-end gap-2" : "flex items-end gap-2"}>
      {config?.showAvatar !== false && <Avatar name={author} url={comment.authorAvatarUrl} />}
      <div className={comment.mine
        ? "min-w-0 max-w-[85%] break-words rounded-panel rounded-br-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"
        : "min-w-0 max-w-[85%] break-words rounded-panel rounded-bl-field border border-amber-400/45 bg-amber-400/10 px-3.5 py-2.5 text-foreground"}
      >
        <div className="flex items-center gap-1.5">
          <ChannelIcon channel="" comment />
          <span className="text-[10px] font-semibold text-amber-700 dark:text-amber-300">{author}</span>
          <span className="text-[9px] font-medium uppercase tracking-wide text-muted-foreground">{t("crm.chat.internal")}</span>
        </div>
        <p className="mt-1 whitespace-pre-wrap text-sm leading-6"><CommentBody body={comment.body} /></p>
        <time className="mt-1 block text-right text-[10px] text-muted-foreground">
          {config?.showTimestamps !== false && comment.createdAt ? fullTime(comment.createdAt) : ""}
        </time>
      </div>
    </div>
  );
}

/**
 * A folder's own glyph and hue, when the app gave it one (`folders` on `<CrmInbox>`). Without them
 * the plain folder is kept: guessing an identity from the wording of a label stops working the
 * moment the label is written in another language.
 */
function FolderTile({ folder, size = "size-10", icon = 20 }: { folder: ConversationFolder; size?: string; icon?: number }) {
  const Icon = folder.icon;
  if (!Icon) return (
    <span className={`flex ${size} shrink-0 items-center justify-center rounded-field bg-primary/10 text-primary`}>
      <Folder className="size-5" />
    </span>
  );
  const color = folder.color ?? undefined;
  return (
    <span className={`flex ${size} shrink-0 items-center justify-center rounded-field border`}
      style={color ? { color, backgroundColor: `${color}1f`, borderColor: `${color}3d` } : undefined}>
      <Icon size={icon} />
    </span>
  );
}

function matchesFolder(row: EntityRecord, folder: ConversationFolder): boolean {
  return !folder.matchNone && (!folder.conversationIds?.length || folder.conversationIds.includes(String(row.id)))
    && (!folder.channels.length || [...folder.channels, ...(folder.channelIds ?? [])].includes(String(row.channel)))
    && (!folder.statuses.length || [...folder.statuses, ...(folder.statusIds ?? [])].includes(String(row.status)))
    && (!folder.priorities.length || [...folder.priorities, ...(folder.priorityIds ?? [])].includes(String(row.priority)))
    && (!folder.unreadOnly || Number(row.unreadCount ?? 0) > 0);
}

type InboxChrome = { conversationRow?: ConversationRowOptions; list?: WorkspaceToolbarLayout; mobileList?: WorkspaceToolbarLayout; chat?: WorkspaceToolbarLayout; narrowChat?: WorkspaceToolbarLayout; mobileChat?: WorkspaceToolbarLayout; composer?: WorkspaceToolbarLayout; mobileComposer?: WorkspaceToolbarLayout };
type ConversationFocus = { id: string; nonce: number };
function InboxBody({ toolbar, chrome, loading, rows: channelRows, total, open, scopedConfig, workspaceKey, initialFolder, flat, focusConversation, onFocusHandled, onOpenElsewhere, scoped, onShowAll, ...pagination }: Pick<ListRendererProps, "rows" | "total" | "open"> & ConversationPagination & { scopedConfig?: ServerConfig; workspaceKey?: string; initialFolder?: string; flat?: boolean; toolbar?: React.ReactNode; loading?: boolean; chrome?: InboxChrome;
  /** A conversation to open once its row is in the feed (the workspace scopes the feed to bring it in). */
  focusConversation?: ConversationFocus | null; onFocusHandled?: () => void;
  /** Opens a conversation this feed has not loaded; without it the inbox route is reopened on it. */
  onOpenElsewhere?: (conversationId: string) => void;
  /** The feed is narrowed to one contact's conversations; `onShowAll` widens it again. */
  scoped?: boolean; onShowAll?: () => void }) {
  // Subscribe once at the root: `t` reads the store without subscribing, so this is what makes the
  // whole widget re-render into the server's language and date format when /api/config lands after first paint.
  useTranslate();
  useLocale();
  // Apply confirmed reads immediately; a new server row always supersedes this local snapshot.
  const [readRows, setReadRows] = useState<Map<string, EntityRecord>>(() => new Map());
  const rows = useMemo(() => contactChats(channelRows.map(row => readRows.get(String(row.id)) === row
    ? {...row, unreadCount: 0} : row)), [channelRows, readRows]);
  const { workspace, error: configError } = useWorkspace();
  const presentation = useCrmPresentation();
  const serverConfig = scopedConfig ?? workspace?.config;
  const boundFields = workspace?.fields;
  const config: Config | undefined = useMemo(() => serverConfig && resolveConfig(serverConfig, boundFields ?? [], presentation),
    [serverConfig, boundFields, presentation]);
  const command = <T,>(path: string, body: unknown = {}) => crmCommand<T>(workspaceKey && path.startsWith("/api/crm/conversations/") ? `${path}?workspace=${encodeURIComponent(workspaceKey)}` : path, body);
  const [folderKey, setFolderKey] = useState<string | null>(null);
  const [lastFolderKey, setLastFolderKey] = useState<string | null>(null);
  const openFolder = (key: string) => { setLastFolderKey(key); setFolderKey(key); };
  const chatGroups = useChatGroups(workspaceKey);
  // "Everything" drops the grouping rather than the chats: no folder owns a row, so every row is listed.
  const customFolders: ConversationFolder[] = flat ? [] : chatGroups.groups.map(group => ({key:group.key,label:group.label,conversationIds:[],channelIds:[],statusIds:[],priorityIds:[],channels:[],statuses:[],priorities:[],unreadOnly:false}));
  const folders = flat ? [] : [...customFolders, ...(config?.folders ?? [])];
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
    if (flat) return undefined;
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
  // A routed folder opens once it actually exists in this feed, and never fights the reader
  // afterwards: navigating away from it is their business.
  const openedRoutedFolder = useRef("");
  useEffect(() => {
    if (!initialFolder || openedRoutedFolder.current === initialFolder) return;
    if (!folders.some(folder => folder.key === initialFolder)) return;
    openedRoutedFolder.current = initialFolder;
    setFolderKey(initialFolder);
  }, [initialFolder, folders]);
  const [selectedId, setSelectedId] = useState<string | null>(rows.length ? String(rows[0].id) : null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loadedActivityId, setLoadedActivityId] = useState<string | null>(null);
  const loadSequence = useRef(0);
  const [loadedConversationId, setLoadedConversationId] = useState<string | null>(null);
  const [deliveryFailed, setDeliveryFailed] = useState(false);
  const [delivery, setDelivery] = useState<DeliveryConnection | null>(null);
  const selectionDismissedRef = useRef(false);
  const inboxRootRef = useRef<HTMLDivElement | null>(null);
  const selectionRef = useRef(selectedId);
  selectionRef.current = selectedId;
  const [comments, setComments] = useState<Comment[]>([]);
  const [customer, setCustomer] = useState<EntityRecord | null>(null);

  // The composer's draft belongs to the conversation, not to this mount (composerDrafts.ts): switching
  // chats, contacts, inbox modes or tabs and coming back finds the text, subject, recipient, sending
  // mailbox, mode and attached files as they were left. Only a send or an emptied field clears it.
  useSyncExternalStore(composerDrafts.subscribe, composerDrafts.version, composerDrafts.version);
  useEffect(() => { installDraftUnloadGuard(); }, []);
  const draftKey = selectedId ? composerDrafts.key(workspaceKey, selectedId) : null;
  const defaultMode: ComposerMode = !config || action(config, "reply") ? "reply" : "note";
  const composed: ComposerDraft = (draftKey && composerDrafts.read(draftKey)) || { ...EMPTY_DRAFT, mode: defaultMode };
  const { mode, body: draft, subject: subjectDraft, recipient: recipientDraft, note: noteDraft, noteBody } = composed;
  // Files already uploaded and waiting to go with the next reply, and the ones still uploading.
  const pending: Attachment[] = draftKey ? composerDrafts.files(draftKey) : [];
  const updateDraft = (change: (current: ComposerDraft) => ComposerDraft, key: string | null = draftKey) => {
    if (!key) return;
    composerDrafts.write(key, change(composerDrafts.read(key) ?? { ...EMPTY_DRAFT, mode: key === draftKey ? mode : defaultMode }));
  };
  const setMode = (next: ComposerMode) => updateDraft(current => ({ ...current, mode: next }));
  const setDraft = (next: string | ((previous: string) => string)) =>
    updateDraft(current => ({ ...current, body: typeof next === "function" ? next(current.body) : next }));
  const setSubjectDraft = (subject: string | null) => updateDraft(current => ({ ...current, subject }));
  const setRecipientDraft = (recipient: string | null) => updateDraft(current => ({ ...current, recipient }));
  const setPending = (update: Attachment[] | ((files: Attachment[]) => Attachment[]), key: string | null = draftKey) => {
    if (key) composerDrafts.setFiles(key, update);
  };
  const subjectId = useId();
  const [expanded, setExpanded] = useState(false);
  const [maximized, setMaximized] = useState(false);
  const composerWindowRef = useRef<HTMLDivElement | null>(null);
  const replyInputRef = useRef<HTMLTextAreaElement | null>(null);
  useAutoGrow(replyInputRef, draft, !expanded);
  // A conversation opened on purpose (from the mailbox search) stays open: the newest message of the
  // contact's other channels does not pull the selection away from it.
  const pinnedRef = useRef<string | null>(null);
  const [mailboxes, setMailboxes] = useState<MailboxAccount[]>([]);
  const [mailboxOpen, setMailboxOpen] = useState(false);
  useEffect(() => {
    let live = true;
    void mailboxAccounts().then(items => { if (live) setMailboxes(items); }).catch(() => { if (live) setMailboxes([]); });
    return () => { live = false; };
  }, []);
  const [uploading, setUploading] = useState(0);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [fileDragActive, setFileDragActive] = useState(false);
  const fileDragDepth = useRef(0);
  useEffect(() => { fileDragDepth.current = 0; setFileDragActive(false); }, [selectedId, mode]);
  const [busy, setBusy] = useState(false);
  const [historyPages, setHistoryPages] = useState(1);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [contactPanelOpen, setContactPanelOpen] = useState(false);
  const [actionsOpen, setActionsOpen] = useState(false);
  const [compact, setCompact] = useState(false);
  const folderSlideRef = useRef<HTMLDivElement | null>(null);
  const [detailOverlay, setDetailOverlay] = useState(false);
  const [mobileChatOpen, setMobileChatOpen] = useState(() => new URLSearchParams(window.location.search).has("conversation"));
  const actionsId = useId();
  const contactPanelToggleRef = useRef<HTMLButtonElement | null>(null);
  const closeContactPanel = () => { setContactPanelOpen(false); requestAnimationFrame(() => contactPanelToggleRef.current?.focus({ preventScroll: true })); };
  const contactPanelCloseRef = useRef<HTMLButtonElement | null>(null);
  useEffect(() => { if (contactPanelOpen && detailOverlay) contactPanelCloseRef.current?.focus({ preventScroll: true }); }, [contactPanelOpen, detailOverlay]);
  const contactPanelId = useId();
  const replySelectionRef = useRef<ReplySelection | null>(null);
  const draftRef = useRef(draft); draftRef.current = draft || noteDraft || (subjectDraft !== null || (recipientDraft !== null && recipientDraft !== delivery?.recipient) ? "email draft" : "");
  const [error, setError] = useState<string | null>(null);
  const timelineEndRef = useRef<HTMLDivElement | null>(null);
  // Respond to the allocated pane, including a narrow split pane on a wide screen.
  useEffect(() => {
    const root = inboxRootRef.current;
    if (!root) return;
    const measure = () => {
      const nextCompact = root.clientWidth < 720;
      setCompact(nextCompact);
      setDetailOverlay(root.clientWidth < 1100);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    return () => observer.disconnect();
  }, [!!config, rows.length > 0]);

  const selected = useMemo(
    () => channelRows.find((row) => String(row.id) === selectedId) ?? null,
    [channelRows, selectedId]
  );

  const dismissChat = useCallback(() => {
    if (compact) {
      setMobileChatOpen(false);
      setContactPanelOpen(false);
      inboxRootRef.current?.focus({ preventScroll: true });
      return;
    }
    selectionDismissedRef.current = true;
    selectionRef.current = null;
    replySelectionRef.current = null;
    setSelectedId(null);
    setMobileChatOpen(false);
    setContactPanelOpen(false);
    inboxRootRef.current?.focus({ preventScroll: true });
  }, [compact]);
  const dismissFolder = useCallback(() => setFolderKey(null), []);
  useInboxEscape(inboxRootRef, selected ? dismissChat : null, activeFolder ? dismissFolder : null);

  useMobileDetail(compact && mobileChatOpen && !!selected);
  usePageSlide(inboxRootRef, contactPanelOpen ? "contact" : mobileChatOpen ? "chat" : "list", compact);
  usePageSlide(folderSlideRef, activeFolder ? "folder" : "root");

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
  useEffect(() => { setHistoryPages(1); }, [selected?.customer]);

  useEffect(() => {
    const history = timelineEndRef.current?.parentElement;
    if (history) history.scrollTop = history.scrollHeight;
  }, [selectedId, timeline.length, mobileChatOpen]);

  useEffect(() => {
    if (selectionDismissedRef.current && selectedId === null) return;
    if (selectedId && channelRows.some((row) => String(row.id) === selectedId)) return;
    setSelectedId(rows.length ? String(rows[0].id) : null);
  }, [rows, channelRows, selectedId]);

  useEffect(() => {

  }, []);

  const loadSelected = useCallback(async () => {
    const sequence = ++loadSequence.current;
    if (!selected) {
      setMessages([]);
      setComments([]);
      setCustomer(null);
      setLoadedActivityId(null);
      return;
    }
    const id = String(selected.id);
    const customerId = String(selected.customer ?? "");
    const current = () => selectionRef.current === id && loadSequence.current === sequence;
    setDeliveryFailed(false);
    setError(null);
    // History/read acknowledgement must not wait for contact decoration or transport availability.
    await Promise.all([
      (async () => {
        try {
          type ActivityEntry = {id:string;conversationId:string;kind:string;direction:string;channel:string;authorName:string;authorAvatarUrl:string|null;mine:boolean;body:string;at:string;deliveryStatus:string;attachments?:Attachment[];bodyHtml?:string|null;senderAccount?:string|null};
          const entries:ActivityEntry[]=[];let hasMore=false;let cursor:string|null=null;
          for(let page=0;page<historyPages;page++) {
            const query=new URLSearchParams({limit:"100"});
            if(cursor)query.set("cursor",cursor);
            const response=await fetch(`/api/crm/contacts/${customerId}/activity?${query}`,{credentials:"same-origin"});
            const result=await checkedJson<{rows?:ActivityEntry[];hasMore:boolean;nextCursor:string|null}>(response,t("crm.error.history"));
            entries.push(...(result.rows??[]));hasMore=result.hasMore;cursor=result.nextCursor;
            if(!hasMore)break;
            if(!cursor) {
              if(current()) setError(t("crm.error.history"));
              return;
            }
          }
          if (!current()) return;
          setHasMoreHistory(hasMore);
          const chronological = [...entries].reverse();
          setMessages(chronological.filter(entry => entry.kind !== "NOTE").map(entry => ({ ...entry, id: entry.id.replace(/^message:/, ""), sentAt: entry.at })) as Message[]);
          setComments(chronological.filter(entry => entry.kind === "NOTE").map(entry => ({id:entry.id, authorName:entry.authorName, authorAvatarUrl:entry.authorAvatarUrl, body:entry.body, createdAt:entry.at, mine:entry.mine})));
          setLoadedActivityId(id);
          const nextReply = replySelection(customerId, entries, replySelectionRef.current, !!draftRef.current.trim() || pinnedRef.current === id);
          replySelectionRef.current = nextReply.state;
          if (nextReply.conversationId && channelRows.some(row => String(row.id) === nextReply.conversationId)) setSelectedId(nextReply.conversationId);
        } catch (loadError) {
          if (current()) setError(loadError instanceof Error ? loadError.message : t("crm.error.history"));
        }
      })(),
      (async () => {
        try {
          const nextCustomer = customerId ? await request<{fields: EntityRecord}>(`/contacts/${customerId}`) : null;
          if (current()) setCustomer(nextCustomer?.fields ?? null);
        } catch (loadError) {
          if (current()) setError(loadError instanceof Error ? loadError.message : t("crm.error.conversation"));
        }
      })(),
      (async () => {
        try {
          const response = await fetch(`/api/crm/conversations/${id}/delivery${workspaceKey ? `?workspace=${encodeURIComponent(workspaceKey)}` : ""}`, { credentials: "same-origin" });
          const nextDelivery = await checkedJson<DeliveryConnection>(response, t("crm.error.connection"));
          if (!current()) return;
          setDelivery(nextDelivery);
          setLoadedConversationId(id);
        } catch (loadError) {
          if (!current()) return;
          setDelivery(null);
          setDeliveryFailed(true);
          setError(loadError instanceof Error ? loadError.message : t("crm.error.connection"));
        }
      })(),
    ]);
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
  // The clip follows the channel: a full tray, a read-only channel and a channel that simply does
  // not carry files are three different answers, and the button's title gives the right one.
  const attachState = (() => {
    if (replyState.disabled) return { disabled: true, reason: replyState.message || "Replies are unavailable" };
    const limit = delivery?.maxAttachments ?? 0;
    if (uploading > 0) return { disabled: true, reason: t("crm.chat.uploading", { count: uploading }) };
    if (limit <= 0) return { disabled: true, reason: delivery?.attachmentReason || t("crm.chat.attachUnavailable") };
    if (pending.length >= limit) return { disabled: true, reason: t("crm.chat.attachLimit", { count: limit }) };
    return { disabled: false, reason: t("crm.chat.attach") };
  })();
  // Pin the recipient once writing starts, but only with this conversation's own connection loaded.
  useEffect(() => {
    if (loadedConversationId !== selectedId || recipientDraft !== null) return;
    if (mode === "reply" && (draft.trim() || pending.length || subjectDraft !== null) && delivery?.recipient)
      setRecipientDraft(delivery.recipient);
  }, [mode, draft, pending.length, subjectDraft, delivery?.recipient, recipientDraft, loadedConversationId, selectedId]);
  // The mailboxes a reply may leave from. A choice only exists with two or more; a stored choice the
  // connection no longer offers falls back to its default.
  const senders = (delivery?.senders ?? []).filter(sender => sender && typeof sender.id === "string" && sender.id);
  const chooseSender = senders.length > 1;
  const defaultSender = senders.find(sender => sender.id === delivery?.sender)?.id ?? senders[0]?.id ?? null;
  const senderId = chooseSender ? senders.find(sender => sender.id === composed.from)?.id ?? defaultSender : null;
  const showAttachments = mode === "reply" && !!action(config, "sendReply");
  const chooseAttachment = () => {
    if (attachState.disabled) return;
    fileInputRef.current?.click();
  };
  const composerStatusId = useId();
  const fileDropReason = mode !== "reply" ? t("crm.chat.dropReplyOnly") : attachState.disabled ? attachState.reason : null;
  const isFileDrag = (event: DragEvent) => Array.from(event.dataTransfer.types).includes("Files");
  const clearFileDrag = () => { fileDragDepth.current = 0; setFileDragActive(false); };
  const fileDragEnter = (event: DragEvent) => {
    if (!isFileDrag(event)) return;
    event.preventDefault();
    fileDragDepth.current += 1;
    setFileDragActive(true);
  };
  const fileDragOver = (event: DragEvent) => {
    if (!isFileDrag(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = fileDropReason ? "none" : "copy";
  };
  const fileDragLeave = (event: DragEvent) => {
    if (!isFileDrag(event)) return;
    event.preventDefault();
    fileDragDepth.current = Math.max(0, fileDragDepth.current - 1);
    if (!fileDragDepth.current) setFileDragActive(false);
  };
  const filesDropped = (event: DragEvent) => {
    if (!isFileDrag(event)) return;
    event.preventDefault();
    event.stopPropagation();
    clearFileDrag();
    if (fileDropReason) { setError(fileDropReason); return; }
    void attach(event.dataTransfer.files);
  };

  const acknowledgeRead = useCallback(() => Promise.all(channelRows.filter(row =>
    String(row.customer) === String(selected?.customer) && Number(row.unreadCount || 0) > 0 && readRows.get(String(row.id)) !== row
  ).map(async row => {
    await command(`/api/crm/conversations/${row.id}/read`);
    setReadRows(previous => new Map(previous).set(String(row.id), row));
  })), [channelRows, selected?.customer, workspaceKey, readRows]);
  const readRevision = selected && loadedActivityId === selectedId
    ? JSON.stringify([workspaceKey, selectedId, selected.unreadCount,
      messages.filter(message => message.direction === "INBOUND").map(message => message.id)])
    : null;
  useConversationRead(timelineEndRef, readRevision, acknowledgeRead);

  // A contact's unified row opens the conversation holding an unsent draft, so the text is where it was left.
  const draftedConversation = (row: EntityRecord) => {
    if (!row.customer) return undefined;
    const drafted = channelRows.filter(item => String(item.customer) === String(row.customer)
      && hasDraftText(composerDrafts.read(composerDrafts.key(workspaceKey, String(item.id)))));
    return drafted.find(item => String(item.id) === String(row.id)) ?? drafted[0];
  };
  /** `exact` opens this very conversation and keeps it open; a list click may go to a drafted sibling. */
  const select = (row: EntityRecord, exact = false) => {
    setMobileChatOpen(true);
    setActionsOpen(false);
    setContactPanelOpen(false);
    const target = exact ? row : draftedConversation(row) ?? row;
    pinnedRef.current = exact ? String(target.id) : null;
    if (String(target.id) === selectedId) return;
    selectionDismissedRef.current = false;
    replySelectionRef.current = null;
    if (activeFolder && owner(target)?.key !== activeFolder.key) setFolderKey(owner(target)?.key ?? null);
    const id = String(target.id);
    setDelivery(null);
    setError(null);
    setSelectedId(id);
  };
  /** Opens a conversation by id: in place when this feed has it, otherwise through the workspace. */
  const openConversation = (conversationId: string) => {
    setMailboxOpen(false);
    const row = channelRows.find(item => String(item.id) === conversationId);
    if (row) { select(row, true); return; }
    if (onOpenElsewhere) { onOpenElsewhere(conversationId); return; }
    openRoute(`onno://${currentRoute()}?conversation=${encodeURIComponent(conversationId)}`);
  };

  /**
   * Upload what the agent picked, then keep it in the tray until the reply is sent. Uploading on
   * pick rather than on send means the paperclip and the Send button never race, and a file that
   * the server refuses says so while there is still a composer to say it in.
   */
  const attach = async (files: FileList | null) => {
    if (!files?.length || attachState.disabled || !draftKey) return;
    // Files belong to the draft they were picked for, even if the reader moves on while they upload.
    const conversationId = selectedId;
    const key = draftKey;
    const here = () => selectionRef.current === conversationId;
    let maxFileSize: number;
    try {
      const limits = await fetch("/api/media/limits", {credentials:"same-origin"});
      const policy = await checkedJson<{maxFileSize: number}>(limits, t("crm.chat.uploadLimitsUnavailable"));
      if (typeof policy.maxFileSize !== "number" || policy.maxFileSize <= 0) {
        setError(t("crm.chat.uploadLimitsUnavailable"));
        return;
      }
      maxFileSize = policy.maxFileSize;
    } catch {
      if (here()) setError(t("crm.chat.uploadLimitsUnavailable"));
      return;
    }
    const room = (delivery?.maxAttachments ?? 0) - composerDrafts.files(key).length;
    const chosen = Array.from(files).slice(0, Math.max(0, room));
    if (here()) setError(chosen.length < files.length ? t("crm.chat.attachTooMany", { count: delivery?.maxAttachments ?? 0 }) : null);
    for (const file of chosen) {
      if (file.size > maxFileSize) {
        if (here()) setError(t("crm.chat.uploadTooLarge", {name:file.name, limit:fileSize(maxFileSize)}));
        continue;
      }
      setUploading(count => count + 1);
      try {
        const form = new FormData();
        form.append("file", file);
        const token = document.cookie.split(";").map(part => part.trim()).find(part => part.startsWith("XSRF-TOKEN="))?.slice(11);
        const response = await fetch("/api/media", { method: "POST", credentials: "same-origin",
          headers: token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : undefined, body: form });
        if (!response.ok) {
          const failure = await response.json().catch(() => ({}));
          if (selectionRef.current === conversationId) {
            setError(failure.message || failure.detail || t("crm.chat.uploadFailedFile", { name: file.name }));
          }
          continue;
        }
        const stored = await response.json();
        setPending(current => current.some(item => item.url === stored.url) ? current : [...current, {
          url: stored.url, filename: stored.filename || file.name,
          contentType: stored.contentType || file.type || "application/octet-stream",
          size: stored.size ?? file.size,
          image: /^image\/(png|jpeg|gif|webp|avif)$/.test(stored.contentType || file.type || ""),
        }], key);
      } catch (uploadError) {
        if (here()) setError(uploadError instanceof Error ? uploadError.message : t("crm.chat.uploadFailed"));
      } finally {
        setUploading(count => count - 1);
      }
    }
  };

  const submit = async () => {
    const hasFiles = mode === "reply" && pending.length > 0;
    if (!action(config, mode) || !action(config, mode === "reply" ? "sendReply" : "sendNote") || !selected || !draftKey
      || (!(mode === "note" ? noteDraft : draft).trim() && !hasFiles) || busy || uploading > 0
      || (mode === "reply" && replyState.disabled)) return;
    // What goes out is this conversation's draft as it stands now; only that part is cleared after.
    const sentKey = draftKey, sentMode = mode;
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
          recipient: conversationChannel(selected) === "EMAIL" ? recipientDraft ?? delivery?.recipient : undefined,
          subject: conversationChannel(selected) === "EMAIL" ? subjectDraft : undefined,
          attachments: pending.map(file => file.url),
          // Only offered with a real choice, so older backends never see the field.
          ...(chooseSender && senderId ? { from: senderId } : {}),
        });
      }
      composerDrafts.clearSent(sentKey, sentMode);
      setExpanded(false);
      await loadSelected();
    } catch (sendError) {
      setError(sendError instanceof Error ? sendError.message : t("crm.error.send"));
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
      setError(retryError instanceof Error ? retryError.message : t("crm.error.retry"));
    } finally { setBusy(false); }
  };

  useEffect(() => {
    if (config && !action(config, mode)) { const next = config.actions.find(a => a.visible && ["reply", "note"].includes(a.key)); if (next) setMode(next.key as ComposerMode); }
  }, [config, mode]);

  // A conversation the workspace was asked to open (it scopes the feed so the row arrives).
  const handledFocus = useRef(0);
  useEffect(() => {
    if (!focusConversation || handledFocus.current === focusConversation.nonce) return;
    const row = channelRows.find(item => String(item.id) === focusConversation.id);
    if (!row) return;
    handledFocus.current = focusConversation.nonce;
    select(row, true);
    onFocusHandled?.();
  }, [focusConversation, channelRows]);

  // Writing moves with the window: into it when it opens, back to the inline field when it closes
  // (the expand button that opened it is gone by then, so the dialog cannot hand focus back itself).
  const wasExpanded = useRef(false);
  useEffect(() => {
    if (wasExpanded.current === expanded) return;
    wasExpanded.current = expanded;
    const frame = requestAnimationFrame(() => focusComposerField(expanded
      ? composerWindowRef.current : inboxRootRef.current?.querySelector("[data-chat-composer]"), mode));
    return () => cancelAnimationFrame(frame);
  }, [expanded]);
  useEffect(() => { if (!selectedId) setExpanded(false); }, [selectedId]);

  const [hostActions,setHostActions]=useState<{id:string;title:string;enabled:boolean}[]>([]);
  useEffect(()=>{
    let live=true;setHostActions([]);
    if(selectedId && workspaceKey)void request<{id:string;title:string;enabled:boolean}[]>(
      `/inbox-workspaces/${encodeURIComponent(workspaceKey)}/conversations/${selectedId}/actions`)
      .then(items=>{if(live)setHostActions(items);}).catch(()=>{});
    return()=>{live=false;};
  },[selectedId,workspaceKey,selected]);
  const extensionContext: ExtensionContext = {
    surface:"crm-chat", kind:"catalogs", name:"crm_conversations", recordId:selected ? String(selected.id) : undefined,
    record:selected ?? undefined, workspaceKey, permissions:{canWrite:!!action(config,"edit"),canReply:!!action(config,"reply")},
    actions:hostActions.map(a=>({...a,enabled:a.enabled&&!busy})),
    refresh:loadSelected,
    openRecord:(kind,name,id)=>openRoute(`onno://${kind}/${name}/${id}`),
    insertDraft:text=>{if(replyState.disabled)throw new Error(t("crm.error.replyUnavailable"));const next=draft.trim()?`${draft}\n\n${text}`:text;if(next.length>(delivery?.maxTextLength??4096))throw new Error(t("crm.error.replyTooLong"));setMode("reply");setDraft(next);},
    execute:async (name,input)=>{
      if(!selected || !workspaceKey || busy || !hostActions.some(a=>a.id===name&&a.enabled))throw new Error(t("crm.error.action"));
      const result=await command(`/api/crm/inbox-workspaces/${encodeURIComponent(workspaceKey)}/conversations/${selected.id}/actions/${encodeURIComponent(name)}`,{inputs:input});
      await loadSelected();return result;
    },
  };
  if (!config) return configError ? <p role="alert" className="p-4 text-sm">{configError}</p> : <InboxSkeleton />;

  if (!loading && !toolbar && !rows.length && !folders.length) {
    return (
      <div className="rounded-panel border border-dashed border-border px-6 py-16 text-center">
        <div className="text-sm font-medium text-foreground">{t("crm.inbox.noMatches")}</div>
        <div className="mt-1 text-xs text-muted-foreground">{t("crm.inbox.noMatchesHint")}</div>
      </div>
    );
  }

  const senderOptions = channelRows.filter(row => String(row.id) === selectedId || (!!selected?.customer && String(row.customer) === String(selected.customer)));
  const senderIdentity = selected && <span className="flex min-w-0 items-center gap-2"><ChannelLogo channel={conversationChannel(selected)} className="size-4" /><span className="truncate">{string(selected, "inboxDisplay", delivery?.label || conversationChannel(selected))}</span></span>;
  const selectTriggerClass = "h-9 w-auto max-w-full min-w-0 gap-2 rounded-field border-0 bg-transparent px-1 text-xs shadow-none hover:bg-muted focus:ring-1";
  // Switching to the contact's other conversation (another channel or mailbox thread).
  const conversationSwitch = (label: string) => senderOptions.length === 1 ? <span className="flex h-9 min-w-0 items-center px-1">{senderIdentity}</span> : (<Select value={selectedId || ""} disabled={busy || !!draft.trim() || subjectDraft !== null || (recipientDraft !== null && recipientDraft !== delivery?.recipient) || mode === "note"}
                onValueChange={(id: string) => { const target = channelRows.find(row => String(row.id) === id); if (target) setSelectedId(String(target.id)); }}>
                <SelectTrigger aria-label={label} className={selectTriggerClass}
                  title={draft.trim() ? t("crm.chat.switchLocked") : t("crm.chat.switchHint")}>
                  <SelectValue>{senderIdentity}</SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {senderOptions.map(row => (
                    <SelectItem key={String(row.id)} value={String(row.id)}>
                      <span className="flex min-w-0 items-center gap-2">
                        <ChannelLogo channel={conversationChannel(row)} className="size-4" />
                        <span className="truncate">{string(row, "inboxDisplay", conversationChannel(row))}</span>
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>);
  // The sending mailbox, when the connection offers more than one. It takes the From row; switching
  // conversations then moves to a row of its own, labelled as the channel it is.
  const senderLabel = (id: string | null) => { const sender = senders.find(item => item.id === id); return sender?.label || sender?.id || ""; };
  const mailboxSelect = chooseSender && senderId ? <Select value={senderId} disabled={busy || replyState.disabled || mode === "note"}
    onValueChange={(id: string) => updateDraft(current => ({ ...current, from: id === defaultSender ? null : id }))}>
    <SelectTrigger aria-label={t("crm.chat.sendFrom")} className={selectTriggerClass}>
      <SelectValue><span className="truncate">{senderLabel(senderId)}</span></SelectValue>
    </SelectTrigger>
    <SelectContent>{senders.map(sender => <SelectItem key={sender.id} value={sender.id}>{sender.label || sender.id}</SelectItem>)}</SelectContent>
  </Select> : null;
  const senderControls = (labels: boolean) => selected ? <div data-sender-account className={labels ? "flex min-w-0 max-w-full flex-col text-xs" : "flex min-w-0 max-w-full flex-wrap items-center gap-1 text-xs"}>
    <div className="flex min-w-0 max-w-full items-center gap-2">
      {labels && <span className="w-12 shrink-0 text-muted-foreground">{t("crm.chat.from")}</span>}
      {mailboxSelect ?? conversationSwitch(t("crm.chat.sendFrom"))}
    </div>
    {mailboxSelect && senderOptions.length > 1 && <div className="flex min-w-0 max-w-full items-center gap-2">
      {labels && <span className="w-12 shrink-0 text-muted-foreground">{t("crm.chat.channel")}</span>}
      {conversationSwitch(t("crm.chat.switchConversation"))}
    </div>}
  </div> : null;
  const sendingAccount = senderControls(!compact);

  const contactName = string(selected, "customerDisplay", t("crm.chat.unknownCustomer"));
  // Visible list actions: what was only on a row's right-click (folders) and the mailbox search.
  const canCreateFolder = chatGroups.ready && !flat;
  const listActions = (scoped && onShowAll) || canCreateFolder || mailboxes.length > 0
    ? <div role="group" aria-label={t("crm.inbox.listActions")} className="flex shrink-0 flex-wrap items-center gap-1 border-b border-border/50 px-2 py-1.5">
      {scoped && onShowAll && <Button type="button" size="toolbar" variant="ghost" onClick={onShowAll}><ArrowLeft className="size-4" aria-hidden="true" />{t("crm.inbox.showAll")}</Button>}
      {canCreateFolder && <NewChatGroup change={chatGroups.change} conversationId={selected ? String(selected.id) : null} contactName={contactName} />}
      {mailboxes.length > 0 && <Button type="button" size="toolbar" variant="ghost" aria-haspopup="dialog" onClick={() => setMailboxOpen(true)}>
        <MailSearch className="size-4" aria-hidden="true" />{t("crm.mailbox.find")}
      </Button>}
    </div> : null;
  const modeOptions = (config?.actions ?? []).filter(a => a.visible && ["reply", "note"].includes(a.key)).map(a => ({ value: a.key as ComposerMode, label: a.label }));
  const sendAction = action(config, mode === "reply" ? "sendReply" : "sendNote");
  const cannotSend = !action(config, mode) || busy || uploading > 0 || (!(mode === "note" ? noteDraft : draft).trim() && !(mode === "reply" && pending.length > 0)) || (mode === "reply" && replyState.disabled);
  const canExpand = mode === "note" ? !!action(config, "note") : !replyState.disabled;
  // Ctrl/Cmd+Enter sends; the window listens around its fields too, so a handled key is not sent twice.
  const sendShortcut = (event: { key: string; metaKey: boolean; ctrlKey: boolean; defaultPrevented: boolean; preventDefault: () => void }) => {
    if (event.defaultPrevented) return;
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      void submit();
    }
  };
  const fileDropOverlay = fileDragActive && <div className="pointer-events-none absolute inset-2 z-50 flex items-center justify-center rounded-panel border-2 border-dashed border-primary bg-background/95 p-6 text-center" role="status">
    <div className="flex flex-col items-center gap-3">
      <Paperclip className="size-8 text-primary" aria-hidden="true" />
      <p className="text-sm font-medium">{fileDropReason || t("crm.chat.dropFiles")}</p>
      {!fileDropReason && <p className="text-xs text-muted-foreground">{t("crm.chat.dropFilesHint")}</p>}
    </div>
  </div>;
  // One composer, drawn inline under the chat or in the large window: both edit the same draft.
  const composerFields = (surface: "inline" | "window") => {
    if (!selected) return null;
    const inline = surface === "inline";
    return <>
      {inline ? <div className={`mb-2 flex items-center justify-between ${compact ? "gap-1" : "gap-3"}`}>
        {compact && chrome?.mobileComposer?.center?.includes("channel") ? <Segmented value={mode} onChange={(value: ComposerMode) => { if (!busy) setMode(value); }} options={modeOptions} /> : sendingAccount}
        <span className="flex shrink-0 items-center gap-1">
          <span hidden={compact && mode === "reply"} title={mode === "reply" ? delivery?.label : undefined} className="text-[10px] text-muted-foreground">
            {mode === "note" ? t("crm.composer.noteHint") : ""}
          </span>
          {canExpand && <Button type="button" variant="ghost" size="toolbar" className="size-8 shrink-0 p-0" aria-haspopup="dialog"
            aria-label={t("crm.composer.expand")} title={t("crm.composer.expand")} onClick={() => setExpanded(true)}>
            <Maximize2 className="size-4" aria-hidden="true" />
          </Button>}
        </span>
      </div> : <div className="mb-2 flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">{senderControls(true)}</div>
        <Button type="button" variant="ghost" size="toolbar" data-composer-maximize className="size-8 shrink-0 p-0"
          aria-label={maximized ? t("crm.composer.restoreSize") : t("crm.composer.maximize")}
          title={maximized ? t("crm.composer.restoreSize") : t("crm.composer.maximize")} onClick={() => setMaximized(value => !value)}>
          {maximized ? <Minimize2 className="size-4" aria-hidden="true" /> : <Maximize2 className="size-4" aria-hidden="true" />}
        </Button>
      </div>}
      {mode === "reply" && conversationChannel(selected) === "EMAIL" && !!delivery?.recipient && <div className="mb-1 flex min-w-0 items-center gap-2 text-xs">
        <span className="w-12 shrink-0 text-muted-foreground">{t("crm.chat.to")}</span>
        {(delivery.recipients?.length ?? 0) > 1 ? <Select value={recipientDraft ?? delivery.recipient} disabled={busy || replyState.disabled}
          onValueChange={(value:string)=>setRecipientDraft(value===delivery.recipient?null:value)}>
          <SelectTrigger aria-label={t("crm.chat.to")} className="h-9 w-auto max-w-full min-w-0 gap-2 border-0 bg-transparent px-1 text-xs shadow-none hover:bg-muted"><SelectValue /></SelectTrigger>
          <SelectContent>{delivery.recipients?.map(address=><SelectItem key={address} value={address}>{address}</SelectItem>)}</SelectContent>
        </Select> : <span className="flex h-9 min-w-0 items-center truncate px-1">{recipientDraft ?? delivery.recipient}</span>}
      </div>}
      {mode === "reply" && recipientDraft !== null && recipientDraft !== delivery?.recipient && <p className="mb-2 text-xs text-muted-foreground">{t("crm.chat.newRecipientThread")}</p>}
      {mode === "reply" && conversationChannel(selected) === "EMAIL" && <div className="mb-2">
        <div className="flex min-w-0 items-center gap-2 border-b border-border/60 pb-1 transition-colors focus-within:border-primary">
          <label htmlFor={subjectId} className="w-12 shrink-0 text-xs text-muted-foreground">{t("crm.chat.subject")}</label>
          <Input id={subjectId} value={subjectDraft ?? string(selected, "subject", "")}
            maxLength={998} disabled={busy || replyState.disabled}
            className="h-9 min-w-0 flex-1 rounded-none border-0 bg-transparent px-1 py-0 text-sm shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:shadow-none"
            onChange={(event: { target: { value: string } }) => setSubjectDraft(event.target.value === string(selected, "subject", "") ? null : event.target.value)} />
        </div>
        {subjectDraft !== null && <p className="mt-1 text-[10px] text-muted-foreground">{t("crm.chat.newSubjectThread")}</p>}
      </div>}
      <div hidden={mode !== "note"} className={!inline && mode === "note" ? "flex min-h-0 flex-1 flex-col" : undefined}>
        <NoteInput key={draftKey ?? ""} draftKey={draftKey ?? undefined} value={noteDraft} autoGrow={inline} fill={!inline}
          disabled={busy || !action(config, "note")} onChange={(text, body) => updateDraft(current => ({ ...current, note: text, noteBody: body }))}
          onSubmit={() => void submit()} />
      </div>
      {mode === "reply" && !replyState.disabled && <Textarea
        ref={inline ? replyInputRef : undefined}
        data-composer-reply=""
        value={draft}
        disabled={replyState.disabled}
        onChange={(event: { target: { value: string } }) => setDraft(event.target.value)}
        onKeyDown={sendShortcut}
        placeholder={replyState.placeholder}
        aria-describedby={replyState.message ? composerStatusId : undefined}
        maxLength={delivery?.maxTextLength ?? 4096}
        aria-label={t("crm.composer.replyLabel")}
        className={`${inline ? "min-h-16" : "min-h-48 flex-1"} resize-none border-0 bg-transparent dark:bg-transparent px-2 shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:shadow-none`}
      />}
      {/* What is about to go with the reply. It sits under the text because that is the order
          it will arrive in, and each chip can be taken back out before sending. */}
      {mode === "reply" && (pending.length > 0 || uploading > 0) && <div className="mt-1 flex flex-wrap gap-1.5 px-2">
        {pending.map(file => (
          <span key={file.url} className="inline-flex max-w-full items-center gap-1.5 rounded-pill bg-muted px-2 py-1 text-[11px]">
            <Paperclip className="size-3 shrink-0" aria-hidden="true" />
            <span className="min-w-0 truncate font-medium">{file.filename}</span>
            {file.size > 0 && <span className="shrink-0 tabular-nums text-muted-foreground">{fileSize(file.size)}</span>}
            <Button variant="ghost" type="button" aria-label={t("crm.chat.removeFile", { name: file.filename })} disabled={busy}
              className="h-auto p-0 text-inherit"
              onClick={() => setPending(current => current.filter(item => item.url !== file.url))}>
              <X className="size-3" />
            </Button>
          </span>
        ))}
        {uploading > 0 && <span role="status" className="inline-flex items-center gap-1.5 rounded-pill bg-muted px-2 py-1 text-[11px] text-muted-foreground">
          {t("crm.chat.uploading", { count: uploading })}
        </span>}
      </div>}
      {mode === "reply" && replyState.message && <div id={composerStatusId} role="status" className={`flex items-center gap-2 rounded-field bg-muted/50 text-xs text-muted-foreground ${compact && inline ? "px-2 py-1" : "px-3 py-2"}`}>
        <span className="flex-1">{replyState.message}</span>
        {replyState.retry && <Button variant="ghost" aria-label={t("crm.chat.checkAgain")} className={compact && inline ? "size-10 shrink-0 p-0" : undefined} onClick={() => void loadSelected()}>{compact && inline ? <RefreshCw className="size-4" /> : t("crm.chat.checkAgain")}</Button>}
      </div>}
      {error && <p role="alert" className="mt-2 text-xs text-destructive">{error}</p>}
    </>;
  };
  const composerWindow = selected && expanded ? <DialogShell open onOpenChange={(open: boolean) => { if (!open) setExpanded(false); }}
    size="lg" tone={mode === "note" ? "warning" : "info"} icon={<PenLine className="size-5" aria-hidden="true" />}
    title={mode === "note" ? t("crm.composer.noteTitle", { name: contactName }) : t("crm.composer.replyTitle", { name: contactName })}
    description={mode === "note" ? t("crm.composer.noteHint") : null}
    footer={<div className="flex w-full min-w-0 flex-wrap items-center gap-2">
      {modeOptions.length > 0 && <Segmented value={mode} onChange={(value: ComposerMode) => { if (!busy) setMode(value); }} options={modeOptions} />}
      {showAttachments && <span className="inline-flex" title={attachState.reason}>
        <Button variant="ghost" type="button" aria-label={t("crm.chat.attach")} disabled={attachState.disabled}
          aria-description={attachState.disabled ? attachState.reason : undefined} onClick={chooseAttachment}>
          <Paperclip className="size-4" />
        </Button>
      </span>}
      <ExtensionSlot name="crm.chat.composer" context={{ ...extensionContext, insertDraft: mode === "reply" ? extensionContext.insertDraft : undefined }} className="flex items-center gap-2" />
      <span className="flex-1" />
      {sendAction && <Button type="button" disabled={cannotSend} onClick={() => void submit()}>
        {busy ? t("crm.composer.sending") : sendAction.label}
      </Button>}
    </div>}>
    <div ref={composerWindowRef} data-crm-composer-window="" data-maximized={maximized} onKeyDown={sendShortcut}
      className="relative flex min-h-0 flex-1 flex-col">
      <style>{COMPOSER_WINDOW_CSS}</style>
      {composerFields("window")}
      {fileDropOverlay}
    </div>
  </DialogShell> : null;

  return (
    <div
      ref={inboxRootRef}
      tabIndex={-1}
      data-testid="crm-inbox-workspace"
      className="crm-chat-layout relative grid h-full min-h-0 max-h-full w-full overflow-hidden overscroll-none"
      data-contact-open={!!(contactPanelOpen && selected && config)}
      data-compact={compact}
      data-detail-overlay={detailOverlay}
      data-chat-open={mobileChatOpen && !!selected}
      style={{ height: "100%", minHeight: 0, maxHeight: "100%", contain: "layout size" }}
    >
      <section data-slide-page="list" aria-label={t("crm.inbox.chatSelection")} aria-hidden={compact && mobileChatOpen && !!selected} inert={compact && mobileChatOpen && !!selected} className={`${islandSurface} crm-conversation-pane min-h-0 min-w-0 flex flex-col overflow-hidden`}>
        <style>{`
          .crm-chat-layout { --contact-width:320px; --contact-gap:12px; grid-template-columns:300px minmax(0,1fr) auto; column-gap:0; }
          .crm-chat-layout > section { grid-column:1; grid-row:1; margin-right:12px; }
          .crm-chat-layout > main { grid-column:2; grid-row:1; border-radius:var(--radius-panel, 16px); background:hsl(var(--card)); }
          .crm-chat-header { --workspace-toolbar-edge:88px; margin:0; border-bottom:1px solid hsl(var(--border)); border-radius:0; }
          .crm-chat-composer { margin:0 12px 12px; max-height:45%; overflow-y:auto; }
          .crm-chat-composer textarea { min-height:64px; max-height:min(240px, 40vh); }
          /* A row's "⋯" menu button: shown on hover or keyboard focus, always on touch screens (no right-click there). */
          [data-row-menu-host] { position:relative; }
          [data-row-menu-host] > [data-row-menu-trigger] { position:absolute; top:6px; right:6px; z-index:1; opacity:0; transition:opacity 120ms ease; }
          [data-row-menu-host]:hover > [data-row-menu-trigger], [data-row-menu-host]:focus-within > [data-row-menu-trigger], [data-row-menu-trigger][aria-expanded="true"] { opacity:1; }
          @media (hover:none) {
            [data-row-menu-host] { padding-right:34px; }
            [data-row-menu-host] > [data-row-menu-trigger] { top:50%; right:2px; transform:translateY(-50%); opacity:1; }
          }
          @media (prefers-reduced-motion: reduce) { [data-row-menu-trigger] { transition:none; } }
          /* Like the inbox navigation rail: the slot's width animates while the island keeps its size and fades. */
          .crm-contact-slot { grid-column:3; grid-row:1; min-width:0; min-height:0; overflow:hidden; width:0;
            transition:width var(--resize-dur, 300ms) var(--resize-ease, cubic-bezier(0.22, 1, 0.36, 1)); }
          .crm-contact-slot > .crm-contact-island { width:var(--contact-width); margin-left:var(--contact-gap); opacity:0;
            transition:opacity var(--resize-dur, 300ms) var(--resize-ease, cubic-bezier(0.22, 1, 0.36, 1)); }
          .crm-contact-island { height:100%; }
          .crm-chat-layout[data-contact-open="true"] .crm-contact-slot { width:calc(var(--contact-width) + var(--contact-gap)); }
          .crm-chat-layout[data-contact-open="true"] .crm-contact-slot > .crm-contact-island { opacity:1; }
          .crm-chat-layout[data-detail-overlay="true"]:not([data-compact="true"]) .crm-contact-slot { position:absolute; inset:0 0 0 auto; width:min(360px,100%); z-index:30;
            transform:translate3d(105%,0,0); transition:transform var(--resize-dur, 300ms) var(--resize-ease, cubic-bezier(0.22, 1, 0.36, 1)); }
          .crm-chat-layout[data-detail-overlay="true"]:not([data-compact="true"]) .crm-contact-slot > .crm-contact-island { width:100%; margin-left:0; opacity:1; }
          .crm-chat-layout[data-detail-overlay="true"][data-contact-open="true"]:not([data-compact="true"]) .crm-contact-slot { transform:translate3d(0,0,0); }
          @media (prefers-reduced-motion: reduce) { .crm-contact-slot, .crm-contact-slot > .crm-contact-island { transition:none !important; } }
          .crm-chat-layout[data-compact="true"] { grid-template-columns:minmax(0,1fr); gap:0; }
          .crm-chat-layout[data-compact="true"] > section,
          .crm-chat-layout[data-compact="true"] > main { grid-column:1; grid-row:1; position:absolute; inset:0; width:100%; height:100%; transform:translate3d(0,0,0); backface-visibility:hidden; }
          .crm-chat-layout[data-compact="true"][data-chat-open="false"] > main { transform:translate3d(100%,0,0); pointer-events:none; }
          .crm-chat-layout[data-compact="true"][data-chat-open="true"] > section { transform:translate3d(-100%,0,0); pointer-events:none; }
          .crm-chat-layout[data-compact="true"] .crm-chat-composer { margin:0 8px 8px; }
          .crm-chat-layout[data-compact="true"] .crm-contact-slot { grid-column:1; grid-row:1; display:block; position:absolute; inset:0; width:100%; height:100%; z-index:30; transform:translateX(100%); }
          .crm-chat-layout[data-compact="true"][data-contact-open="true"] .crm-contact-slot { transform:translateX(0); }
          .crm-chat-layout[data-compact="true"][data-contact-open="true"] > main { transform:translateX(-100%); }
          .crm-chat-layout[data-compact="true"] .crm-contact-island { background:hsl(var(--card)); }
          .crm-chat-layout[data-compact="true"] .crm-contact-slot { width:100%; transition:none; }
          .crm-chat-layout[data-compact="true"] .crm-contact-slot > .crm-contact-island { width:100%; margin-left:0; opacity:1; transition:none; }
          .crm-chat-layout[data-compact="true"] > section { margin-right:0; }
          .crm-chat-layout [data-chat-history] { scrollbar-gutter:stable; }
          .crm-chat-layout [data-chat-history] > * { max-width:880px; margin-left:auto; margin-right:auto; }
          .crm-folder-slide > .crm-folder-page { position:absolute; inset:0; width:100%; max-width:100%; min-width:0; overflow:hidden; transform:translate3d(0,0,0); backface-visibility:hidden; }
          .crm-folder-slide[data-open="false"] > .crm-folder-detail { transform:translate3d(100%,0,0); pointer-events:none; }
          .crm-folder-slide[data-open="true"] > .crm-folder-root { transform:translate3d(-100%,0,0); pointer-events:none; }
        `}</style>
        {toolbar && <WorkspaceToolbar compact={compact} className="shrink-0 border-b border-border/50 p-3"
          layout={{start:["title"], end:["count"], secondary:["controls"], ...chrome?.list}}
          compactLayout={chrome?.mobileList}
          items={[
            {id:"title",content:<span className="text-sm font-semibold">{t("crm.inbox.conversations")}</span>},
            {id:"count",content:<span className="whitespace-nowrap text-xs text-muted-foreground">{conversationCount(total, channelRows.length)}</span>},
            {id:"controls",content:<div className="min-w-0 w-full">{toolbar}</div>},
          ]} />}
        {listActions}
        <div ref={folderSlideRef} className="crm-folder-slide relative min-h-0 min-w-0 w-full flex-1 overflow-hidden" data-open={!!activeFolder}>
          <div data-slide-page="root" ref={rootListRef} className="crm-folder-page crm-folder-root flex min-h-0 min-w-0 flex-col" aria-hidden={!!activeFolder} {...(activeFolder ? {inert: true} : {})}>
            {!toolbar && <div className="flex min-h-16 shrink-0 flex-col justify-center border-b border-border/50 px-3 py-3">
              <div className="text-sm font-semibold">{t("crm.inbox.conversations")}</div>{chatGroups.error && <p role="alert" className="text-xs text-destructive">{chatGroups.error}</p>}
              <div className="mt-0.5 text-[11px] text-muted-foreground">{conversationCount(total, channelRows.length)}{folders.length ? ` · ${folders.length} folders` : ""}</div>
            </div>}
            <ConversationList {...pagination} active={!activeFolder}>
              {!rows.length && !folders.length && (loading ? <ConversationListSkeleton /> : <p className="p-4 text-sm text-muted-foreground">{t("crm.inbox.noMatches")}</p>)}
              {(() => {
                const shown = new Set<string>();
                const entries = rows.map(row => {
                  const folder = owner(row);
                  if (!folder) return <ConversationRow key={String(row.id)} appearance={chrome?.conversationRow} row={row} config={config} workspaceKey={workspaceKey} groups={chatGroups.groups} groupKey={chatGroups.groups.find(group => group.customerIds.includes(String(row.customer)))?.key} onGroupChange={chatGroups.ready ? chatGroups.change : undefined} selected={(!compact || mobileChatOpen) && String(row.customer || row.id) === String(selected?.customer || selectedId)} onSelect={() => select(row)} />;
                  if (shown.has(folder.key)) return null;
                  shown.add(folder.key);
                  const members = folderRows(folder.key);
                  return <Button variant="ghost" key={`folder:${folder.key}`} data-folder-key={folder.key} type="button" onClick={() => openFolder(folder.key)} className="h-auto whitespace-normal font-normal flex w-full items-center gap-3 rounded-field px-3 py-3 text-left hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                    <FolderTile folder={folder} />
                    <span className="min-w-0 flex-1"><span className="block truncate text-[13px] font-semibold">{folder.label}</span><span className="mt-1 block truncate text-[11px] text-muted-foreground">{members.map(member => string(member, "customerDisplay", string(member, "description"))).join(", ")}</span></span>
                    <span className="text-xs tabular-nums text-muted-foreground">{members.length}</span><ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                  </Button>;
                });
                return <>{entries}{folders.filter(folder => !shown.has(folder.key)).map(folder => <Button variant="ghost" key={folder.key} data-folder-key={folder.key} type="button" onClick={() => openFolder(folder.key)} className="h-auto whitespace-normal font-normal flex w-full items-center gap-3 rounded-field px-3 py-3 text-left text-muted-foreground hover:bg-muted/50"><FolderTile folder={folder} size="size-9" icon={16} /><span className="flex-1 text-sm">{folder.label}</span><span className="text-xs">0</span><ChevronRight className="size-4" /></Button>)}</>;
              })()}
            </ConversationList>
          </div>
          <div data-slide-page="folder" ref={detailListRef} className="crm-folder-page crm-folder-detail flex min-h-0 min-w-0 flex-col" aria-hidden={!activeFolder} {...(!activeFolder ? {inert: true} : {})}>
            <div className="flex min-h-16 shrink-0 items-center gap-2 border-b border-border/50 px-3 py-3">
              <Button variant="ghost" size="sm" aria-label={t("crm.inbox.back")} onClick={() => setFolderKey(null)}><ArrowLeft className="size-4" /></Button>
              {displayedFolder && <FolderTile folder={displayedFolder} size="size-8" icon={16} />}
              <div className="min-w-0"><div className="truncate text-sm font-semibold">{displayedFolder?.label}</div><div className="mt-0.5 text-[11px] text-muted-foreground">{detailRows.length} chats in the current view</div></div>
              {chatGroups.groups.filter(group => group.key === displayedFolder?.key).map(group => <ChatGroupActions key={group.key} group={group} change={chatGroups.change} />)}
            </div>
            <ConversationList {...pagination} active={!!activeFolder}>
              {displayedFolder && detailRows.map(row => <ConversationRow key={String(row.id)} appearance={chrome?.conversationRow} row={row} config={config} workspaceKey={workspaceKey} groups={chatGroups.groups} groupKey={chatGroups.groups.find(group => group.customerIds.includes(String(row.customer)))?.key} onGroupChange={chatGroups.ready ? chatGroups.change : undefined} selected={(!compact || mobileChatOpen) && String(row.customer || row.id) === String(selected?.customer || selectedId)} onSelect={() => select(row)} />)}
              {displayedFolder && !detailRows.length && <p className="px-3 py-8 text-center text-xs text-muted-foreground">{t("crm.inbox.emptyFolder")}</p>}
            </ConversationList>
          </div>
        </div>
      </section>

      {selected ? (
        <main onDragEnter={fileDragEnter} onDragOver={fileDragOver} onDragLeave={fileDragLeave} onDrop={filesDropped} onDragEnd={clearFileDrag} data-file-drop-zone data-slide-page="chat" className="relative flex min-h-0 min-w-0 flex-col overflow-hidden border border-border/60" aria-hidden={compact && (!mobileChatOpen || contactPanelOpen)} inert={(compact && !mobileChatOpen) || (contactPanelOpen && detailOverlay)}>
          <header className="crm-chat-header z-10 shrink-0 px-3 py-2">
            <WorkspaceToolbar compact={compact} balanced={compact} className="flex min-h-11 flex-col justify-center"
              layout={{start:["back"], center:["title"], end:["actions","contact"], ...chrome?.chat}}
              compactLayout={chrome?.mobileChat} fit narrowLayout={chrome?.narrowChat}
              items={[
                {id:"back",content:compact && <Button size="toolbar" variant="ghost" aria-label={t("crm.inbox.back")} onClick={() => { setMobileChatOpen(false); setContactPanelOpen(false); inboxRootRef.current?.focus({preventScroll:true}); }}><ArrowLeft className="size-4" /></Button>},
                {id:"actions",content:<Button size="toolbar" variant={actionsOpen ? "secondary" : "ghost"} aria-label={t("crm.chat.actions")} aria-expanded={actionsOpen} aria-controls={actionsId} onClick={() => setActionsOpen(value => !value)}><MoreHorizontal className="size-4" /></Button>},
                {id:"openContact",content:action(config, "edit") && !!selected.customerRef && <Button size="toolbar" variant="ghost" onClick={() => {
                  const ref = selected.customerRef as {type:string;id:string};
                  openRoute(`onno://catalogs/${encodeURIComponent(ref.type)}/${encodeURIComponent(ref.id)}`);
                }}>{t("crm.contact.open")}</Button>},
                {id:"scheduleCall",content:action(config, "logActivity") && <ScheduleCall customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={loadSelected} onInvitation={text => { setMode("reply"); setDraft(previous => previous ? `${previous}\n\n${text}` : text); }} />},
                {id:"logActivity",content:action(config, "logActivity") && <LogActivity customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={loadSelected} />},
                {id:"extensions",content:<ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-wrap items-center gap-2" />},
                {id:"title",content:<div className="min-w-0 truncate text-sm font-semibold text-foreground">{string(selected, "customerDisplay", "Customer")}</div>},
                {id:"avatar",content:!compact && <Avatar name={string(selected, "customerDisplay", "Customer")} url={customerAvatarUrl} className="size-9" />},
                {id:"contact",content:<Button ref={contactPanelToggleRef} variant={compact ? "ghost" : "subtle"} size="toolbar" className={compact ? "size-11 shrink-0 rounded-full p-1" : "w-8 shrink-0 p-0"} aria-label={contactPanelOpen ? t("crm.contact.hide") : t("crm.contact.show")} title={t("crm.contact.details")} aria-expanded={contactPanelOpen} aria-controls={contactPanelId} onClick={() => setContactPanelOpen(value => !value)}>{compact ? <Avatar name={string(selected, "customerDisplay", "Customer")} url={customerAvatarUrl} className="size-9" /> : <ContactRound className="size-4" />}</Button>},
              ]} />
          </header>
          {actionsOpen && <div id={actionsId} className="flex shrink-0 flex-wrap items-center gap-2 border-b border-border/50 px-4 py-2">
            {action(config, "edit") && !!selected.customerRef && <Button size="toolbar" variant="subtle" onClick={() => {
              const ref = selected.customerRef as {type:string;id:string};
              openRoute(`onno://catalogs/${encodeURIComponent(ref.type)}/${encodeURIComponent(ref.id)}`);
            }}>{t("crm.contact.open")}</Button>}
            {action(config, "logActivity") && <ScheduleCall key={`call:${selected.customer}`} customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={loadSelected} onInvitation={text => { setMode("reply"); setDraft(previous => previous ? `${previous}\n\n${text}` : text); }} />}
            {action(config, "logActivity") && <LogActivity key={String(selected.customer)} customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={loadSelected} />}
            <ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-wrap items-center gap-2" />
          </div>}
          <div data-chat-history className="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-4 py-5">

            {hasMoreHistory && <Button size="sm" variant="ghost" onClick={() => setHistoryPages(value => value+1)}>{t("crm.chat.loadEarlier")}</Button>}
            {timeline.map((entry) => entry.message ? (
              entry.message.kind === "SYSTEM_EVENT" ? (
                (config?.showSystemEvents ?? true) ? <ChatMessageBody key={entry.id} message={entry.message} fallback={<TimelineSystemEvent message={entry.message} config={config} />} /> : null
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
                    : entry.message.authorAvatarUrl}
                />
              )
            ) : entry.comment ? <TimelineComment key={entry.id} comment={entry.comment} config={config} /> : null)}
            {!timeline.length && !error ? (
              <div className="py-16 text-center text-xs text-muted-foreground">{t("crm.chat.noMessages")}</div>
            ) : null}
            <div ref={timelineEndRef} />
          </div>

                          <input ref={fileInputRef} type="file" multiple className="hidden"
                  onChange={(event: { target: { files: FileList | null; value: string } }) => {
                    void attach(event.target.files); event.target.value = "";
                  }} />
          <div data-chat-composer className="crm-chat-composer shrink-0 rounded-panel border border-border">
            {expanded ? <p className={`${compact ? "p-2" : "p-3"} text-xs text-muted-foreground`}>{t("crm.composer.inWindow")}</p> : <div className={`${compact ? "p-2" : "p-3"} ${mode === "note" ? "bg-amber-400/5" : ""}`}>
            {composerFields("inline")}
            <WorkspaceToolbar compact={compact} className="mt-2"
              layout={{start:["mode","attach"], end:["send"], secondary:["extensions"], ...chrome?.composer}}
              compactLayout={chrome?.mobileComposer}
              items={[
                {id:"channel",content:sendingAccount},
                {id:"tools",content:<ResponsiveActionPanel presentation="actions" label={t("crm.chat.actions")} align="start" trigger={<Button variant="ghost" className="size-10 shrink-0 p-0" aria-label={t("crm.chat.actions")}><Plus className="size-5" /></Button>}>
                    {action(config, "logActivity") && <ScheduleCall customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={loadSelected} onInvitation={text => {setMode("reply");setDraft(previous => previous ? `${previous}\n\n${text}` : text);}} />}
                    {action(config, "logActivity") && <LogActivity customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={loadSelected} />}
                    {showAttachments && <span className="inline-flex" title={attachState.reason}><Button type="button" variant="ghost" size="toolbar" disabled={attachState.disabled} aria-description={attachState.disabled ? attachState.reason : undefined} onClick={chooseAttachment}><Paperclip className="size-4" />{t("crm.chat.attach")}</Button></span>}
                    <ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-col gap-2" />
                  </ResponsiveActionPanel>},
                {id:"mode",content:(<Segmented
                value={mode}
                onChange={(value: ComposerMode) => { if (!busy) setMode(value); }}
                options={(config?.actions ?? []).filter(a => a.visible && ["reply", "note"].includes(a.key)).map(a => ({ value: a.key as ComposerMode, label: a.label }))}
              />)},
                {id:"attach",content:(showAttachments && <span className="inline-flex" title={attachState.reason}>

                <Button variant="ghost" type="button" aria-label={t("crm.chat.attach")}
                  disabled={attachState.disabled}
                  aria-description={attachState.disabled ? attachState.reason : undefined}
                  onClick={chooseAttachment}>
                  <Paperclip className="size-4" />
                </Button>
              </span>)},
                {id:"extensions",content:(<ExtensionSlot name="crm.chat.composer" context={{ ...extensionContext,
                insertDraft: mode === "reply" ? extensionContext.insertDraft : undefined,
              }} className="flex items-center gap-2" />)},
                {id:"send",content:(sendAction && <Button aria-label={busy ? t("crm.composer.sending") : sendAction.label} className={compact ? "size-10 shrink-0 p-0" : undefined} disabled={cannotSend} onClick={() => void submit()}>
                {compact ? <Send className="size-4" /> : busy ? t("crm.composer.sending") : sendAction.label}
              </Button>)},
              ]} />
            </div>}
          </div>
          {fileDropOverlay}
          {composerWindow}
        </main>
      ) : <main className="flex items-center justify-center p-6 text-sm text-muted-foreground">{t("crm.chat.chooseConversation")}</main>}

      <div data-slide-page="contact" className="crm-contact-slot" aria-hidden={!contactPanelOpen} {...(!contactPanelOpen ? {inert:true} : {})}>
        {selected && config ? <aside id={contactPanelId} aria-label={t("crm.contact.details")}
          className={`${islandSurface} crm-contact-island flex flex-col overflow-hidden`}
          onKeyDown={event => { if (event.key === "Escape" && !event.defaultPrevented) { event.preventDefault(); closeContactPanel(); } }}>
          <div className="flex min-h-16 shrink-0 items-center justify-between gap-2 border-b border-border/50 px-3 py-2">
            {compact && <Button ref={contactPanelCloseRef} size="sm" variant="ghost" aria-label={t("crm.contact.close")} onClick={closeContactPanel}><ArrowLeft className="size-4" /></Button>}
            <span className={compact ? "flex-1 text-center text-sm font-semibold" : "text-sm font-semibold"}>{t("crm.contact.details")}</span>
            {compact ? <span className="w-9" /> : <Button ref={contactPanelCloseRef} size="sm" variant="ghost" aria-label={t("crm.contact.close")} onClick={closeContactPanel}><X className="size-4" /></Button>}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
            <ContactPanel presentation="card" key={String(selected.customer)} id={String(selected.customer)} row={selected} customer={customer} config={config} />
            <ExtensionSlot name="crm.chat.aside" context={extensionContext} className="space-y-3 p-4" />
          </div>
        </aside> : null}
      </div>
      {mailboxOpen && mailboxes.length > 0 && <MailboxSearch open accounts={mailboxes} onOpenChange={setMailboxOpen} onOpenConversation={openConversation} />}
    </div>
  );
}



type WorkspaceSummary = { key: string; label: string; canWrite: boolean };
type ChannelAccount = { id: string; channel: string; label: string };
type WorkspaceFeed = { list: ListDescriptor; channelAccounts: ChannelAccount[]; key: string; label: string; config: ServerConfig; canWrite: boolean; rows: EntityRecord[]; total?: number; hasMore: boolean; nextCursor: string | null };
function workspaceRead<T>(path: string): Promise<T> {
  return api.getJson<T>(`/api/crm/inbox-workspaces${path}`);
}

/** A conversation field, as the inbox list's columns, sort and filters name them. */
export type ConversationField =
  | "customer" | "inbox" | "channel" | "assignee" | "status" | "priority" | "subject"
  | "lastMessageAt" | "lastMessagePreview" | "unreadCount" | "code" | "description" | (string & {});

/**
 * How the conversation list presents: the same options as an entity's list customization. The
 * inbox itself is the list's custom view; `view` picks which one it opens on.
 */
export type CrmInboxListOptions = Omit<ListCustomization, "custom" | "columns" | "sort" | "groupBy" | "labels" | "filters"> & {
  columns?: ConversationField[];
  labels?: Partial<Record<ConversationField, string>>;
  /** `-` prefix for descending. Default: the latest message first. */
  sort?: ConversationField | `-${ConversationField}`;
  groupBy?: ConversationField;
  filters?: ListFilter<ConversationField>[];
  /** Open on the inbox (default) or the table. */
  view?: "inbox" | "table";
  /** The toggle's label for the inbox view (default "Inbox"). */
  inboxLabel?: string;
};

/** Where the inbox's own toolbar controls go and which ones it offers. */
export type CrmInboxToolbar = {
  /** "renderer" moves the list's controls into the inbox's conversation pane. */
  placement?: "header" | "renderer";
  /** Persistent channel/account navigation beside the inbox where there is room. */
  filterSidebar?: boolean;
  /** On narrow screens, gather the controls into a sheet instead of a row. */
  mobile?: "inline" | "sheet";
  /** Which of the list's controls show (default all): `search`, `filters`, `views`, `workspaces`, `extensions`, … */
  controls?: string[];
  /** Channel and account choices above the conversations. */
  channels?: boolean;
  accounts?: boolean;
  views?: ListView[];
  filters?: string[];
  actions?: string[];
  title?: boolean;
  count?: boolean;
  viewSwitcher?: boolean;
  search?: boolean;
};

/** Toolbar arrangements of the inbox's panes, per screen size (ids as the panes name their items). */
export type CrmInboxLayouts = Omit<InboxChrome, "conversationRow">;

export type CrmInboxProps = CrmPresentation & {
  /**
   * The `CrmInboxWorkspace` bean, by key. Omitted: the one holding `conversation`, else the first
   * the user may use.
   */
  workspace?: string;
  /** Other workspaces offered as a switch above the list: readings of the same conversations. */
  modes?: { workspace: string; icon?: CrmIcon }[];
  /** Open this conversation (a record link), its contact's chats in the feed. */
  conversation?: string;
  /** Heading of the conversation pane. */
  title?: string;
  list?: CrmInboxListOptions;
  toolbar?: CrmInboxToolbar;
  /** The row anatomy: account line, channel badge, preview lines, and badges for field values. */
  row?: ConversationRowOptions;
  layouts?: CrmInboxLayouts;
  /** Offer the Folders/Everything switch where the workspace has folders (default true). */
  grouping?: boolean;
  /** The app route of the CRM connections page, linked from the toolbar. */
  connectionsRoute?: string;
  /** Labels of the channel keys in the channel choices (default: the key, capitalized). */
  channelLabels?: Record<string, string>;
  /** Components that draw some messages their own way. */
  messageRenderers?: ChatMessageRenderer[];
};

function channelLabel(key: string, labels?: Record<string, string>): string {
  return labels?.[key] ?? (key ? key.charAt(0).toUpperCase() + key.slice(1).toLowerCase().replace(/[_-]+/g, " ") : key);
}

/**
 * The team inbox of a `CrmInboxWorkspace`: its conversations, folders, chat and composer, with the
 * contact panel beside. The Java workspace bean decides which conversations it holds and who may
 * use it; these props decide how it presents.
 *
 *   <CrmInbox workspace="support" list={{ columns: ["customer", "subject", "lastMessageAt"] }} />
 */
export function CrmInbox({ workspace: configuredKey = "", modes = [], conversation: configuredConversation = "", title,
  list: listOptions, toolbar: toolbarProps = {}, row, layouts, grouping = true, connectionsRoute, channelLabels,
  messageRenderers, contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields,
  showSystemEvents, showTimestamps, showDeliveryStatus }: CrmInboxProps) {
  // One presentation object per change of its parts, so the body's resolved config stays put.
  const look = useMemo<CrmPresentation>(() => ({ contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars,
    showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus }),
    [contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents,
      showTimestamps, showDeliveryStatus]);
  const modeKeys = modes.map(entry => entry.workspace).filter(Boolean);
  const route = new URLSearchParams(window.location.search);
  // A conversation the inbox was asked to open that this feed has not loaded (a mailbox search hit,
  // say) narrows the feed to that contact, like a `?conversation=` link; "All chats" widens it again.
  // `scope` undefined follows the route, null drops it, an id replaces it.
  const [scope, setScope] = useState<string | null | undefined>(undefined);
  const [focus, setFocus] = useState<ConversationFocus | null>(null);
  const conversationId = configuredConversation || (scope === undefined ? route.get("conversation") || "" : scope || "");
  // "?mode=<workspace>&folder=<key>" — another surface (an event page, a report) can hand the inbox
  // the folding and the folder it wants opened, instead of dropping the reader at the root list.
  const routedMode = route.get("mode") || "";
  const routedFolder = route.get("folder") || "";
  const showToolbarControl = (key:string) => !toolbarProps.controls || toolbarProps.controls.includes(key);
  const showChannelFilters = !!toolbarProps.channels;
  const showAccountFilter = toolbarProps.accounts ?? showChannelFilters;
  const [mode, setMode] = useState(
    [routedMode, configuredKey].find(key => key && modeKeys.includes(key)) || modeKeys[0] || ""
  );
  const [modeLabels, setModeLabels] = useState<Record<string, string>>({});
  // How this inbox is being read, as plain strings a toolbar contribution may set as well.
  const [view, setView] = useState<Record<string, string | undefined>>({});
  const viewState = useMemo(() => ({
    get: (key: string) => view[key],
    set: (key: string, value: string | undefined) => setView(current => ({ ...current, [key]: value })),
  }), [view]);
  const flat = view.grouping === "flat";
  const [channel, setChannel] = useState("");
  const [account, setAccount] = useState("");
  const conversationPath = conversationId ? `/conversation/${encodeURIComponent(conversationId)}` : "";
  // The feed opens sorted as the list will ask for it, so its bootstrap rows seed the list.
  const sort = listOptions?.sort;
  const sortQuery = useMemo(() => {
    const query = new URLSearchParams();
    if (sort) { query.set("sort", sort.replace(/^-/, "")); query.set("dir", sort.startsWith("-") ? "desc" : "asc"); }
    return query.toString();
  }, [sort]);
  const [feed,setFeed] = useState<WorkspaceFeed | null>(null);
  // The conversation path the feed above was read for: its rows bootstrap only that list.
  const [feedPath,setFeedPath] = useState("");
  const [error,setError] = useState("");
  const [refreshKey,setRefreshKey] = useState(0);
  useEffect(() => {
    let live=true;
    setError("");
    // Choices are independent of the configured workspace. Only fallback selection waits for them.
    const choices = workspaceRead<WorkspaceSummary[]>(!configuredKey && !mode && conversationId ? `?conversation=${encodeURIComponent(conversationId)}` : "");
    void choices.then(items => {
      if(live)setModeLabels(current => ({...current, ...Object.fromEntries(items.map(item=>[item.key,item.label]))}));
    }).catch(() => {});
    const preferred = mode || configuredKey;
    const read = (key: string) => workspaceRead<WorkspaceFeed>(`/${encodeURIComponent(key)}${conversationPath}${sortQuery ? `?${sortQuery}` : ""}`);
    void (async () => {
      if (preferred) {
        try { return await read(preferred); }
        catch (failure) {
          // Keep the previous fallback for a mode that is no longer accessible.
          const items = await choices;
          const fallback = items.find(item => item.key === configuredKey && item.key !== preferred) || items.find(item => item.key !== preferred);
          if (!fallback) throw failure;
          return read(fallback.key);
        }
      }
      const items = await choices;
      if (!items.length) throw new Error(t("crm.error.noWorkspaces"));
      return read(items[0].key);
    })().then(result => {if(live){setFeed(result);setFeedPath(conversationPath);}}).catch(e=>{if(live)setError(e.message);});
    return ()=>{live=false;};
    // refreshKey: an inbox-page invalidation re-reads the workspace, not just its rows — folders are
    // computed from application data (stage, event membership) and move when that data moves.
  },[configuredKey,conversationPath,mode,refreshKey,sortQuery]);
  useUiEvents(()=>setRefreshKey(value=>value+1),{types:["updated"],entityType:"page",entityName:"crm-inbox-workspaces"});
  // One component identity for the life of the inbox. A new function type here would remount the
  // inbox on every feed refresh — reading a message refreshes the workspace, and the open folder,
  // the selected chat and the draft would all reset. The body reads the current feed from a ref;
  // the parent's own re-render is what carries a changed feed into it.
  const feedRef = useRef(feed);
  feedRef.current = feed;
  // Honour the routed folder only while the feed it belongs to is the one on screen.
  const folderRef = useRef(routedFolder);
  folderRef.current = feed && routedMode && feed.key !== routedMode ? "" : routedFolder;
  const chromeRef = useRef<InboxChrome>({});
  chromeRef.current = { conversationRow: row, ...layouts };
  const flatRef = useRef(flat);
  flatRef.current = flat;
  const focusRef = useRef(focus);
  focusRef.current = focus;
  const scopedRef = useRef(false);
  scopedRef.current = !!conversationId && !configuredConversation;
  const conversationScope = useMemo(() => ({
    open: (id: string) => { setChannel(""); setAccount(""); setScope(id); setFocus({ id, nonce: Date.now() }); },
    handled: () => setFocus(null),
    showAll: () => { setScope(null); setFocus(null); },
  }), []);
  // The inbox draws its own "loading more" row inside the conversation list.
  const renderer = useMemo<ListRendererComponent>(() => Object.assign(function WorkspaceBody(props: ListRendererProps) {
    const current = feedRef.current;
    return current ? <InboxBody {...props} open={()=>{}} scopedConfig={current.config} workspaceKey={current.key}
      initialFolder={folderRef.current} flat={flatRef.current} chrome={chromeRef.current}
      focusConversation={focusRef.current} onFocusHandled={conversationScope.handled} onOpenElsewhere={conversationScope.open}
      scoped={scopedRef.current} onShowAll={conversationScope.showAll} /> : null;
  }, { ownsLoadingMore: true }),[]);
  const list = useMemo(() => {
    if (!feed) return null;
    const { view: openOn, inboxLabel, ...options } = listOptions ?? {};
    const customized = applyListOptions(feed.list, options as Parameters<typeof applyListOptions>[1]);
    return {
      ...customized,
      title: title ?? (options.title || t("crm.inbox.title")),
      custom: { component: renderer, label: inboxLabel ?? t("crm.inbox.title"), defaultView: openOn !== "table" },
      filters: showChannelFilters ? (customized.filters ?? []).filter(filter => !["channel", "inbox"].includes(filter.key)) : customized.filters,
      kind: "catalogs", name: "crm_conversations", embedded: true, fill: true, canWrite: false, newUrl: null,
      feed: `/api/crm/inbox-workspaces/${encodeURIComponent(feed.key)}${conversationPath}`,
    } as ListDescriptor;
  }, [feed, listOptions, title, renderer, conversationPath, showChannelFilters]);
  const initialPage = useMemo(() => {
    // While a narrowed (or widened) feed is loading, the old rows must not seed the new list.
    if (!feed || !list || feedPath !== conversationPath) return undefined;
    return {feed:(list as {feed:string}).feed, query:sortQuery, rows:feed.rows, total:feed.total,
      nextCursor:feed.nextCursor ?? null, hasMore:feed.hasMore};
  }, [feed, list, feedPath, conversationPath, sortQuery]);
  const queryParams = useMemo(() => ({eq: [channel && `channel,${channel}`, showAccountFilter && account && `inbox,${account}`].filter(Boolean) as string[]}), [channel,account,showAccountFilter]);
  const accounts = feed?.channelAccounts ?? [];
  const channels = [...new Set(accounts.map(item => item.channel).filter(Boolean))].map(value => ({ value, label: channelLabel(value, channelLabels) }));
  const modeControls = modes.length > 1 ? <div className="flex flex-wrap items-center gap-1" aria-label={t("crm.inbox.view")}>
    {modes.map(entry => { const Icon = entry.icon; return <Button key={entry.workspace} size="toolbar" variant={feed?.key === entry.workspace ? "secondary" : "ghost"}
      aria-pressed={feed?.key === entry.workspace} onClick={() => setMode(entry.workspace)}>
      {Icon ? <Icon size={14} /> : null}{modeLabels[entry.workspace] || entry.workspace}
    </Button>; })}
  </div> : undefined;
  const flatChannelControls = <InboxChannelNavigation channels={channels} accounts={accounts}
    channel={channel} account={account} showChannels={showChannelFilters} showAccounts={showAccountFilter}
    onChannel={setChannel} onAccount={setAccount} />;
  const connectionsLink = connectionsRoute?.startsWith("/") && !connectionsRoute.startsWith("//")
    ? <Button type="button" variant="ghost" onClick={() => openRoute(`onno://${connectionsRoute.slice(1)}`)}
        className="min-h-10 w-full justify-start gap-2.5 rounded-field px-3 text-sm font-normal text-muted-foreground hover:bg-muted hover:text-foreground"><Settings2 className="size-4" />{t("crm.inbox.manageConnections")}</Button> : undefined;
  const channelControls = showChannelFilters || showAccountFilter ? <div className="crm-channel-bar flex min-w-0 items-center gap-2" aria-label={t("crm.inbox.channelsAndAccounts")}>
    {showChannelFilters && <><div className="crm-channel-buttons flex min-w-0 items-center gap-1 overflow-x-auto" aria-label={t("crm.chat.channel")}>
      <Button size="toolbar" variant={!channel ? "secondary" : "ghost"} aria-pressed={!channel} onClick={() => {setChannel(""); setAccount("");}}>{t("crm.inbox.allChannels")}</Button>
      {channels.map(item => <Button key={item.value} size="toolbar" variant={channel === item.value ? "secondary" : "ghost"} aria-pressed={channel === item.value} onClick={() => {setChannel(item.value); setAccount("");}}><ChannelLogo channel={item.value} className="size-4" />{item.label}</Button>)}
    </div>
    <div className="crm-channel-select min-w-0 flex-1">
      <Select value={channel || "all"} onValueChange={(value: string) => { setChannel(value === "all" ? "" : value); setAccount(""); }}>
        <SelectTrigger aria-label={t("crm.chat.channel")} className="w-full min-w-0"><SelectValue>{channel ? channels.find(item => item.value === channel)?.label || channel : t("crm.inbox.allChannels")}</SelectValue></SelectTrigger>
        <SelectContent><SelectItem value="all">{t("crm.inbox.allChannels")}</SelectItem>{channels.map(item => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}</SelectContent>
      </Select>
    </div>
    </> }
    {showAccountFilter && <Select value={account || "all"} onValueChange={(value: string) => setAccount(value === "all" ? "" : value)}>
      <SelectTrigger aria-label={t("crm.inbox.account")} className="crm-account-select ml-auto w-52 min-w-0"><SelectValue>{account ? accounts.find(item => item.id === account)?.label || account : t("crm.inbox.allAccounts")}</SelectValue></SelectTrigger>
      <SelectContent><SelectItem value="all">{t("crm.inbox.allAccounts")}</SelectItem>{accounts.filter(item => !channel || item.channel === channel).map(item => <SelectItem key={item.id} value={item.id}>{item.label}</SelectItem>)}</SelectContent>
    </Select>}
  </div> : undefined;
  if(error)return <p role="alert">{error}</p>;
  // Folders or everything: a reading preference, not a filter — "Everything" drops the grouping
  // without dropping a single chat. Only offered where there is folding to drop. The toolbar is
  // also an outlet: the app's `crm.inbox.toolbar` contributions get the same view state.
  const toolbar = <div className="flex flex-wrap items-center gap-1">
    {grouping && (feed?.config.folders.length ?? 0) > 0 && <div className="flex flex-wrap items-center gap-1" aria-label={t("crm.inbox.grouping")}>
      <Button size="toolbar" variant={!flat ? "secondary" : "ghost"} aria-pressed={!flat} onClick={() => viewState.set("grouping", "folders")}>{t("crm.inbox.folders")}</Button>
      <Button size="toolbar" variant={flat ? "secondary" : "ghost"} aria-pressed={flat} onClick={() => viewState.set("grouping", "flat")}>{t("crm.inbox.everything")}</Button>
    </div>}
    <ExtensionSlot name="crm.inbox.toolbar" className="flex flex-wrap items-center gap-1"
      context={{ surface: "crm-inbox", workspaceKey: feed?.key, route: route.get("mode") || "", permissions: {},
        record: { folders: feed?.config.folders.length ?? 0 },
        view: viewState, refresh: () => setRefreshKey(key => key + 1) }} />
  </div>;
  return list ? <CrmPresentationProvider value={look}><ChatMessageRenderers renderers={messageRenderers}>
  <div data-toolbar-placement={toolbarProps.placement} className="crm-inbox-page flex h-full min-h-0 min-w-0 flex-col gap-3">
    <style>{`
      .crm-inbox-page { container-type:inline-size; }
      [data-inbox-channel-navigation] button[aria-pressed="true"] { background:hsl(var(--primary) / 0.12); color:hsl(var(--primary)); font-weight:600; }
      @media (hover:hover) and (pointer:fine) {
        [data-filter-sidebar] [data-inbox-channel-navigation] fieldset > div > * + * { margin-top:2px; }
        [data-filter-sidebar] [data-inbox-channel-navigation] > * + * { margin-top:12px; }
      }
      .crm-channel-select { display:none; }
      .crm-inbox-page[data-toolbar-placement="renderer"] .crm-channel-buttons { display:none; }
      .crm-inbox-page[data-toolbar-placement="renderer"] .crm-channel-select { display:block; }
      .crm-inbox-page[data-toolbar-placement="renderer"] .crm-account-select { width:45%; }
      .crm-channel-buttons > button { flex-shrink:0; }
      [data-compact-filter-sheet] .crm-channel-bar { flex-direction:column; align-items:stretch; gap:12px; }
      [data-compact-filter-sheet] .crm-channel-buttons { display:none; }
      [data-compact-filter-sheet] .crm-channel-select { display:block; flex:none; }
      [data-compact-filter-sheet] .crm-account-select { margin:0; width:100%; }
      @container (max-width:720px) {
        .crm-channel-buttons { display:none; }
        .crm-channel-select { display:block; }
        .crm-account-select { width:45%; }
      }
    `}</style>
    <EntityListView key={`${feed?.key}${conversationPath}`} initialPage={initialPage} list={list} renderer={renderer} toolbarPlacement={toolbarProps.placement === "renderer" ? "renderer" : "header"} refreshKey={refreshKey} headerExtra={<>{showToolbarControl("workspaces") && modeControls}{showToolbarControl("extensions") && toolbar}</>} toolbarContent={toolbarProps.filterSidebar || toolbarProps.mobile === "sheet" ? flatChannelControls : channelControls}
      toolbarFooter={connectionsLink}
      toolbarOptions={{filterSidebar:!!toolbarProps.filterSidebar, compactPresentation:toolbarProps.mobile === "sheet" ? "sheet" : "inline", controls:toolbarProps.controls,
        views:toolbarProps.views, filterKeys:toolbarProps.filters, actionKeys:toolbarProps.actions, title:toolbarProps.title !== false, count:toolbarProps.count !== false,
        viewSwitcher:toolbarProps.viewSwitcher !== false, search:toolbarProps.search !== false}} queryParams={queryParams} />
  </div></ChatMessageRenderers></CrmPresentationProvider> : <InboxSkeleton />;
}
