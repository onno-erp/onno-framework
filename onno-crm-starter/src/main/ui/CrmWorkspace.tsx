import { api, locale, text as t, useTranslate } from "@onno/ui";
import { createContext, useContext, type ComponentType, type ReactNode } from "react";
import { ContactAvatarImage } from "./ContactAvatarImage";
import { ExtensionSlot, openRoute } from "@onno/ui";
import { RefreshCw, Pause, Play, KeyRound, Loader2, Copy, Check } from "@onno/ui/icons";
import { ChannelLogo } from "./ChannelLogo";
import { LinkedText } from "./LinkedText";
import { Label, Button, Input, Badge, toast, useUiEvents, type EntityRecord, type RecordContext } from "@onno/ui";
import { useCallback, useEffect, useState } from "react";

/** How a contact field's value is drawn; `auto` reads it (an address, a link, a number, else text). */
export type CrmFieldFormat = "auto" | "text" | "badge" | "email" | "phone" | "url" | "date" | "number" | "boolean";
/**
 * One field of the contact panel, by key: `customer.<field>` for a field the host's
 * `CrmCatalogBinding` exposes, `conversation.<field>` for one of the open conversation's.
 */
export type CrmContactField = { key: string; label?: string; section?: string; format?: CrmFieldFormat };
/** The conversation actions a workspace may enable (Java `CrmWorkspaceService.ACTIONS`). */
export type CrmAction = "logActivity" | "edit" | "details" | "reply" | "note" | "sendReply" | "sendNote" | "retry";
/** An icon component, e.g. one of lucide-react's. */
export type CrmIcon = ComponentType<{ className?: string; size?: number }>;
/** How a workspace folder presents, by folder key: its glyph and the hue drawn behind it. */
export type CrmFolderPresentation = { label?: string; icon?: CrmIcon; color?: string };

/** How the inbox and contact panel present. Everything is optional; the defaults show everything. */
export type CrmPresentation = {
  /** The contact panel's fields, in order. Default: every field the host's binding exposes. */
  contactFields?: (string | CrmContactField)[];
  /** Button labels for the workspace's actions (default: the `crm.action.*` messages). */
  actionLabels?: Partial<Record<CrmAction, string>>;
  /** Presentation of the workspace's folders, by folder key. */
  folders?: Record<string, CrmFolderPresentation>;
  /** The conversation field a row's title and preview line show. Default: the customer, the last message. */
  rowTitle?: string;
  rowPreview?: string;
  showAvatars?: boolean;
  /** The contact's linked channel identities in the contact panel. */
  showIdentities?: boolean;
  /** Contact fields without a value (shown as "—"). Default false. */
  showEmptyFields?: boolean;
  showSystemEvents?: boolean;
  showTimestamps?: boolean;
  showDeliveryStatus?: boolean;
};

/** A folder as the server sends it: membership rules only. */
export type ServerFolder = { matchNone?: boolean; key: string; label: string; conversationIds: string[]; channelIds: string[]; statusIds: string[]; priorityIds: string[]; channels: string[]; statuses: string[]; priorities: string[]; unreadOnly: boolean };
/** A workspace's behaviour as the server sends it: the actions it enables and its folders. */
export type ServerConfig = { actions: string[]; folders: ServerFolder[] };

export type DisplayField = { key: string; label: string; section: string; format: CrmFieldFormat; visible: boolean };
export type ConversationFolder = ServerFolder & { icon?: CrmIcon; color?: string };
/** The server's behaviour with the app's presentation applied: what the inbox renders from. */
export type Config = { folders: ConversationFolder[]; fields: DisplayField[]; actions: { key: string; label: string; visible: boolean }[]; listTitle: string; listPreview: string; showAvatar: boolean; showIdentities: boolean; showEmpty: boolean; showSystemEvents: boolean; showTimestamps: boolean; showDeliveryStatus: boolean };
type Workspace = { version: number; config: ServerConfig; fields: string[] };
type Contact = { catalogName: string; canWrite: boolean; fields: Record<string, unknown>; identities: { id: string; channel: string; address: string; externalId: string; verified: boolean }[]; conversations: { id: string; subject: string; channel: string }[] };

