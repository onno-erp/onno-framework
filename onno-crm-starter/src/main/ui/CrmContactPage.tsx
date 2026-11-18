import { Button, ExtensionSlot, text as t, useTranslate, useUiEvents, type EntityRecord, type RouteProps } from "@onno/ui";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ComponentType } from "react";
import { ChannelLogo } from "./ChannelLogo";
import { ConversationBody, useCrmWorkspaceConversation, type CrmConversationProps } from "./CrmConversation";
import { ContactPanel, request, resolveConfig, useCrmPresentation, useWorkspace, type Config, type CrmPresentation } from "./CrmWorkspace";
import { ChatMessageRenderers } from "./chatMessageRenderers";
import { useCrmConversation } from "./useCrmConversation";

/** A contact as the CRM knows it: the host record's bound fields, linked identities, conversations. */
export type CrmContact = {
  catalogName: string;
  canWrite: boolean;
  fields: EntityRecord;
  identities: { id: string; channel: string; address: string; externalId: string; verified: boolean }[];
  conversations: { id: string; subject: string; channel: string }[];
};

/** One contact, loaded and kept live (its record, identities and conversations). */
export function useCrmContact(id: string | null | undefined) {
  const [contact, setContact] = useState<CrmContact | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(() => {
    if (!id) { setContact(null); return Promise.resolve(); }
    return request<CrmContact>(`/contacts/${id}`).then(next => { setContact(next); setError(""); })
      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : t("crm.error.conversation")));
  }, [id]);
  useEffect(() => { setContact(null); void load(); }, [load]);
  useUiEvents(() => { void load(); }, { types: ["updated"], entityType: "catalog", id: id ?? undefined });
  useUiEvents(() => { void load(); }, { types: ["created", "updated"], entityType: "catalog", entityName: "CrmConversations" });
  return { contact, error, reload: load };
}

/**
 * The height that takes an element from where it starts to the bottom of the window, so the page
 * itself never scrolls and only the profile and the chat scroll inside it.
 */
