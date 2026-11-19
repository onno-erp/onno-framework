import { InboxChannelNavigation } from "./InboxChannelNavigation";
import { InboxSkeleton, ConversationListSkeleton, type ConversationRowOptions, conversationCount, ConversationList, type ConversationPagination } from "./ConversationList";
import { ScheduleCall } from "./ScheduleCall";
import { LogActivity } from "./LogActivity";
import { ExtensionSlot, openRoute, type ExtensionContext } from "@onno/ui";
import { ChatGroupActions, NewChatGroup } from "./ChatGroupActions";
import { composerDrafts, hasDraftText } from "./composerDrafts";
import { MailboxSearch } from "./MailboxSearch";
import { mailboxAccounts, type MailboxAccount } from "./mailboxApi";
import { useChatGroups } from "./chatGroups";
import { contactChats, replySelection, type ReplySelection } from "./contactChats";
import { useInboxEscape } from "./useInboxEscape";
import { useConversationRead } from "./useConversationRead";
import { ComposerWindowHost, useComposerWindows, type ComposerWindow } from "./composerWindows";
import { text as hostText, useLocale, useTranslate, useMobileDetail, usePageSlide, WorkspaceToolbar, type WorkspaceToolbarLayout } from "@onno/ui";

/**
 * Every label in this widget resolves through the app's chrome strings, so the inbox reads in the
 * deployment's language instead of shipping hardcoded English. `t` is the non-hook accessor, usable
 * in handlers and thrown errors; the root component calls {@link useTranslate} once so the whole
 * tree re-renders when the server's `onno.ui.messages` overlay arrives.
 */
const t = hostText;
import { createContext, useContext, useId, type ComponentType } from "react";
import { ChannelLogo } from "./ChannelLogo";
import { ContactPanel, useWorkspace, action, resolveConfig, useCrmPresentation, CrmPresentationProvider, type ConversationFolder, type CrmIcon, type CrmPresentation, type ServerConfig, type Config } from "./CrmWorkspace";
import { ChatMessageRenderers, type ChatMessageRenderer } from "./chatMessageRenderers";
import { EntityListView, Button, Select, SelectTrigger, SelectValue, SelectContent, SelectItem, api, useUiEvents, applyListOptions, type EntityRecord, type ListRendererProps, type ListRendererComponent, type ListCustomization, type ListDescriptor, type ListFilter, type ListView } from "@onno/ui";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Settings2, ContactRound, Folder, ChevronRight, ArrowLeft, X, MoreHorizontal, MailSearch } from "@onno/ui/icons";
import { string, workspaceCommand } from "./crmModel";
import { ChatTimeline, CrmAvatar, type ChatTimelineProps } from "./ChatTimeline";
import { ConversationRow, type ConversationRowComponent, type ConversationRowMenu } from "./ConversationRow";
import { CrmComposer, FileDropOverlay, type CrmComposerProps } from "./CrmComposer";
import { useConversationExtensionContext } from "./CrmConversation";
import { useCrmComposer } from "./useCrmComposer";
import { useCrmConversation, useTimelineWithSources, type CrmTimelineSource } from "./useCrmConversation";

// One surface treatment for the header, composer, and contact-details island.
const islandSurface = "rounded-panel bg-card border border-border/60";