const ACTIONS: CrmAction[] = ["logActivity", "edit", "details", "reply", "note", "sendReply", "sendNote", "retry"];

function humanize(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The label of a contact field key: `customer.description` is the contact's name. */
function fieldLabel(key: string): string {
  const [, field = key] = key.split(".");
  return field === "description" ? t("crm.contact.name") : humanize(field);
}

/** Apply the app's presentation to a workspace's server configuration and bound contact fields. */
export function resolveConfig(server: ServerConfig, boundFields: readonly string[], p: CrmPresentation = {}): Config {
  const fields: DisplayField[] = (p.contactFields ?? boundFields).map(entry => {
    const f = typeof entry === "string" ? { key: entry } : entry;
    return { key: f.key, label: f.label ?? fieldLabel(f.key), section: f.section ?? t("crm.contact.section"), format: f.format ?? "auto", visible: true };
  });
  const enabled = new Set(server.actions);
  return {
    fields,
    actions: ACTIONS.map(key => ({ key, label: p.actionLabels?.[key] ?? t(`crm.action.${key}`), visible: enabled.has(key) })),
    folders: server.folders.map(folder => {
      const look = p.folders?.[folder.key];
      return { ...folder, label: look?.label ?? folder.label, icon: look?.icon, color: look?.color };
    }),
    listTitle: `conversation.${p.rowTitle ?? "customer"}`,
    listPreview: `conversation.${p.rowPreview ?? "lastMessagePreview"}`,
    showAvatar: p.showAvatars ?? true,
    showIdentities: p.showIdentities ?? true,
    showEmpty: p.showEmptyFields ?? false,
    showSystemEvents: p.showSystemEvents ?? true,
    showTimestamps: p.showTimestamps ?? true,
    showDeliveryStatus: p.showDeliveryStatus ?? true,
  };
}

const Presentation = createContext<CrmPresentation>({});
/** Hands the app's presentation to the inbox and contact panels below. */
export function CrmPresentationProvider({ value, children }: { value: CrmPresentation; children: ReactNode }) {
  return <Presentation.Provider value={value}>{children}</Presentation.Provider>;
}
export function useCrmPresentation(): CrmPresentation {
  return useContext(Presentation);
}

export async function request<T>(path: string, body?: unknown): Promise<T> {
  if (body === undefined) return api.getJson<T>(`/api/crm${path}`);
  const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
  const response = await fetch(`/api/crm${path}`, { credentials: "same-origin", ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) }, body: JSON.stringify(body) }) });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || error.detail || `Request failed (${response.status})`); }
  return response.json();
}
export function useWorkspace() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(() => request<{ workspace: Workspace }>("/workspace").then(r => { setWorkspace(r.workspace); setError(""); }).catch(e => setError(e.message)), []);
  useEffect(() => { void load(); }, [load]);
  useUiEvents(() => { void load(); }, { types: ["updated"], entityName: "CrmConversations", entityType: "catalog" });
  return { workspace, error, reload: load };
}
export function action(config: Config | undefined, key: string) { return config?.actions.find(a => a.key === key && a.visible); }
export function rowValue(row: EntityRecord, key: string) { const field = key.replace(/^conversation\./, ""); return String(row[`${field}Display`] ?? row[field] ?? ""); }

/** What an `auto` field is, read from its value. */
export function inferFormat(value: unknown): Exclude<CrmFieldFormat, "auto"> {
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "number") return "number";
  const text = value == null ? "" : String(value).trim();
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) return "email";
  if (/^https?:\/\/\S+$/i.test(text)) return "url";
  if (/^\+?[\d\s().-]{6,}$/.test(text) && /\d{3}/.test(text.replace(/\D/g, ""))) return "phone";
  return "text";
}

