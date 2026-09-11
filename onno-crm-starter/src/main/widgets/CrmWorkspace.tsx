import { text as t, useTranslate } from "@onno/widget-sdk";
import type { ReactNode } from "react";
import { ContactAvatarImage } from "./ContactAvatarImage";
import { ExtensionSlot } from "@onno/widget-sdk";
import { RefreshCw, Pause, Play, KeyRound, Loader2, Copy, Check } from "lucide-react";
import { ChannelLogo } from "./ChannelLogo";
import { Label, Button, Input, Badge, toast, registerWidget, useCallback, useEffect, useState, useUiEvents, type EntityRecord, type WidgetProps } from "@onno/widget-sdk";

export type DisplayField = { key: string; label: string; section: string; format: string; visible: boolean; editable: boolean };
export type ConversationFolder = { matchNone?: boolean; key: string; label: string; conversationIds: string[]; channelIds: string[]; statusIds: string[]; priorityIds: string[]; channels: string[]; statuses: string[]; priorities: string[]; unreadOnly: boolean };
export type Config = { folders: ConversationFolder[]; fields: DisplayField[]; actions: { key: string; label: string; visible: boolean }[]; listTitle: string; listSubtitle: string; listPreview: string; showAvatar: boolean; showIdentities: boolean; showEmpty: boolean; showSystemEvents: boolean; showTimestamps: boolean; showDeliveryStatus: boolean };
type Workspace = { version: number; config: Config; availableFields: DisplayField[] };
type Contact = { catalogName: string; canWrite: boolean; fields: Record<string, unknown>; identities: { id: string; channel: string; address: string; externalId: string; verified: boolean }[]; conversations: { id: string; subject: string; channel: string }[] };
export async function request<T>(path: string, body?: unknown): Promise<T> {
  const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
  const response = await fetch(`/api/crm${path}`, { credentials: "same-origin", ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) }, body: JSON.stringify(body) }) });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || error.detail || `Request failed (${response.status})`); }
  return response.json();
}
export function useWorkspace() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [canConfigure, setCanConfigure] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(() => request<{ workspace: Workspace; canConfigure: boolean }>("/workspace").then(r => { setWorkspace(r.workspace); setCanConfigure(r.canConfigure); setError(""); }).catch(e => setError(e.message)), []);
  useEffect(() => { void load(); }, [load]);
  useUiEvents(() => { void load(); }, { types: ["updated"], entityName: "CrmConversations", entityType: "catalog" });
  return { workspace, canConfigure, error, reload: load };
}
export function action(config: Config | undefined, key: string) { return config?.actions.find(a => a.key === key && a.visible); }
export function rowValue(row: EntityRecord, key: string) { const field = key.replace(/^conversation\./, ""); return String(row[`${field}Display`] ?? row[field] ?? ""); }
type ChannelConnection = { key: string; label: string; channel: string; state: string; account: string; description: string; actions: string[] };
function CrmSettings() {
  const [channels,setChannels]=useState<ChannelConnection[]>([]);
  const [canManage,setCanManage]=useState(false);
  const [error,setError]=useState("");
  const load=useCallback(async()=>{
    const result=await request<{channels:ChannelConnection[];canManage:boolean}>("/channels");
    setChannels(result.channels);setCanManage(result.canManage);
  },[]);
  useEffect(()=>{void load().catch(e=>setError(e.message));},[load]);
  return <div className="space-y-4">
    {error && <p role="alert">{error}</p>}
    {!channels.length && <p>{t("crm.settings.noConnectors")}</p>}
    {channels.map(channel=><section key={channel.key} className="space-y-3 rounded-panel border border-border p-4">
      <h3>{channel.label}</h3><p>{channel.account}</p><p>{channel.description}</p>
      <Badge>{channel.state}</Badge>
      <ExtensionSlot name="crm.channel.settings" context={{surface:"crm-channel",record:channel,
        permissions:{canManage},refresh:load}} />
    </section>)}
  </div>;
}