/** The app route this inbox is on, without the SPA's mount prefix, for an `onno://` hand-off. */
function currentRoute(): string {
  const base = (window as { __onnoBasePath?: string }).__onnoBasePath;
  let path = window.location.pathname;
  const prefix = base && base !== "__ONNO_BASE_PATH__" ? base.replace(/\/+$/, "") : "";
  if (prefix && path.startsWith(`${prefix}/`)) path = path.slice(prefix.length);
  return path.replace(/^\/+/, "");
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

/** The contact panel beside the chat, the built-in one or an app's own (`components.ContactPanel`). */
export type InboxContactPanelProps = {
  customerId: string; conversation: EntityRecord; customer: EntityRecord | null; config: Config; extensionContext: ExtensionContext;
};
function DefaultInboxContact({ customerId, conversation, customer, config }: InboxContactPanelProps) {
  return <ContactPanel presentation="card" id={customerId} row={conversation} customer={customer} config={config} />;
}

/** The parts of the inbox an app may draw itself. Each gets the same state the built-in one does. */
export type CrmInboxComponents = {
  /** One conversation in the list. Wrap the exported `ConversationRow` to keep its menu and selection. */
  ConversationRow?: ConversationRowComponent;
  /**
   * The composer under the chat (the exported `CrmComposer` and its parts). It also draws the windows of chats
   * not on screen, with `windowOnly` set: pass the props through to `CrmComposer`.
   */
  Composer?: ComponentType<CrmComposerProps>;
  /** The contact panel beside the chat. */
  ContactPanel?: ComponentType<InboxContactPanelProps>;
  /** What the chat pane shows while no conversation is chosen. */
  EmptyChat?: ComponentType;
};
type InboxExtrasValue = { components?: CrmInboxComponents; timelineSources?: readonly CrmTimelineSource[];
  renderEntry?: ChatTimelineProps["renderEntry"]; rowMenu?: ConversationRowMenu };
/** The inbox's app-supplied parts, handed to the list renderer without remounting it. */
const InboxExtras = createContext<InboxExtrasValue>({});

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
  const extras = useContext(InboxExtras);
  const selectionDismissedRef = useRef(false);
  const inboxRootRef = useRef<HTMLDivElement | null>(null);
  const selectionRef = useRef(selectedId);
  selectionRef.current = selectedId;
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
  const timelineEndRef = useRef<HTMLDivElement | null>(null);
  // Respond to the allocated pane, including a narrow split pane on a wide screen.
  useEffect(() => {
    const root = inboxRootRef.current;
    if (!root) return;
    const measure = () => {
      setCompact(root.clientWidth < 720);
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

  // The open chat: its timeline, contact, delivery and commands (useCrmConversation), and its
  // composer (useCrmComposer). The inbox follows the contact's latest channel as messages arrive,
  // unless the reader is writing or opened this conversation on purpose.
  const hasDraftRef = useRef(false);
  const channelRowsRef = useRef(channelRows);
  channelRowsRef.current = channelRows;
  const followActivity = useCallback((entries: readonly { id: string; conversationId: string; direction: string }[], conversationId: string) => {
    const customerId = String(channelRowsRef.current.find(row => String(row.id) === conversationId)?.customer ?? "");
    const next = replySelection(customerId, [...entries], replySelectionRef.current, hasDraftRef.current || pinnedRef.current === conversationId);
    replySelectionRef.current = next.state;
    if (next.conversationId && channelRowsRef.current.some(row => String(row.id) === next.conversationId)) setSelectedId(next.conversationId);
  }, []);
  const chat = useCrmConversation({ conversation: selected, workspace: workspaceKey, onActivity: followActivity });
  const composer = useCrmComposer(chat, config);
  hasDraftRef.current = !!(composer.draft || composer.noteDraft).trim() || composer.subjectDraft !== null
    || (composer.recipientDraft !== null && composer.recipientDraft !== chat.delivery?.recipient);
  const extensionContext = useConversationExtensionContext(chat, composer, config);
  const timeline = useTimelineWithSources(chat, extras.timelineSources);
  const customer = chat.customer;
  const customerAvatarUrl = string(customer, "avatarUrl", string(selected, "customerAvatar"));

  useEffect(() => {
    const history = timelineEndRef.current?.parentElement;
    if (history) history.scrollTop = history.scrollHeight;
  }, [selectedId, timeline.length, mobileChatOpen]);

  useEffect(() => {
    if (selectionDismissedRef.current && selectedId === null) return;
    if (selectedId && channelRows.some((row) => String(row.id) === selectedId)) return;
    setSelectedId(rows.length ? String(rows[0].id) : null);
  }, [rows, channelRows, selectedId]);

  const acknowledgeRead = useCallback(() => Promise.all(channelRows.filter(row =>
    String(row.customer) === String(selected?.customer) && Number(row.unreadCount || 0) > 0 && readRows.get(String(row.id)) !== row
  ).map(async row => {
    await workspaceCommand(workspaceKey, `/api/crm/conversations/${row.id}/read`);
    setReadRows(previous => new Map(previous).set(String(row.id), row));
  })), [channelRows, selected?.customer, workspaceKey, readRows]);
  const readRevision = selected && chat.activityLoaded
    ? JSON.stringify([workspaceKey, selectedId, selected.unreadCount,
      chat.messages.filter(message => message.direction === "INBOUND").map(message => message.id)])
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
    setSelectedId(String(target.id));
  };
  /** Opens a conversation by id: in place when this feed has it, otherwise through the workspace. */
  const openConversation = (conversationId: string) => {
    setMailboxOpen(false);
    const row = channelRows.find(item => String(item.id) === conversationId);
    if (row) { select(row, true); return; }
    if (onOpenElsewhere) { onOpenElsewhere(conversationId); return; }
    openRoute(`onno://${currentRoute()}?conversation=${encodeURIComponent(conversationId)}`);
  };

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

  const windowHost = useMemo(() => ({ workspace: workspaceKey ?? "" }), [workspaceKey]);
  if (!config) return configError ? <p role="alert" className="p-4 text-sm">{configError}</p> : <InboxSkeleton />;

  if (!loading && !toolbar && !rows.length && !folders.length) {
    return (
      <div className="rounded-panel border border-dashed border-border px-6 py-16 text-center">
        <div className="text-sm font-medium text-foreground">{t("crm.inbox.noMatches")}</div>
        <div className="mt-1 text-xs text-muted-foreground">{t("crm.inbox.noMatchesHint")}</div>
      </div>
    );
  }

  // The contact's conversations a reply can switch to (another channel or mailbox thread).
  const senderOptions = channelRows.filter(row => String(row.id) === selectedId || (!!selected?.customer && String(row.customer) === String(selected.customer)));
  const switchConversation = (id: string) => { const target = channelRows.find(row => String(row.id) === id); if (target) setSelectedId(String(target.id)); };
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
  const Row = extras.components?.ConversationRow ?? ConversationRow;
  const Composer = extras.components?.Composer ?? CrmComposer;
  const Contact = extras.components?.ContactPanel ?? DefaultInboxContact;
  const EmptyChat = extras.components?.EmptyChat;
  const insertInvitation = (text: string) => { composer.setMode("reply"); composer.setDraft(previous => previous ? `${previous}\n\n${text}` : text); };
  const rowFor = (row: EntityRecord) => <Row key={String(row.id)} appearance={chrome?.conversationRow} menu={extras.rowMenu} row={row} config={config} workspaceKey={workspaceKey}
    groups={chatGroups.groups} groupKey={chatGroups.groups.find(group => group.customerIds.includes(String(row.customer)))?.key}
    onGroupChange={chatGroups.ready ? chatGroups.change : undefined}
    selected={(!compact || mobileChatOpen) && String(row.customer || row.id) === String(selected?.customer || selectedId)} onSelect={() => select(row)} />;

  return (
    <ComposerWindowHost.Provider value={windowHost}>
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
          /* It takes the timestamp's place on the title line (as in a mail list), so the two never overlap. */
          [data-row-menu-host] > [data-row-menu-trigger] { position:absolute; top:50%; right:8px; transform:translateY(-50%); z-index:1; opacity:0; transition:opacity 120ms ease; }
          [data-row-menu-host]:has([data-row-preview]) > [data-row-menu-trigger] { top:calc(50% - 11px); }
          [data-row-menu-host]:hover > [data-row-menu-trigger], [data-row-menu-host]:focus-within > [data-row-menu-trigger], [data-row-menu-trigger][aria-expanded="true"] { opacity:1; }
          [data-row-menu-host] [data-row-timestamp] { transition:opacity 120ms ease; }
          [data-row-menu-host]:is(:hover, :focus-within) [data-row-timestamp], [data-row-menu-host]:has([data-row-menu-trigger][aria-expanded="true"]) [data-row-timestamp] { opacity:0; }
          @media (hover:none) {
            [data-row-menu-host] { padding-right:34px; }
            [data-row-menu-host] > [data-row-menu-trigger], [data-row-menu-host]:has([data-row-preview]) > [data-row-menu-trigger] { top:50%; right:2px; opacity:1; }
            [data-row-menu-host]:is(:hover, :focus-within) [data-row-timestamp] { opacity:1; }
          }
          @media (prefers-reduced-motion: reduce) { [data-row-menu-trigger], [data-row-timestamp] { transition:none; } }
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
                  if (!folder) return rowFor(row);
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
              {displayedFolder && detailRows.map(rowFor)}
              {displayedFolder && !detailRows.length && <p className="px-3 py-8 text-center text-xs text-muted-foreground">{t("crm.inbox.emptyFolder")}</p>}
            </ConversationList>
          </div>
        </div>
      </section>

      {selected ? (
        <main {...composer.dropZone} data-file-drop-zone data-slide-page="chat" className="relative flex min-h-0 min-w-0 flex-col overflow-hidden border border-border/60" aria-hidden={compact && (!mobileChatOpen || contactPanelOpen)} inert={(compact && !mobileChatOpen) || (contactPanelOpen && detailOverlay)}>
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
                {id:"scheduleCall",content:action(config, "logActivity") && <ScheduleCall customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={chat.reload} onInvitation={insertInvitation} />},
                {id:"logActivity",content:action(config, "logActivity") && <LogActivity customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={chat.reload} />},
                {id:"extensions",content:<ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-wrap items-center gap-2" />},
                {id:"title",content:<div className="min-w-0 truncate text-sm font-semibold text-foreground">{string(selected, "customerDisplay", "Customer")}</div>},
                {id:"avatar",content:!compact && <CrmAvatar name={string(selected, "customerDisplay", "Customer")} url={customerAvatarUrl} className="size-9" />},
                {id:"contact",content:<Button ref={contactPanelToggleRef} variant={compact ? "ghost" : "subtle"} size="toolbar" className={compact ? "size-11 shrink-0 rounded-full p-1" : "w-8 shrink-0 p-0"} aria-label={contactPanelOpen ? t("crm.contact.hide") : t("crm.contact.show")} title={t("crm.contact.details")} aria-expanded={contactPanelOpen} aria-controls={contactPanelId} onClick={() => setContactPanelOpen(value => !value)}>{compact ? <CrmAvatar name={string(selected, "customerDisplay", "Customer")} url={customerAvatarUrl} className="size-9" /> : <ContactRound className="size-4" />}</Button>},
              ]} />
          </header>
          {actionsOpen && <div id={actionsId} className="flex shrink-0 flex-wrap items-center gap-2 border-b border-border/50 px-4 py-2">
            {action(config, "edit") && !!selected.customerRef && <Button size="toolbar" variant="subtle" onClick={() => {
              const ref = selected.customerRef as {type:string;id:string};
              openRoute(`onno://catalogs/${encodeURIComponent(ref.type)}/${encodeURIComponent(ref.id)}`);
            }}>{t("crm.contact.open")}</Button>}
            {action(config, "logActivity") && <ScheduleCall key={`call:${selected.customer}`} customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={chat.reload} onInvitation={insertInvitation} />}
            {action(config, "logActivity") && <LogActivity key={String(selected.customer)} customerId={String(selected.customer)} conversationId={String(selected.id)} onSaved={chat.reload} />}
            <ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-wrap items-center gap-2" />
          </div>}
          <div data-chat-history className="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-4 py-5">
            {chat.hasMoreHistory && <Button size="sm" variant="ghost" onClick={chat.loadEarlier}>{t("crm.chat.loadEarlier")}</Button>}
            <ExtensionSlot name="crm.chat.timeline.start" context={extensionContext} className="space-y-3" />
            <ChatTimeline entries={timeline} config={config} customerAvatarUrl={customerAvatarUrl}
              busy={composer.busy || !chat.delivery?.connected} onRetry={message => void composer.retry(message)}
              renderEntry={extras.renderEntry} messageContext={extensionContext} />
            {!timeline.length && !chat.error ? (
              <div className="py-16 text-center text-xs text-muted-foreground">{t("crm.chat.noMessages")}</div>
            ) : null}
            <div ref={timelineEndRef} />
          </div>
          <Composer conversation={chat} composer={composer} config={config} compact={compact}
            layouts={{ composer: chrome?.composer, mobileComposer: chrome?.mobileComposer }}
            extensionContext={extensionContext} siblings={senderOptions} onSwitch={switchConversation} contactName={contactName} />
          <FileDropOverlay composer={composer} />
        </main>
      ) : EmptyChat ? <main className="flex min-h-0 min-w-0 flex-col"><EmptyChat /></main>
        : <main className="flex items-center justify-center p-6 text-sm text-muted-foreground">{t("crm.chat.chooseConversation")}</main>}

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
            <Contact key={String(selected.customer)} customerId={String(selected.customer)} conversation={selected} customer={customer} config={config} extensionContext={extensionContext} />
            <ExtensionSlot name="crm.chat.aside" context={extensionContext} className="space-y-3 p-4" />
          </div>
        </aside> : null}
      </div>
      {mailboxOpen && mailboxes.length > 0 && <MailboxSearch open accounts={mailboxes} onOpenChange={setMailboxOpen} onOpenConversation={openConversation} />}
      {config && <ComposerWindows workspace={windowHost.workspace} shown={selected && chat.id ? composerDrafts.key(chat.workspace, chat.id) : null}
        config={config} Composer={Composer} />}
    </div>
    </ComposerWindowHost.Provider>
  );
}