function Display({ value, format: declared, color }: { value: unknown; format: CrmFieldFormat; color?: string }) {
  if (value == null || value === "") return <span className="text-muted-foreground/70">—</span>;
  const text = String(value);
  const format = declared === "auto" ? inferFormat(value) : declared;
  if (format === "badge") return <StageBadge text={text} color={color} />;
  if (format === "boolean") return <>{value === true || text === "true" ? "Yes" : "No"}</>;
  if (format === "number" && Number.isFinite(Number(text))) return <span className="tabular-nums">{Number(text).toLocaleString()}</span>;
  if (format === "date" && !Number.isNaN(Date.parse(text))) return <span className="tabular-nums">{new Date(text).toLocaleDateString(locale(), { day: "numeric", month: "short", year: "numeric" })}</span>;
  const href = format === "email" ? `mailto:${text}` : format === "phone" ? `tel:${text}` : format === "url" && /^https?:\/\//.test(text) ? text : null;
  return href ? <a className="text-primary underline decoration-primary/40 underline-offset-2 hover:decoration-primary" href={href} target={format === "url" ? "_blank" : undefined} rel="noreferrer">{text}</a> : <LinkedText text={text} className="text-primary underline decoration-primary/40 underline-offset-2 hover:decoration-primary" />;
}

/**
 * A badge tinted by the value's own colour. The catalog already gives every enumeration a colour —
 * a stage, a priority, a qualification — and honouring it here is what lets the reader find the one
 * field that matters without reading the labels beside it.
 */
function StageBadge({ text, color }: { text: string; color?: string }) {
  return <Badge variant="secondary" color={color} className="max-w-full truncate px-2 py-0.5 text-[11px] font-medium"
    style={color ? { color, backgroundColor: `${color}1f`, borderColor: `${color}59` } : undefined}>{text}</Badge>;
}

/**
 * A label/value row. Most values sit in their own column so they line up down the panel; an address
 * or a link gets the full width under its label instead, because it has no spaces to wrap on and a
 * half-column would break it across lines mid-word.
 */
function Row({ label, format, children }: { label: string; format: string; children: ReactNode }) {
  const unbroken = format === "email" || format === "url";
  if (unbroken) return <div className="py-[3px]">
    <dt className="truncate text-[11px] leading-5 text-muted-foreground" title={label}>{label}</dt>
    <dd className="min-w-0 break-all text-[12px] leading-5 text-foreground">{children}</dd>
  </div>;
  return <div className="grid grid-cols-[minmax(0,6rem)_minmax(0,1fr)] items-baseline gap-x-3 gap-y-0.5 py-[3px]">
    <dt className="truncate text-[11px] leading-5 text-muted-foreground" title={label}>{label}</dt>
    <dd className="min-w-0 break-words text-right text-[12px] leading-5 text-foreground">{children}</dd>
  </div>;
}