function Display({ value, format, color }: { value: unknown; format: string; color?: string }) {
  if (value == null || value === "") return <span className="text-muted-foreground/70">—</span>;
  const text = String(value);
  if (format === "badge") return <StageBadge text={text} color={color} />;
  if (format === "boolean") return <>{value === true || text === "true" ? "Yes" : "No"}</>;
  if (format === "number" && Number.isFinite(Number(text))) return <span className="tabular-nums">{Number(text).toLocaleString()}</span>;
  if (format === "date" && !Number.isNaN(Date.parse(text))) return <span className="tabular-nums">{new Date(text).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}</span>;
  const href = format === "email" ? `mailto:${text}` : format === "phone" ? `tel:${text}` : format === "url" && /^https?:\/\//.test(text) ? text : null;
  return href ? <a className="text-primary underline decoration-primary/40 underline-offset-2 hover:decoration-primary" href={href} target={format === "url" ? "_blank" : undefined} rel="noreferrer">{text}</a> : <>{text}</>;
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
export function ContactPanel({ id, row = {}, config, customer = null, inboxRoute }: { inboxRoute?: string; id: string; row?: EntityRecord; config: Config; customer?: EntityRecord | null }) {
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
  return <div className="p-4 text-xs">
    <header className="flex items-center gap-3 pb-3">
      {config.showAvatar && <span className="relative flex size-11 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted text-sm font-medium text-muted-foreground">
        {name.slice(0, 2).toUpperCase()}
        <ContactAvatarImage name={name} url={typeof contact.fields.avatarUrl === "string" ? contact.fields.avatarUrl : null} />
      </span>}
      <div className="min-w-0 flex-1">
        <h2 className="truncate text-sm font-semibold leading-5 text-foreground" title={name}>{name}</h2>
        {primary ? <span className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <ChannelLogo channel={primary.channel} className="size-3.5" />
          <span className="truncate">{primary.address || primary.externalId}</span>
        </span> : null}
      </div>
    </header>
    {chips.length > 0 && <div className="flex flex-wrap gap-1.5 pb-3">
      {chips.map(f => <StageBadge key={f.key} text={String(value(f))} color={color(f)} />)}
    </div>}
    {sections.map(group => <section key={group.section} className="border-t border-border/60 py-3">
      <h3 className="pb-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/80">{group.section}</h3>
      <dl>{group.fields.map(f => {
        const text = value(f) == null ? "" : String(value(f));
        const copyable = (f.format === "email" || f.format === "phone") && !!text;
        return <div key={f.key} className="group">
          <Row label={f.label} format={f.format}>
            <span className={`inline-flex max-w-full items-center gap-1 ${f.format === "email" || f.format === "url" ? "" : "justify-end"}`}>
              <span className="min-w-0"><Display value={value(f)} format={f.format} color={color(f)} /></span>
              {copyable && <CopyButton value={text} label={f.label} />}
            </span>
          </Row>
        </div>;
      })}</dl>
    </section>)}
    {config.showIdentities && <section className="border-t border-border/60 py-3">
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
    {inboxRoute && /^\/[a-zA-Z0-9_/-]+$/.test(inboxRoute) && contact.conversations.length > 0 && <Button variant="outline" size="sm" className="mt-3 w-full" onClick={() => window.dispatchEvent(new CustomEvent("onno:action", {detail:`onno://${inboxRoute.slice(1)}?conversation=${encodeURIComponent(contact.conversations[0].id)}`}))}>{t("crm.contact.openConversation")}</Button>}
    <ExtensionSlot name="crm.contact.actions" className="mt-3 space-y-2" context={{
      surface: "crm-contact", kind: "catalogs", name: contact.catalogName, recordId: currentId, record: contact.fields,
      permissions: { canWrite: contact.canWrite },
    }} />
    {error && <p role="alert" className="mt-3 text-destructive">{error}</p>}
  </div>;
}
function ContactDetails({ widget }: WidgetProps) { const { workspace, error } = useWorkspace(); return widget.record && workspace ? <ContactPanel id={widget.record.id} inboxRoute={widget.extraConfig?.inboxRoute} customer={widget.record.data} config={workspace.config} /> : <p>{error || "Open a saved customer to view contact details."}</p>; }
registerWidget("crmSettings", CrmSettings);
registerWidget("crmContactDetails", ContactDetails);
