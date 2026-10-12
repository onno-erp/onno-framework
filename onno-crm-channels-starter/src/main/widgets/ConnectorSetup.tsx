import {registerExtension, useState, useEffect, useTranslate, text as t, Button, Input, Label, toast, type ExtensionProps} from "@onno/widget-sdk";
import {RefreshCw, Pause, Play, KeyRound, Loader2} from "lucide-react";
export async function request<T>(path: string, body?: unknown): Promise<T> {
  const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
  const response = await fetch(`/api/crm${path}`, { credentials: "same-origin", ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) }, body: JSON.stringify(body) }) });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || error.detail || `Request failed (${response.status})`); }
  return response.json();
}
type ChannelConnection = { key: string; label: string; channel: string; state: string; account: string; description: string; actions: string[]; accountId?: string };
function ConnectorSetup({context}: ExtensionProps) {
  useTranslate();
  const msg=(key:string)=>t("crm.connections."+key);
  // Keys newer than the host's bundled defaults render as themselves; fall back to English then.
  const msgOr=(key:string,fallback:string,params:Record<string,string>={})=>{
    const full="crm.connections."+key;
    const value=t(full,params);
    if(value!==full)return value;
    return Object.entries(params).reduce((rendered,[name,v])=>rendered.split(`{${name}}`).join(v),fallback);
  };
  // Only the ordinary Telegram bot reconnects with a bot token; other providers reconnect generically.
  const actionLabel=(action:string)=>action==="reconnect"&&channel.key==="telegram"?msgOr("reconnectBot","Reconnect bot"):msg(action);
  const [confirming,setConfirming]=useState(false);
  const channel=context.record as unknown as ChannelConnection;
  const canManage=context.permissions.canManage;
  const load=async()=>{await context.refresh?.();};
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [editing, setEditing] = useState("");
  const [credential, setCredential] = useState("");
  const [telegramUrl, setTelegramUrl] = useState("");
  useEffect(() => {
    const url = new URL(window.location.href);
    const result = url.searchParams.get("gmail");
    if (!result) return;
    if (result === "connected") toast.success(msg("googleConnected"));
    else toast.error(msg("googleError"));
    url.searchParams.delete("gmail"); window.history.replaceState(window.history.state, "", url);
  }, []);
  const command = async (channel: ChannelConnection, action: string) => {
    setBusy(channel.key);setError("");
    try {
      if (action === "connect" && channel.key === "gmail") {
        const result = await request<{url: string}>("/gmail/authorize", {returnPath: window.location.pathname + window.location.search,...(channel.accountId?{accountId:channel.accountId}:{})});
        window.location.assign(result.url); return;
      }
      if (action === "connect" && channel.key === "telegram-business" && channel.accountId) {
        const result = await request<{url: string}>(`/telegram/business/${channel.accountId}/authorize`, {});
        setTelegramUrl(result.url); return;
      }
      await request(channel.accountId?`/connection-accounts/${channel.accountId}`:`/channels/${channel.key}`, {action, ...(action === "reconnect" ? {credential} : {})});
      setCredential("");setEditing("");setConfirming(false);await load();
      toast.success(msg("actionSuccess"));
    } catch(e) {setError((e as Error).message);setCredential("");} finally {setBusy("");}
  };
  return <div className="space-y-3">{error && <p role="alert">{error}</p>}
      <div className="flex flex-wrap gap-2">{channel.actions.map(a => <Button key={a} size="toolbar" variant="subtle" disabled={!canManage || !!busy} onClick={() => a === "disconnect" ? setConfirming(true) : a === "reconnect" ? (setEditing(channel.key),setCredential("")) : void command(channel,a)}>{busy === channel.key ? <Loader2 className="animate-spin" /> : a === "check" ? <RefreshCw /> : a === "pause" ? <Pause /> : a === "resume" ? <Play /> : <KeyRound />}{busy === channel.key ? msg("working") : actionLabel(a)}</Button>)}</div>
      {telegramUrl && <div className="space-y-2 rounded-field bg-muted/40 p-3">
        <a className="underline" href={telegramUrl} target="_blank" rel="noreferrer">{msgOr("telegramOpenStart","Open Telegram and press Start")}</a>
        <p className="text-xs text-muted-foreground">{msgOr("telegramBusinessSteps","Then choose {account} in Telegram Settings → Chat Automation (or Telegram Business → Chatbots), select which chats it can access (prefer selected chats over all chats), and allow replies. This connection link expires in 10 minutes.",{account:channel.account})}</p>
        <Button size="toolbar" variant="subtle" onClick={()=>void load()}>{msgOr("refreshConnection","Refresh connection")}</Button>
      </div>}
      {confirming && <div className="space-y-3 rounded-field bg-muted/40 p-3"><p className="text-xs text-muted-foreground">{msg("disconnectPrompt")}</p><div className="flex flex-wrap gap-2"><Button size="toolbar" disabled={!!busy || !canManage} onClick={()=>void command(channel,"disconnect")}>{msg("confirmDisconnect")}</Button><Button size="toolbar" variant="subtle" disabled={!!busy} onClick={()=>setConfirming(false)}>{msg("cancel")}</Button></div></div>}
      {editing === channel.key && <form className="space-y-3 border-t border-border pt-4" onSubmit={(e: {preventDefault:()=>void}) => {e.preventDefault();void command(channel,"reconnect");}}>
        <Label className="block space-y-2 text-xs"><span>{msgOr("botToken","Bot token")}</span><Input aria-label={msgOr("telegramBotToken","Telegram bot token")} type="password" autoComplete="new-password" value={credential} onChange={(e: {target:{value:string}}) => setCredential(e.target.value)} /></Label>
        <p className="text-xs text-muted-foreground">{msgOr("botTokenHint","Use a token for {account}. The token is verified and stored on the server; existing conversations stay connected to this bot.",{account:channel.account})}</p>
        <div className="flex gap-2"><Button type="submit" disabled={!!busy || !credential.trim()}>{msgOr("verifyReconnect","Verify and reconnect")}</Button><Button type="button" variant="outline" disabled={!!busy} onClick={() => {setCredential("");setEditing("");}}>{msg("cancel")}</Button></div>
      </form>}
</div>;
}
registerExtension({id:"onno.channels.setup",slot:"crm.channel.settings",
 visible:context=>["gmail","telegram","telegram-business","instagram","whatsapp"].includes(String(context.record?.key)),component:ConnectorSetup});
