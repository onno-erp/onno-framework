import { api, ExtensionSlot, openRoute, text as t, useTranslate, useUiEvents, windows, type EntityRecord, type ExtensionContext } from "@onno/ui";
import { MessageSquare } from "@onno/ui/icons";
import { useCallback, useEffect, useMemo, useRef, useState, type ComponentType, type ReactNode } from "react";
import { ChatTimeline, CrmAvatar, type ChatTimelineProps } from "./ChatTimeline";
import { CrmComposer, FileDropOverlay, type CrmComposerLayouts, type CrmComposerProps } from "./CrmComposer";
import { action, resolveConfig, useCrmPresentation, useWorkspace, type Config, type CrmPresentation, type ServerConfig } from "./CrmWorkspace";
import { ChatMessageRenderers, type ChatMessageRenderer } from "./chatMessageRenderers";
import { string, workspaceCommand } from "./crmModel";
import { useConversationRead } from "./useConversationRead";
import { useCrmComposer, type CrmComposerState } from "./useCrmComposer";
import { useCrmConversation, useTimelineWithSources, type CrmConversationState, type CrmTimelineSource } from "./useCrmConversation";

/**
 * A single conversation anywhere in the app: on a record page, in a dashboard column, in a docked
 * window that stays open while the user works elsewhere (`openCrmConversation`). It is the inbox's
 * chat without the list, built from the same parts and the same per-conversation draft.
 */

type WorkspaceFeed = { key: string; config: ServerConfig; rows: EntityRecord[] };

/**
 * One conversation of an inbox workspace, with the contact's other conversations (their channels)
 * and the workspace's resolved presentation. Kept live as rows change.
 */
export function useCrmWorkspaceConversation(workspace: string, conversationId: string, presentation?: CrmPresentation) {
  const [feed, setFeed] = useState<WorkspaceFeed | null>(null);
  const [error, setError] = useState("");
  const [selectedId, setSelectedId] = useState(conversationId);
  useEffect(() => { setSelectedId(conversationId); }, [conversationId]);
  const load = useCallback(() => api.getJson<WorkspaceFeed>(`/api/crm/inbox-workspaces/${encodeURIComponent(workspace)}/conversation/${encodeURIComponent(conversationId)}`)
    .then(next => { setFeed(next); setError(""); })
    .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : t("crm.error.conversation"))), [workspace, conversationId]);
  useEffect(() => { void load(); }, [load]);
  useUiEvents(() => { void load(); }, { types: ["created", "updated"], entityType: "catalog", entityName: "CrmConversations" });
  useUiEvents(() => { void load(); }, { types: ["updated"], entityType: "page", entityName: "crm-inbox-workspaces" });
  const { workspace: shared } = useWorkspace();
  const inherited = useCrmPresentation();
  const look = useMemo(() => ({ ...inherited, ...presentation }), [inherited, presentation]);
  const config: Config | undefined = useMemo(() => feed ? resolveConfig(feed.config, shared?.fields ?? [], look) : undefined, [feed, shared?.fields, look]);
  const conversation = feed?.rows.find(row => String(row.id) === selectedId) ?? null;
  const siblings = useMemo(() => !conversation ? [] : (feed?.rows ?? []).filter(row => String(row.id) === selectedId
    || (!!conversation.customer && String(row.customer) === String(conversation.customer))), [feed, conversation, selectedId]);
  return { conversation, siblings, config, error, loading: !feed && !error, switchTo: setSelectedId, reload: load };
}