function useFillToBottom(ready: boolean, min = 420) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [height, setHeight] = useState<number | null>(null);
  useLayoutEffect(() => {
    const measure = () => {
      const element = ref.current;
      if (!element) return;
      // The page's scroll container ends where the tab does; the gutter is the element's own padding.
      let scroller: HTMLElement | null = element.parentElement;
      while (scroller && !/(auto|scroll)/.test(getComputedStyle(scroller).overflowY)) scroller = scroller.parentElement;
      const bottom = scroller ? scroller.getBoundingClientRect().bottom : window.innerHeight;
      setHeight(Math.max(min, Math.floor(bottom - element.getBoundingClientRect().top)));
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [ready, min]);
  return { ref, height };
}

/** Props the parts of a contact page get. */
export type CrmContactPagePartProps = {
  contact: CrmContact; customerId: string; config: Config;
  /** The conversation shown beside the profile, and how to show another. */
  selected: string | null; onSelect: (conversationId: string) => void;
};

/** What an app can swap on the contact page. */
export type CrmContactPageComponents = {
  /** The profile column (default: the contact card and the app's `crm.contact.*` outlets). */
  Profile?: ComponentType<CrmContactPagePartProps>;
  /** The list of the contact's conversations, one per channel thread. */
  Conversations?: ComponentType<CrmContactPagePartProps>;
};

export type CrmContactPageProps = CrmPresentation & Omit<CrmConversationProps, "conversation" | keyof CrmPresentation> & {
  /** The contact (the host customer record) by id. */
  contact: string;
  pageComponents?: CrmContactPageComponents;
};

function DefaultProfile({ customerId, contact, config }: CrmContactPagePartProps) {
  return <ContactPanel presentation="card" id={customerId} customer={contact.fields} config={config} />;
}

function DefaultConversations({ contact, selected, onSelect }: CrmContactPagePartProps) {
  if (!contact.conversations.length) return <p className="px-4 py-3 text-xs text-muted-foreground">{t("crm.chat.noMessages")}</p>;
  return <nav aria-label={t("crm.inbox.conversations")} className="flex flex-col gap-1 p-2">
    {contact.conversations.map(item => <Button key={item.id} type="button" variant={item.id === selected ? "secondary" : "ghost"}
      aria-current={item.id === selected ? "true" : undefined} onClick={() => onSelect(item.id)}
      className="h-auto justify-start gap-2 whitespace-normal px-3 py-2 text-left font-normal">
      <ChannelLogo channel={item.channel} className="size-4 shrink-0" />
      <span className="min-w-0 flex-1 truncate text-sm">{item.subject || item.channel}</span>
    </Button>)}
  </nav>;
}

/**
 * A contact's page: their profile and conversations on the left, the chosen conversation (with the
 * contact's whole history across channels) on the right. Register it as a page of the app:
 *
 *   { path: "/contacts/:id", component: ({ params }) => <CrmContactPage contact={params.id} workspace="support" /> }
 */
export function CrmContactPage({ contact: customerId, workspace, pageComponents = {}, messageRenderers, ...rest }: CrmContactPageProps) {
  useTranslate();
  const { contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus, ...conversationParts } = rest;
  const look = useMemo<CrmPresentation>(() => ({ contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus }),
    [contactFields, actionLabels, folders, rowTitle, rowPreview, showAvatars, showIdentities, showEmptyFields, showSystemEvents, showTimestamps, showDeliveryStatus]);
  const { contact, error } = useCrmContact(customerId);
  const { workspace: shared } = useWorkspace();
  const inherited = useCrmPresentation();
  const config = useMemo(() => shared ? resolveConfig(shared.config, shared.fields, { ...inherited, ...look }) : undefined, [shared, inherited, look]);
  const fill = useFillToBottom(!!contact && !!config);
  const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => {
    if (contact && (!selected || !contact.conversations.some(item => item.id === selected))) setSelected(contact.conversations[0]?.id ?? null);
  }, [contact, selected]);
  if (error) return <p role="alert" className="p-4 text-sm text-destructive">{error}</p>;
  if (!contact || !config) return <p role="status" className="p-4 text-sm text-muted-foreground">{t("crm.inbox.loading")}</p>;
  const Profile = pageComponents.Profile ?? DefaultProfile;
  const Conversations = pageComponents.Conversations ?? DefaultConversations;
  const parts: CrmContactPagePartProps = { contact, customerId, config, selected, onSelect: setSelected };
  return <ChatMessageRenderers renderers={messageRenderers}>
    {/* A page of its own: the same gutter as a default list page, and the full height of the tab. */}
    <div ref={fill.ref} data-crm-contact-page style={fill.height ? { height: fill.height } : undefined}
      className="grid min-h-0 w-full grid-cols-1 gap-3 p-4 sm:p-6 md:grid-cols-[minmax(280px,340px)_minmax(0,1fr)]">
      <aside className="flex min-h-0 flex-col overflow-y-auto rounded-panel border border-border/60 bg-card">
        <Profile {...parts} />
        <Conversations {...parts} />
        <ExtensionSlot name="crm.contact.page.aside" className="space-y-3 p-3"
          context={{ surface: "crm-contact", kind: "catalogs", name: contact.catalogName, recordId: customerId, record: contact.fields, permissions: { canWrite: contact.canWrite } }} />
      </aside>
      <section className="min-h-0 overflow-hidden rounded-panel border border-border/60 bg-card">
        {selected ? <ContactConversation key={selected} workspace={workspace} conversation={selected} presentation={look} {...conversationParts} />
          : <p className="p-6 text-sm text-muted-foreground">{t("crm.chat.chooseConversation")}</p>}
      </section>
    </div>
  </ChatMessageRenderers>;
}

/** The conversation column of a contact page (the conversation, without loading a list around it). */
function ContactConversation({ workspace, conversation: conversationId, presentation, ...parts }: Omit<CrmConversationProps, keyof CrmPresentation> & { presentation: CrmPresentation }) {
  const loaded = useCrmWorkspaceConversation(workspace, conversationId, presentation);
  const state = useCrmConversation({ conversation: loaded.conversation, workspace });
  if (loaded.error) return <p role="alert" className="p-4 text-sm text-destructive">{loaded.error}</p>;
  if (!loaded.conversation) return <p role="status" className="p-4 text-sm text-muted-foreground">{t("crm.inbox.loading")}</p>;
  return <ConversationBody conversation={state} siblings={loaded.siblings} onSwitch={loaded.switchTo} config={loaded.config} {...parts} />;
}

/** `CrmContactPage` as a route page: `{ path: "/contacts/:id", component: CrmContactRoute }` (workspace via props). */
export function CrmContactRoute({ params, ...props }: Pick<RouteProps, "params"> & Omit<CrmContactPageProps, "contact">) {
  return <CrmContactPage {...props} contact={String(params.id ?? "")} />;
}