/**
 * The composer windows of the conversations the inbox is not showing. Each keeps writing to its own chat (its own
 * conversation and composer state; the draft is shared through `composerDrafts`), drawn by the inbox's `Composer`
 * so an app's own composer applies to these windows too. The shown chat's window is drawn by its composer.
 */
function ComposerWindows({ workspace, shown, config, Composer }: { workspace: string; shown: string | null; config: Config; Composer: ComponentType<CrmComposerProps> }) {
  const windows = useComposerWindows();
  return <>{windows.list(workspace).filter(window => window.key !== shown).map(window =>
    <ParkedComposer key={window.key} window={window} config={config} Composer={Composer} />)}</>;
}

function ParkedComposer({ window, config, Composer }: { window: ComposerWindow; config: Config; Composer: ComponentType<CrmComposerProps> }) {
  const chat = useCrmConversation({ conversation: window.conversation, workspace: window.workspace });
  const composer = useCrmComposer(chat, config);
  const extensionContext = useConversationExtensionContext(chat, composer, config);
  return <Composer conversation={chat} composer={composer} config={config} extensionContext={extensionContext} contactName={window.contactName} windowOnly />;
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
  /** The parts of the inbox the app draws itself (the row, the composer, the contact panel, …). */
  components?: CrmInboxComponents;
  /**
   * The app's own entries in the chat's timeline (orders, bookings, payments, a widget at a date).
   * Keep the array stable (module-level): each source's `useEntries` is a hook.
   */
  timelineSources?: readonly CrmTimelineSource[];
  /** Draws one timeline entry your own way, or wraps the default (`render()`). */
  renderEntry?: ChatTimelineProps["renderEntry"];
  /** What the row menu offers besides the conversation's commands and the app's items. */
  rowMenu?: ConversationRowMenu;
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
  showSystemEvents, showTimestamps, showDeliveryStatus, components, timelineSources, renderEntry, rowMenu }: CrmInboxProps) {
  const extras = useMemo<InboxExtrasValue>(() => ({ components, timelineSources, renderEntry, rowMenu }), [components, timelineSources, renderEntry, rowMenu]);
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
  return list ? <CrmPresentationProvider value={look}><ChatMessageRenderers renderers={messageRenderers}><InboxExtras.Provider value={extras}>
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
  </div></InboxExtras.Provider></ChatMessageRenderers></CrmPresentationProvider> : <InboxSkeleton />;
}
