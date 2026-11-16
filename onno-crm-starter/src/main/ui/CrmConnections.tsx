import { Button, Badge, Input, Label, Segmented, useTranslate, useUiEvents, text as t } from "@onno/ui";
import { Component, useState, useEffect, useCallback, type ComponentType, type ReactNode } from "react";
import { Building2, UserRound, Plug, Plus, LockKeyhole, CircleHelp, Pencil, Check, X, AlertCircle, Trash2 } from "@onno/ui/icons";
import type { ChangeEvent } from "react";
import { request } from "./CrmWorkspace";

type Scope = "SHARED" | "PERSONAL";
type Connection = { key:string; label:string; channel:string; state:string; account:string; description:string; actions:string[] };
type Provider = {key:string; label:string; channel:string; iconUrl:string; description:string; enabled:boolean; scopes:Scope[]; setupHint:string};
type Account = {id:string; provider:string; scope:Scope; label:string; connection:Connection; canManage:boolean; canRemove?:boolean; legacy?:boolean};
type Settings = {providers:Provider[]; accounts:Account[]; canManageShared:boolean; canManagePersonal:boolean};
const prefix = "crm.connections.";

/** One connection account as a setup form sees it: its provider (`key`), state and the commands it accepts. */
export type CrmConnectionAccount = Connection & { accountId?: string; scope: Scope };
export type CrmConnectionSetupProps = { account: CrmConnectionAccount; canManage: boolean; refresh: () => Promise<void> };
/**
 * A provider's connect/reconnect form, shown on the accounts of the providers it names — e.g.
 * `@onno/crm-channels`' `channelSetups`: `<CrmConnections setups={[channelSetups]} />`.
 */
export type CrmConnectionSetup = { providers: string[]; component: ComponentType<CrmConnectionSetupProps> };

class SetupBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? <span role="status" className="text-xs text-muted-foreground">Setup unavailable</span> : this.props.children; }
}
const msg=(key:string)=>t(prefix+key);

