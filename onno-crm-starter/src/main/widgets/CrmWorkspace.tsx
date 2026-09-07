import { ContactStageLabel, useContactStages } from "./ContactStage";
import { ExtensionSlot } from "@onno/widget-sdk";
import { RefreshCw, Pause, Play, KeyRound, Loader2 } from "lucide-react";
import { ChannelLogo } from "./ChannelLogo";
import { Label, Button, Input, Badge, toast, registerWidget, useCallback, useEffect, useState, useUiEvents, type EntityRecord, type WidgetProps } from "@onno/widget-sdk";

export type DisplayField = { key: string; label: string; section: string; format: string; visible: boolean; editable: boolean };
type CustomField = { key: string; label: string; type: string; options: string[]; required: boolean };
export type ConversationFolder = { key: string; label: string; conversationIds: string[]; channelIds: string[]; statusIds: string[]; priorityIds: string[]; channels: string[]; statuses: string[]; priorities: string[]; unreadOnly: boolean };
export type Config = { folders: ConversationFolder[]; fields: DisplayField[]; customFields: CustomField[]; actions: { key: string; label: string; visible: boolean }[]; listTitle: string; listSubtitle: string; listPreview: string; showAvatar: boolean; showIdentities: boolean; showEmpty: boolean; showSystemEvents: boolean; showTimestamps: boolean; showDeliveryStatus: boolean };
type Workspace = { version: number; config: Config; availableFields: DisplayField[] };
type Contact = { duplicates: Record<string, unknown>[]; fields: Record<string, unknown>; customValues: Record<string, unknown>; revision: string; identities: { id: string; channel: string; address: string; externalId: string; verified: boolean }[]; conversations: { id: string; subject: string; channel: string }[] };
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
  const [channels, setChannels] = useState<ChannelConnection[]>([]);
  const [canManage, setCanManage] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [editing, setEditing] = useState("");
  const [credential, setCredential] = useState("");
  const load = useCallback(async () => {
    const data = await request<{channels: ChannelConnection[]; canManage: boolean}>("/channels");
    setChannels(data.channels);setCanManage(data.canManage);
  }, []);
  useEffect(() => {void load().catch(e => setError(e.message));}, [load]);
  useEffect(() => {
    const url = new URL(window.location.href);
    const result = url.searchParams.get("gmail");
    if (!result) return;
    if (result === "connected") toast.success("Gmail connected. Inbox synchronization will start shortly.");
    else toast.error("Gmail sign-in was not completed. Try connecting again and allow both requested permissions.");
    url.searchParams.delete("gmail"); window.history.replaceState(window.history.state, "", url);
  }, []);
  const command = async (channel: ChannelConnection, action: string) => {
    setBusy(channel.key);setError("");
    try {
      if (action === "connect" && channel.key === "gmail") {
        const result = await request<{url: string}>("/gmail/authorize", {});
        window.location.assign(result.url); return;
      }
      await request(`/channels/${channel.key}`, {action, ...(action === "reconnect" ? {credential} : {})});
      setCredential("");setEditing("");await load();
      toast.success(action === "check" ? `${channel.label} connection verified.` : action === "reconnect" ? `${channel.label} credentials updated.` : `${channel.label} ${action === "pause" ? "paused" : "resumed"}.`);
    } catch(e) {setError((e as Error).message);setCredential("");} finally {setBusy("");}
  };
  return <div className="space-y-4">
    {!canManage && channels.length > 0 && <p className="text-sm text-muted-foreground">Sign in as a CRM manager to manage connections.</p>}
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    {!channels.length && !error && <p className="text-sm text-muted-foreground">Loading connections…</p>}
    <div className="grid gap-4 md:grid-cols-2">{channels.map(channel => <section key={channel.key} className="space-y-4 rounded-panel border border-border bg-card p-5">
      <div className="flex items-center gap-3"><ChannelLogo channel={channel.key} className="size-10" /><div className="min-w-0 flex-1"><h3 className="text-sm font-semibold">{channel.label}</h3>{channel.account && <p className="truncate text-xs text-muted-foreground">{channel.account}</p>}</div><Badge variant={channel.state === "CONNECTED" ? "secondary" : "outline"}>{({CONNECTED:"Connected",DISCONNECTED:"Not connected",PAUSED:"Paused",ERROR:"Needs attention",UNAVAILABLE:"Setup required"} as Record<string,string>)[channel.state] || channel.state}</Badge></div>
      <p className="text-sm text-muted-foreground">{channel.description}</p>
      {channel.state === "UNAVAILABLE" && <p className="text-xs text-muted-foreground">Sign-in becomes available when your administrator enables this integration.</p>}
      <div className="flex flex-wrap gap-2">{channel.actions.map(a => <Button key={a} size="toolbar" variant="subtle" disabled={!canManage || !!busy} onClick={() => a === "reconnect" ? (setEditing(channel.key),setCredential("")) : void command(channel,a)}>{busy === channel.key ? <Loader2 className="animate-spin" /> : a === "check" ? <RefreshCw /> : a === "pause" ? <Pause /> : a === "resume" ? <Play /> : <KeyRound />}{busy === channel.key ? "Working…" : ({connect:"Connect Google account",check:"Check connection",pause:"Pause",resume:"Resume",reconnect:"Reconnect bot"} as Record<string,string>)[a] || a}</Button>)}</div>
      {editing === channel.key && <form className="space-y-3 border-t border-border pt-4" onSubmit={(e: {preventDefault:()=>void}) => {e.preventDefault();void command(channel,"reconnect");}}>
        <Label className="block space-y-2 text-xs"><span>Bot token</span><Input aria-label="Telegram bot token" type="password" autoComplete="new-password" value={credential} onChange={(e: {target:{value:string}}) => setCredential(e.target.value)} /></Label>
        <p className="text-xs text-muted-foreground">Use a token for {channel.account}. The token is verified and stored on the server; existing conversations stay connected to this bot.</p>
        <div className="flex gap-2"><Button type="submit" disabled={!!busy || !credential.trim()}>Verify and reconnect</Button><Button type="button" variant="outline" disabled={!!busy} onClick={() => {setCredential("");setEditing("");}}>Cancel</Button></div>
      </form>}
    </section>)}</div>
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
  const { stages, error: stageError } = useContactStages();
  const [contact, setContact] = useState<Contact | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(async () => { const c = await request<Contact>(`/contacts/${id}`); setContact(c); }, [id]);
  useEffect(() => { let alive = true; setContact(null); setError(""); request<Contact>(`/contacts/${id}`).then(c => { if (alive) setContact(c); }).catch(e => { if (alive) setError(e.message); }); return () => { alive = false; }; }, [id]);
  useUiEvents(() => { void load().catch(e => setError(e.message)); }, { types: ["updated"], entityType: "catalog", entityName: "CrmCustomers", id });
  useUiEvents(() => { void load().catch(e => setError(e.message)); }, { types: ["updated"], entityType: "tag", id });
  if (!contact) return <p className="p-4 text-xs">{error || "Loading contact…"}</p>;
  const currentId = String(contact.fields.id);
  const shown = config.fields.filter(f => f.visible && f.key !== "customer.tags");
  const getValue = (f: DisplayField) => { const [source, key] = f.key.split("."); return source === "custom" ? contact.customValues[key] : source === "conversation" ? row[`${key}Display`] ?? row[key] : customer?.[`${key}Display`] ?? contact.fields[key]; };
  const getColor = (f: DisplayField): string | undefined => {
    const [source, key] = f.key.split(".");
    const record = source === "conversation" ? row : customer ?? contact.fields;
    const color = record[`${key}Color`] ?? record[`${key}_color`];
    return typeof color === "string" ? color : undefined;
  };
  return <div className="space-y-4 p-4 text-xs">
    {stageError && <p role="alert" className="text-destructive">{stageError}</p>}
    {config.showAvatar && <div className="flex justify-center">{typeof contact.fields.avatarUrl === "string" && /^(https?:\/\/|\/api\/)/.test(contact.fields.avatarUrl) ? <img alt="Contact profile" src={contact.fields.avatarUrl} className="size-14 rounded-full object-cover" /> : <span className="flex size-14 items-center justify-center rounded-full bg-muted text-lg">{String(contact.fields.description || "?").slice(0, 2).toUpperCase()}</span>}</div>}
    {[...new Set(shown.map(f => f.section))].map(section => <section key={section} className="space-y-2 border-b border-border pb-3"><h3 className="font-semibold text-muted-foreground">{section}</h3>{shown.filter(f => f.key !== "customer.tags" && f.section === section && (config.showEmpty || (getValue(f) != null && getValue(f) !== ""))).map(f => <div key={f.key} className="flex flex-wrap justify-between gap-2"><span className="text-muted-foreground">{f.label}</span><span className="break-all text-right"><>{f.key === "customer.stage" ? <ContactStageLabel stage={stages.find(stage => String(stage.id) === String(contact.fields.stage))} fallback={String(customer?.stage_display ?? customer?.stageDisplay ?? "—")} /> : <Display value={getValue(f)} format={f.format} color={getColor(f)} />}</></span></div>)}</section>)}
    {config.showIdentities && <section className="space-y-2"><h3 className="font-semibold">Linked identities</h3>{contact.identities.length ? contact.identities.map(i => <div key={i.id}><span className="text-muted-foreground">{i.channel} · </span>{i.address || i.externalId}<span className="block text-[10px] text-muted-foreground">{i.verified ? "Provider linked" : "Manually entered · unverified"}</span></div>) : <p className="text-muted-foreground">No channel identities linked yet</p>}</section>}
    <ExtensionSlot name="crm.contact.actions" context={{
      surface: "crm-contact", kind: "catalogs", name: "crm_customers", recordId: currentId, record: contact.fields,
      permissions: { canWrite: !!action(config, "edit") },
    }} />
    {error && <p role="alert" className="text-destructive">{error}</p>}
  </div>;
}
function ContactDetails({ widget }: WidgetProps) { const { workspace, error } = useWorkspace(); return widget.record && workspace ? <ContactPanel id={widget.record.id} customer={widget.record.data} config={workspace.config} /> : <p>{error || "Open a saved customer to view contact details."}</p>; }
registerWidget("crmSettings", CrmSettings);
registerWidget("crmContactDetails", ContactDetails);
