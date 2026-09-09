import { ExtensionSlot } from "@onno/widget-sdk";
import { RefreshCw, Pause, Play, KeyRound, Loader2 } from "lucide-react";
import { ChannelLogo } from "./ChannelLogo";
import { Label, Button, Input, Badge, toast, registerWidget, useCallback, useEffect, useState, useUiEvents, type EntityRecord, type WidgetProps } from "@onno/widget-sdk";

export type DisplayField = { key: string; label: string; section: string; format: string; visible: boolean; editable: boolean };
export type ConversationFolder = { key: string; label: string; conversationIds: string[]; channelIds: string[]; statusIds: string[]; priorityIds: string[]; channels: string[]; statuses: string[]; priorities: string[]; unreadOnly: boolean };
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
    {!channels.length && <p>No channel connectors are enabled.</p>}
    {channels.map(channel=><section key={channel.key} className="space-y-3 rounded-panel border border-border p-4">
      <h3>{channel.label}</h3><p>{channel.account}</p><p>{channel.description}</p>
      <Badge>{channel.state}</Badge>
      <ExtensionSlot name="crm.channel.settings" context={{surface:"crm-channel",record:channel,
        permissions:{canManage},refresh:load}} />
    </section>)}
  </div>;
}

function Display({ value, format, color }: { value: unknown; format: string; color?: string }) {
  if (value == null || value === "") return <span className="text-muted-foreground">—</span>;
  const text = String(value);
  if (format === "badge") return <Badge variant="secondary" color={color} className="px-2.5 py-0.5 text-xs">{text}</Badge>;
  if (format === "boolean") return <>{value === true || text === "true" ? "Yes" : "No"}</>;
  if (format === "number" && Number.isFinite(Number(text))) return <>{Number(text).toLocaleString()}</>;
  if (format === "date" && !Number.isNaN(Date.parse(text))) return <>{new Date(text).toLocaleDateString()}</>;
  const href = format === "email" ? `mailto:${text}` : format === "phone" ? `tel:${text}` : format === "url" && /^https?:\/\//.test(text) ? text : null;
  return href ? <a className="text-primary underline" href={href} target={format === "url" ? "_blank" : undefined} rel="noreferrer">{text}</a> : <>{text}</>;
}
export function ContactPanel({ id, row = {}, config, customer = null }: { id: string; row?: EntityRecord; config: Config; customer?: EntityRecord | null }) {
  const [contact, setContact] = useState<Contact | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(async () => { const c = await request<Contact>(`/contacts/${id}`); setContact(c); }, [id]);
  useEffect(() => { let alive = true; setContact(null); setError(""); request<Contact>(`/contacts/${id}`).then(c => { if (alive) setContact(c); }).catch(e => { if (alive) setError(e.message); }); return () => { alive = false; }; }, [id]);
  useUiEvents(() => { void load().catch(e => setError(e.message)); }, { types: ["updated"], entityType: "catalog", id });
  useUiEvents(() => { void load().catch(e => setError(e.message)); }, { types: ["updated"], entityType: "tag", id });
  if (!contact) return <p className="p-4 text-xs">{error || "Loading contact…"}</p>;
  const currentId = String(contact.fields.id);
  const shown = config.fields.filter(f => f.visible);
  const getValue = (f: DisplayField) => { const [source, key] = f.key.split("."); return source === "conversation" ? row[`${key}Display`] ?? row[key] : customer?.[`${key}Display`] ?? contact.fields[key]; };
  const getColor = (f: DisplayField): string | undefined => {
    const [source, key] = f.key.split(".");
    const record = source === "conversation" ? row : customer ?? contact.fields;
    const color = record[`${key}Color`] ?? record[`${key}_color`];
    return typeof color === "string" ? color : undefined;
  };
  return <div className="space-y-4 p-4 text-xs">
    {config.showAvatar && <div className="flex justify-center">{typeof contact.fields.avatarUrl === "string" && /^(https?:\/\/|\/api\/)/.test(contact.fields.avatarUrl) ? <img alt="Contact profile" src={contact.fields.avatarUrl} className="size-14 rounded-full object-cover" /> : <span className="flex size-14 items-center justify-center rounded-full bg-muted text-lg">{String(contact.fields.description || "?").slice(0, 2).toUpperCase()}</span>}</div>}
    {[...new Set(shown.map(f => f.section))].map(section => <section key={section} className="space-y-2 border-b border-border pb-3"><h3 className="font-semibold text-muted-foreground">{section}</h3>{shown.filter(f => f.section === section && (config.showEmpty || (getValue(f) != null && getValue(f) !== ""))).map(f => <div key={f.key} className="flex flex-wrap justify-between gap-2"><span className="text-muted-foreground">{f.label}</span><span className="break-all text-right"><><Display value={getValue(f)} format={f.format} color={getColor(f)} /></></span></div>)}</section>)}
    {config.showIdentities && <section className="space-y-2"><h3 className="font-semibold">Linked identities</h3>{contact.identities.length ? contact.identities.map(i => <div key={i.id}><span className="text-muted-foreground">{i.channel} · </span>{i.address || i.externalId}<span className="block text-[10px] text-muted-foreground">{i.verified ? "Provider linked" : "Manually entered · unverified"}</span></div>) : <p className="text-muted-foreground">No channel identities linked yet</p>}</section>}
    {action(config,"edit") && <Button variant="outline" size="sm" onClick={() => window.dispatchEvent(new CustomEvent("onno:action", {detail:`onno://catalogs/${contact.catalogName}/${currentId}`}))}>Open contact</Button>}
    <ExtensionSlot name="crm.contact.actions" context={{
      surface: "crm-contact", kind: "catalogs", name: contact.catalogName, recordId: currentId, record: contact.fields,
      permissions: { canWrite: contact.canWrite },
    }} />
    {error && <p role="alert" className="text-destructive">{error}</p>}
  </div>;
}
function ContactDetails({ widget }: WidgetProps) { const { workspace, error } = useWorkspace(); return widget.record && workspace ? <ContactPanel id={widget.record.id} customer={widget.record.data} config={workspace.config} /> : <p>{error || "Open a saved customer to view contact details."}</p>; }
registerWidget("crmSettings", CrmSettings);
registerWidget("crmContactDetails", ContactDetails);