function ProviderIcon({provider}:{provider?:Provider}) {
  return <span className="flex size-10 shrink-0 items-center justify-center rounded-field border border-border bg-background">{provider?.iconUrl ? <img src={provider.iconUrl} alt="" className="size-5 object-contain" /> : <Plug className="size-5 text-muted-foreground" />}</span>;
}
function State({value}:{value:string}) {
  return <Badge variant="secondary"><span className={`mr-1.5 inline-block size-1.5 rounded-full ${value === "CONNECTED" ? "bg-emerald-500" : value === "ERROR" ? "bg-destructive" : "bg-muted-foreground/50"}`} />{msg(`state.${value}`)}</Badge>;
}
function AccountCard({account,provider,reload,setups}:{account:Account;provider?:Provider;reload:()=>Promise<void>;setups:CrmConnectionSetup[]}) {
  const [editing,setEditing]=useState(false),[label,setLabel]=useState(account.label),[busy,setBusy]=useState(false),[error,setError]=useState("");
  const rename=async(e:{preventDefault:()=>void})=>{
    e.preventDefault();setBusy(true);setError("");
    try {await request(`/connection-accounts/${account.id}`,{action:"rename",label});await reload();setEditing(false);}catch(e){setError((e as Error).message);}finally{setBusy(false);}
  };
  const remove=async()=>{setBusy(true);setError("");try{await request(`/connection-accounts/${account.id}`,{action:"removeDraft"});await reload();}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
  return <section aria-label={account.label} className="flex min-w-0 flex-col gap-4 rounded-panel border border-border bg-card p-5">
    <div className="flex items-start gap-3"><ProviderIcon provider={provider}/><div className="min-w-0 flex-1"><h3 className="truncate text-sm font-medium">{account.label}</h3><p className="truncate text-xs text-muted-foreground">{account.connection.account || provider?.label || account.provider}</p></div><State value={account.connection.state}/></div>
    {editing ? <form onSubmit={rename} className="space-y-2"><Label>{msg("accountName")}<Input aria-label={msg("accountName")} autoFocus value={label} maxLength={120} onChange={(e:ChangeEvent<HTMLInputElement>)=>setLabel(e.target.value)}/></Label><div className="flex gap-2"><Button size="toolbar" disabled={busy || !label.trim()} type="submit"><Check/>{msg("save")}</Button><Button size="toolbar" variant="subtle" disabled={busy} onClick={()=>setEditing(false)}><X/>{msg("cancel")}</Button></div></form> : <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground"><span className="flex items-center gap-1.5">{account.scope === "PERSONAL" ? <LockKeyhole className="size-3.5"/> : <Building2 className="size-3.5"/>}{msg(account.scope === "PERSONAL" ? "ownerOnly" : "sharedAccount")}</span>{!account.legacy && account.canManage && <Button size="toolbar" variant="ghost" onClick={()=>{setLabel(account.label);setEditing(true);}}><Pencil/>{msg("rename")}</Button>}</div>}
    {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
    <div className="mt-auto flex flex-wrap items-start gap-2 border-t border-border pt-3">{setups.some(setup=>setup.providers.includes(account.provider)) && <div className="min-w-0 flex-1 basis-48">{setups.filter(setup=>setup.providers.includes(account.provider)).map((setup,index)=>{const Setup=setup.component;return <SetupBoundary key={index}><Setup account={{...account.connection,key:account.provider,...(!account.legacy?{accountId:account.id}:{}),scope:account.scope}} canManage={account.canManage} refresh={reload}/></SetupBoundary>;})}</div>}
    {account.canManage && account.canRemove && <Button size="toolbar" variant="ghost" className="ml-auto shrink-0 text-muted-foreground hover:bg-destructive/10 hover:text-destructive" title={msg("removeDraft")} aria-label={msg("removeDraft")} disabled={busy} onClick={()=>void remove()}><Trash2 className="size-4" />{msg("remove")}</Button>}
    {!account.canManage && <p className="text-xs text-muted-foreground">{msg("adminManaged")}</p>}</div>
  </section>;
}

/** The CRM's connection accounts, shared and personal. Props filter presentation, never access. */
export type CrmConnectionsProps = {
  /** Which account scopes to offer. Default both. */
  scopes?: ("shared" | "personal")[];
  /** The scope shown first. Default shared. */
  defaultScope?: "shared" | "personal";
  sharedLabel?: string;
  personalLabel?: string;
  /** Offer only these providers (keys). Default all. */
  providers?: string[];
  /** List providers that are disabled or unavailable in the scope. Default true. */
  showUnavailable?: boolean;
  /** Providers' connect/reconnect forms (e.g. `channelSetups` from `@onno/crm-channels`). */
  setups?: CrmConnectionSetup[];
};

export function CrmConnections(config: CrmConnectionsProps) {
  useTranslate();
  const scopes=((config.scopes ?? ["shared","personal"]).map(s=>s.toUpperCase()) as Scope[]);
  if(!scopes.length)scopes.push("SHARED","PERSONAL");
  const first=(config.defaultScope ?? "shared").toUpperCase() as Scope;
  const [scope,setScope]=useState<Scope>(scopes.includes(first)?first:scopes[0]);
  const [data,setData]=useState<Settings|null>(null),[error,setError]=useState(""),[refreshing,setRefreshing]=useState(false);
  const [adding,setAdding]=useState<Provider|null>(null),[name,setName]=useState(""),[saving,setSaving]=useState(false),[formError,setFormError]=useState("");
  const [help,setHelp]=useState<string|null>(null);
  const load=useCallback(async()=>{
    const [settings,legacy]=await Promise.all([request<Settings>("/connection-accounts"),request<{channels:Connection[];canManage:boolean}>("/channels")]);
    const accounts:Account[]=[...settings.accounts,...legacy.channels.filter(c=>c.account || c.state === "CONNECTED" || c.state === "PAUSED" || !settings.providers.some(p=>p.key === c.key && p.scopes.length > 0)).map(c=>({id:c.key,provider:c.key,scope:"SHARED" as Scope,label:c.label,connection:c,canManage:legacy.canManage,legacy:true}))];
    setData({...settings,accounts});setError("");
  },[]);
  useEffect(()=>{void load().catch(e=>setError(e.message));},[load]);
  useUiEvents(()=>{void load().catch(e=>setError(e.message));},{types:["updated"],entityType:"page",entityName:"crm-connections"});
  const refresh=async()=>{setRefreshing(true);try{await load();}catch(e){setError((e as Error).message);}finally{setRefreshing(false);}};
  const add=async(e:{preventDefault:()=>void})=>{
    e.preventDefault();if(!adding)return;setSaving(true);setFormError("");
    try {await request("/connection-accounts",{provider:adding.key,scope,label:name});await load();setAdding(null);}catch(e){setFormError((e as Error).message);}finally{setSaving(false);}
  };
  const sharedLabel=String(config.sharedLabel || msg("shared")),personalLabel=String(config.personalLabel || msg("personal"));
  const configuredProviders=config.providers ?? [];
  const allowed=(key:string)=>!configuredProviders.length || configuredProviders.includes(key);
  const accounts=(data?.accounts || []).filter(a=>a.scope === scope && allowed(a.provider));
  const providers=(data?.providers || []).filter(p=>allowed(p.key) && (config.showUnavailable !== false || (p.enabled && p.scopes.includes(scope))));
  const canManage=scope === "SHARED"?data?.canManageShared:data?.canManagePersonal;
  return <div className="w-full space-y-6 pb-6">
    <div className="flex flex-wrap items-center justify-between gap-3">{scopes.length>1 ? <Segmented value={scope} onChange={(v:string)=>{setScope(v as Scope);setAdding(null);setHelp(null);}} options={scopes.map(value=>({value,label:value === "SHARED"?sharedLabel:personalLabel,icon:value === "SHARED"?Building2:UserRound}))}/> : <h2 className="text-sm font-medium">{scope === "SHARED"?sharedLabel:personalLabel}</h2>}</div>
    <div className="flex items-start gap-3 rounded-panel bg-muted/40 p-4">{scope === "SHARED"?<Building2 className="mt-0.5 size-5 shrink-0 text-muted-foreground"/>:<LockKeyhole className="mt-0.5 size-5 shrink-0 text-muted-foreground"/>}<div className="space-y-1"><p className="text-sm font-medium">{msg(scope === "SHARED"?"sharedTitle":"personalTitle")}</p><p className="text-xs leading-relaxed text-muted-foreground">{msg(scope === "SHARED"?"sharedDescription":"personalDescription")}</p></div></div>
    {error && <div role="alert" className="flex flex-wrap items-center gap-3 rounded-panel border border-destructive/30 p-4 text-sm"><AlertCircle className="size-5 text-destructive"/><span>{msg("loadError")}</span><Button size="toolbar" variant="subtle" onClick={()=>void refresh()} disabled={refreshing}>{msg("retry")}</Button></div>}
    {!data && !error && <div role="status" aria-label={msg("loading")} aria-busy="true" className="grid gap-4 sm:grid-cols-2">{[0,1,2,3].map(i=><div key={i} aria-hidden="true" className="space-y-5 rounded-panel border border-border p-5 motion-safe:animate-pulse"><div className="flex gap-3"><div className="size-10 rounded-field bg-muted"/><div className="flex-1 space-y-2"><div className="h-4 w-2/3 rounded-field bg-muted"/><div className="h-3 w-1/2 rounded-field bg-muted"/></div></div><div className="h-3 w-full rounded-field bg-muted"/><div className="h-8 w-28 rounded-pill bg-muted"/></div>)}</div>}
    {data && <>
      <section className="space-y-3"><div className="flex items-center gap-2"><h2 className="text-sm font-medium">{msg("yourAccounts")}</h2><span className="text-xs text-muted-foreground">{accounts.length}</span></div>
      {accounts.length ? <div className="grid gap-4 sm:grid-cols-2">{accounts.map(account=><AccountCard key={account.id} account={account} provider={data.providers.find(p=>p.key === account.provider)} reload={load} setups={config.setups ?? []}/>)}</div> : <div className="flex flex-col items-center gap-2 rounded-panel border border-dashed border-border px-5 py-8 text-center"><Plug className="mb-1 size-5 text-muted-foreground"/><p className="text-sm font-medium">{msg("emptyTitle")}</p><p className="max-w-md text-xs text-muted-foreground">{msg(canManage?"emptyDescription":"readOnlyDescription")}</p></div>}</section>
      {adding && <form onSubmit={add} className="space-y-4 rounded-panel border border-border bg-card p-5"><div className="flex items-center gap-3"><ProviderIcon provider={adding}/><div><h3 className="text-sm font-medium">{t(prefix+"addProvider",{provider:adding.label})}</h3><p className="text-xs text-muted-foreground">{scope === "PERSONAL"?personalLabel:sharedLabel}</p></div></div><Label className="block space-y-2"><span>{msg("accountName")}</span><Input autoFocus aria-label={msg("accountName")} value={name} maxLength={120} onChange={(e:ChangeEvent<HTMLInputElement>)=>setName(e.target.value)} placeholder={msg("namePlaceholder")}/></Label><p className="text-xs text-muted-foreground">{msg("authorizeNext")}</p>{formError && <p role="alert" className="text-xs text-destructive">{formError}</p>}<div className="flex gap-2"><Button type="submit" disabled={saving || !name.trim()}>{msg(saving?"saving":"addAccount")}</Button><Button type="button" variant="subtle" disabled={saving} onClick={()=>setAdding(null)}>{msg("cancel")}</Button></div></form>}
      <section className="space-y-3"><div><h2 className="text-sm font-medium">{msg("availableProviders")}</h2><p className="mt-1 text-xs text-muted-foreground">{msg("providersDescription")}</p></div><div className="grid gap-4 sm:grid-cols-2">{providers.map(provider=>{
        const supported=provider.scopes.includes(scope),available=provider.enabled && supported;
        const legacy=data.accounts.find(a=>a.legacy && a.provider === provider.key);
        return <section key={provider.key} className="flex flex-col gap-4 rounded-panel border border-border bg-card p-5"><div className="flex items-center gap-3"><ProviderIcon provider={provider}/><div className="flex-1"><h3 className="text-sm font-medium">{provider.label}</h3><p className="text-xs text-muted-foreground">{msg(available?"ready":!supported && scope === "PERSONAL"?"sharedOnly":"setupRequired")}</p></div></div><p className="text-xs leading-relaxed text-muted-foreground">{provider.description}</p><div className="mt-auto flex flex-wrap gap-2">{available ? <Button size="toolbar" variant="subtle" disabled={!canManage || saving} onClick={()=>{setAdding(provider);setName(provider.label);setFormError("");}}><Plus/>{msg("addAccount")}</Button> : <Button size="toolbar" variant="subtle" disabled><LockKeyhole/>{msg(!supported && scope === "PERSONAL"?"sharedOnly":"unavailable")}</Button>}<Button size="toolbar" variant="ghost" aria-expanded={help === provider.key} onClick={()=>setHelp(help === provider.key?null:provider.key)}><CircleHelp/>{msg("details")}</Button></div>{help === provider.key && <p className="border-t border-border pt-3 text-xs leading-relaxed text-muted-foreground">{available?msg(scope === "PERSONAL"?"personalHelp":"sharedHelp"):provider.setupHint || msg("unavailable")}{legacy && scope === "SHARED"?` ${msg("manageAbove")}`:""}</p>}</section>;
      })}</div>{!providers.length && <p className="text-sm text-muted-foreground">{msg("noProviders")}</p>}</section>
    </>}
  </div>;
}