/** The conversation's extension context, as every chat outlet gets it. */
export function useConversationExtensionContext(conversation: CrmConversationState, composer: CrmComposerState, config: Config | undefined): ExtensionContext {
  const row = conversation.conversation;
  return {
    surface: "crm-chat", kind: "catalogs", name: "crm_conversations", recordId: row ? String(row.id) : undefined,
    record: row ?? undefined, workspaceKey: conversation.workspace,
    permissions: { canWrite: !!action(config, "edit"), canReply: !!action(config, "reply") },
    actions: conversation.actions.map(item => ({ id: item.id, title: item.title, enabled: item.enabled && !composer.busy })),
    refresh: conversation.reload,
    openRecord: (kind, name, id) => openRoute(`onno://${kind}/${name}/${id}`),
    insertDraft: composer.insertDraft,
    execute: (name, inputs) => composer.busy ? Promise.reject(new Error(t("crm.error.action"))) : conversation.execute(name, inputs),
  };
}

/** What an app can swap in a conversation screen. Each part gets the same state the default uses. */
export type CrmConversationComponents = {
  /** Above the timeline: the contact's name, avatar and the conversation's actions. */
  Header?: ComponentType<CrmConversationPartProps>;
  /** The reply/note composer under the timeline. */
  Composer?: ComponentType<CrmComposerProps>;
  /** Shown in the timeline while there is nothing in it. */
  Empty?: ComponentType<CrmConversationPartProps>;
};
export type CrmConversationPartProps = {
  conversation: CrmConversationState; composer: CrmComposerState; config?: Config; extensionContext: ExtensionContext;
};

/** Props of {@link CrmConversation}. */
export type CrmConversationProps = CrmPresentation & {
  /** The inbox workspace whose rules apply (who may read, reply, which commands). */
  workspace: string;
  /** The conversation to show; the contact's other channels are offered in the composer's From. */
  conversation: string;
  components?: CrmConversationComponents;
  /** The app's own entries in the timeline (orders, bookings, …). Keep the array stable. */
  timelineSources?: readonly CrmTimelineSource[];
  renderEntry?: ChatTimelineProps["renderEntry"];
  messageRenderers?: ChatMessageRenderer[];
  layouts?: CrmComposerLayouts;
  /** Draw no header at all (a window or panel that has its own title). */
  header?: boolean;
  className?: string;
};

function DefaultHeader({ conversation, extensionContext }: CrmConversationPartProps) {
  const row = conversation.conversation;
  if (!row) return null;
  const name = string(row, "customerDisplay", t("crm.chat.unknownCustomer"));
  return <header className="flex min-h-14 shrink-0 items-center gap-3 border-b border-border px-4 py-2">
    <CrmAvatar name={name} url={string(conversation.customer, "avatarUrl", string(row, "customerAvatar"))} className="size-9" />
    <div className="min-w-0 flex-1">
      <div className="truncate text-sm font-semibold">{name}</div>
      <div className="truncate text-xs text-muted-foreground">{string(row, "subject")}</div>
    </div>
    <ExtensionSlot name="crm.chat.header" context={extensionContext} className="flex flex-wrap items-center gap-2" />
  </header>;
}

function DefaultEmpty() {
  return <div className="py-16 text-center text-xs text-muted-foreground">{t("crm.chat.noMessages")}</div>;
}