/** Copy a value to the clipboard, with the result said back in the button itself. */
function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => { if (!copied) return; const timer = setTimeout(() => setCopied(false), 1600); return () => clearTimeout(timer); }, [copied]);
  return <Button size="sm" variant="ghost" aria-label={copied ? `${label} copied` : `Copy ${label}`} title={copied ? "Copied" : `Copy ${label}`}
    className="size-6 shrink-0 p-0 text-muted-foreground opacity-0 transition-opacity hover:text-foreground focus-visible:opacity-100 group-hover:opacity-100"
    onClick={() => { void navigator.clipboard?.writeText(value).then(() => setCopied(true)).catch(() => {}); }}>
    {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
  </Button>;
}
export function ContactPanel({ id, row = {}, config, customer = null, inboxRoute, presentation = "sidebar" }: { presentation?: "sidebar" | "card"; inboxRoute?: string; id: string; row?: EntityRecord; config: Config; customer?: EntityRecord | null }) {
  const card = presentation === "card";
  const [contact, setContact] = useState<Contact | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(async () => { const c = await request<Contact>(`/contacts/${id}`); setContact(c); }, [id]);
  useEffect(() => { let alive = true; setContact(null); setError(""); request<Contact>(`/contacts/${id}`).then(c => { if (alive) setContact(c); }).catch(e => { if (alive) setError(e.message); }); return () => { alive = false; }; }, [id]);
  useUiEvents(() => { void load().catch(e => setError(e.message)); }, { types: ["updated"], entityType: "catalog", id });
  useUiEvents(() => { void load().catch(e => setError(e.message)); }, { types: ["updated"], entityType: "tag", id });
  if (!contact) return <p role={error ? "alert" : "status"} className={`p-4 text-xs ${error ? "text-destructive" : "text-muted-foreground"}`}>{error || "Loading contact…"}</p>;
  const currentId = String(contact.fields.id);
  const shown = config.fields.filter(f => f.visible);
  const value = (f: DisplayField) => { const [source, key] = f.key.split("."); return source === "conversation" ? row[`${key}Display`] ?? row[key] : customer?.[`${key}Display`] ?? contact.fields[key]; };
  const color = (f: DisplayField): string | undefined => {
    const [source, key] = f.key.split(".");
    const record = source === "conversation" ? row : customer ?? contact.fields;
    const tint = record[`${key}Color`] ?? record[`${key}_color`];
    return typeof tint === "string" ? tint : undefined;
  };
  const name = String(contact.fields.description || "Unknown");
  // Badge fields are the ones a reader scans for — stage, qualification, priority. They read as a
  // chip row under the name rather than as label/value rows buried further down the panel.
  const chips = shown.filter(f => f.format === "badge" && value(f) != null && value(f) !== "");
  const rows = shown.filter(f => !chips.includes(f));
  const sections = [...new Set(rows.map(f => f.section))]
    .map(section => ({ section, fields: rows.filter(f => f.section === section && (config.showEmpty || (value(f) != null && value(f) !== ""))) }))
    .filter(group => group.fields.length > 0);
  const primary = contact.identities.find(identity => identity.address) ?? contact.identities[0];
  return <div data-contact-card={card || undefined} className={card ? "space-y-3 p-3 text-sm" : "p-4 text-xs"}>
    <style>{`[data-contact-card] dt { font-size:12px; } [data-contact-card] dd { font-size:14px; } [data-contact-card] dl > .group { padding:6px 0; } [data-contact-card] button[title^="Copy"] { opacity:1; width:32px; height:32px; }`}</style>
    <header className={card ? "flex flex-col items-center gap-3 rounded-panel border border-border/60 bg-muted/30 px-4 py-6 text-center" : "flex items-center gap-3 pb-3"}>
      {config.showAvatar && <span className={`relative flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted text-sm font-medium text-muted-foreground ${card ? "size-16" : "size-11"}`}>
        {name.slice(0, 2).toUpperCase()}
        <ContactAvatarImage name={name} url={typeof contact.fields.avatarUrl === "string" ? contact.fields.avatarUrl : null} />
      </span>}
      <div className="min-w-0 flex-1">
        <h2 className={card ? "break-words text-lg font-semibold text-foreground" : "truncate text-sm font-semibold leading-5 text-foreground"} title={name}>{name}</h2>
        {primary ? <span className={`mt-1 flex items-center gap-1.5 text-muted-foreground ${card ? "justify-center text-sm" : "text-[11px]"}`}>
          <ChannelLogo channel={primary.channel} className="size-3.5" />
          <span className="truncate">{primary.address || primary.externalId}</span>
        </span> : null}
      </div>
    </header>
    {/* Profile-level host content, such as tags, sits right under the profile rather than below the field cards. */}
    <ExtensionSlot name="crm.contact.profile" className={card ? "px-1" : "pb-3"} context={{
      surface: "crm-contact", kind: "catalogs", name: contact.catalogName, recordId: currentId, record: contact.fields,
      permissions: { canWrite: contact.canWrite },
    }} />
    {chips.length > 0 && <div className="flex flex-wrap gap-1.5 pb-3">
      {chips.map(f => <StageBadge key={f.key} text={String(value(f))} color={color(f)} />)}
    </div>}
    {sections.map(group => <section key={group.section} className={card ? "rounded-panel border border-border/60 p-4" : "border-t border-border/60 py-3"}>
      <h3 className="pb-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/80">{group.section}</h3>
      <dl>{group.fields.map(f => {
        const text = value(f) == null ? "" : String(value(f));
        const format = f.format === "auto" ? inferFormat(value(f)) : f.format;
        const copyable = (format === "email" || format === "phone") && !!text;
        return <div key={f.key} className="group">
          <Row label={f.label} format={format}>
            <span className={`inline-flex max-w-full items-center gap-1 ${format === "email" || format === "url" ? "" : "justify-end"}`}>
              <span className="min-w-0"><Display value={value(f)} format={format} color={color(f)} /></span>
              {copyable && <CopyButton value={text} label={f.label} />}
            </span>
          </Row>
        </div>;
      })}</dl>
    </section>)}
    {config.showIdentities && <section className={card ? "rounded-panel border border-border/60 p-4" : "border-t border-border/60 py-3"}>
      <h3 className="pb-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/80">{t("crm.contact.identities")}</h3>
      {contact.identities.length ? <ul className="space-y-1.5">{contact.identities.map(i => <li key={i.id} className="group flex items-center gap-2">
        <ChannelLogo channel={i.channel} className="size-4" />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[12px] leading-4 text-foreground" title={i.address || i.externalId}>{i.address || i.externalId}</span>
          <span className="block text-[10px] leading-4 text-muted-foreground">{i.verified ? "Provider linked" : "Manually entered · unverified"}</span>
        </span>
        <CopyButton value={i.address || i.externalId} label={i.channel} />
      </li>)}</ul> : <p className="text-[12px] text-muted-foreground">{t("crm.contact.noIdentities")}</p>}
    </section>}
    {inboxRoute && /^\/[a-zA-Z0-9_/-]+$/.test(inboxRoute) && contact.conversations.length > 0 && <Button variant="outline" size="sm" className="mt-3 w-full" onClick={() => openRoute(`onno://${inboxRoute.slice(1)}?conversation=${encodeURIComponent(contact.conversations[0].id)}`)}>{t("crm.contact.openConversation")}</Button>}
    <ExtensionSlot name="crm.contact.actions" className="mt-3 space-y-2" context={{
      surface: "crm-contact", kind: "catalogs", name: contact.catalogName, recordId: currentId, record: contact.fields,
      permissions: { canWrite: contact.canWrite },
    }} />
    {error && <p role="alert" className="mt-3 text-destructive">{error}</p>}
  </div>;
}
/**
 * A customer's CRM contact details, as a record panel under the host's customer form:
 * `recordPanels: [{ component: CrmContactDetails, title: "Contact" }]`. Its presentation is the
 * same as the inbox's contact panel; wrap it to pass one: `(p) => <CrmContactDetails {...p} contactFields={…} />`.
 */
export function CrmContactDetails({ record, inboxRoute, ...presentation }: { record: RecordContext; inboxRoute?: string } & CrmPresentation) {
  const { workspace, error } = useWorkspace();
  const inherited = useCrmPresentation();
  const look = { ...inherited, ...presentation };
  return workspace
    ? <ContactPanel id={record.id} inboxRoute={inboxRoute} customer={record.data} config={resolveConfig(workspace.config, workspace.fields, look)} />
    : <p>{error || "Open a saved customer to view contact details."}</p>;
}