/** Live parts of one conversation: what {@link CrmConversation} renders, for an app's own layout. */
export function ConversationBody({ conversation, siblings, onSwitch, config, components = {}, timelineSources, renderEntry, layouts, header = true, className = "" }: {
  conversation: CrmConversationState; siblings?: readonly EntityRecord[]; onSwitch?: (id: string) => void; config?: Config;
} & Pick<CrmConversationProps, "components" | "timelineSources" | "renderEntry" | "layouts" | "header" | "className">) {
  const composer = useCrmComposer(conversation, config);
  const extensionContext = useConversationExtensionContext(conversation, composer, config);
  const entries = useTimelineWithSources(conversation, timelineSources);
  const endRef = useRef<HTMLDivElement | null>(null);
  const row = conversation.conversation;
  useEffect(() => {
    const history = endRef.current?.parentElement;
    if (history) history.scrollTop = history.scrollHeight;
  }, [conversation.id, entries.length]);
  // Reading the chat here marks it read, as in the inbox.
  const acknowledge = useCallback(async () => {
    if (!row || Number(row.unreadCount || 0) <= 0) return;
    await workspaceCommand(conversation.workspace, `/api/crm/conversations/${row.id}/read`);
  }, [row, conversation.workspace]);
  const readRevision = row && conversation.activityLoaded
    ? JSON.stringify([conversation.workspace, row.id, row.unreadCount, conversation.messages.filter(message => message.direction === "INBOUND").map(message => message.id)])
    : null;
  useConversationRead(endRef, readRevision, acknowledge);
  if (!row) return null;
  const Header = components.Header ?? DefaultHeader;
  const Composer = components.Composer ?? CrmComposer;
  const Empty = components.Empty ?? DefaultEmpty;
  const parts: CrmConversationPartProps = { conversation, composer, config, extensionContext };
  const contactName = string(row, "customerDisplay", t("crm.chat.unknownCustomer"));
  return <div {...composer.dropZone} data-file-drop-zone className={`relative flex h-full min-h-0 min-w-0 flex-col overflow-hidden ${className}`}>
    {header && <Header {...parts} />}
    <div data-chat-history className="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-4 py-5">
      <ExtensionSlot name="crm.chat.timeline.start" context={extensionContext} className="space-y-3" />
      <ChatTimeline entries={entries} config={config} busy={composer.busy || !conversation.delivery?.connected}
        customerAvatarUrl={string(conversation.customer, "avatarUrl", string(row, "customerAvatar"))}
        onRetry={message => void composer.retry(message)} renderEntry={renderEntry}
        messageContext={extensionContext} />
      {!entries.length && !conversation.error ? <Empty {...parts} /> : null}
      <div ref={endRef} />
    </div>
    <Composer conversation={conversation} composer={composer} config={config} layouts={layouts}
      extensionContext={extensionContext} siblings={siblings} onSwitch={onSwitch} contactName={contactName}
      windowId={`crm-composer:${conversation.workspace ?? ""}:${row.id}`} />
    <FileDropOverlay composer={composer} />
  </div>;
}

/**
 * One conversation, complete: header, timeline (with the app's entries and renderers) and composer.
 *
 *   <CrmConversation workspace="support" conversation={id} />
 */
export function CrmConversation({ workspace, conversation: conversationId, messageRenderers, ...rest }: CrmConversationProps) {
  useTranslate();
  const { contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus, ...parts } = rest;
  const look = useMemo<CrmPresentation>(() => ({ contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus }),
    [contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus]);
  const loaded = useCrmWorkspaceConversation(workspace, conversationId, look);
  const state = useCrmConversation({ conversation: loaded.conversation, workspace });
  if (loaded.error) return <p role="alert" className="p-4 text-sm text-destructive">{loaded.error}</p>;
  if (!loaded.conversation) return <p role="status" className="p-4 text-sm text-muted-foreground">{t("crm.inbox.loading")}</p>;
  return <ChatMessageRenderers renderers={messageRenderers}>
    <ConversationBody conversation={state} siblings={loaded.siblings} onSwitch={loaded.switchTo} config={loaded.config} {...parts} />
  </ChatMessageRenderers>;
}

/**
 * Opens a conversation in a docked window that stays open while the user moves around the app,
 * like a mail compose box. Calling it again for the same conversation brings that window forward.
 *
 *   openCrmConversation({ workspace: "support", conversation: id, title: "Anna Petrova" })
 */
export function openCrmConversation({ title, ...props }: CrmConversationProps & { title?: ReactNode }) {
  return windows.open(`crm-conversation:${props.workspace}:${props.conversation}`, {
    title: title ?? t("crm.chat.conversation"),
    icon: <MessageSquare className="size-4" aria-hidden="true" />,
    initialState: "docked", width: 560, height: 640, minWidth: 380, minHeight: 360,
    persist: "crm.conversation", padded: false,
    content: <CrmConversation {...props} header={props.header ?? false} />,
  });
}
