import {registerExtension, useState, useEffect, Button, Input, Label, toast, type ExtensionProps} from "@onno/widget-sdk";
import {RefreshCw, Pause, Play, KeyRound, Loader2} from "lucide-react";
export async function request<T>(path: string, body?: unknown): Promise<T> {
  const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
  const response = await fetch(`/api/crm${path}`, { credentials: "same-origin", ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) }, body: JSON.stringify(body) }) });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || error.detail || `Request failed (${response.status})`); }
  return response.json();
}
type ChannelConnection = { key: string; label: string; channel: string; state: string; account: string; description: string; actions: string[] };
function ConnectorSetup({context}: ExtensionProps) {
  const channel=context.record as unknown as ChannelConnection;
  const canManage=context.permissions.canManage;
  const load=async()=>{await context.refresh?.();};
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [editing, setEditing] = useState("");
  const [credential, setCredential] = useState("");
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
        const result = await request<{url: string}>("/gmail/authorize", {returnPath: window.location.pathname + window.location.search});
        window.location.assign(result.url); return;
      }
      await request(`/channels/${channel.key}`, {action, ...(action === "reconnect" ? {credential} : {})});
      setCredential("");setEditing("");await load();
      toast.success(action === "check" ? `${channel.label} connection verified.` : action === "reconnect" ? `${channel.label} credentials updated.` : `${channel.label} ${action === "pause" ? "paused" : "resumed"}.`);
    } catch(e) {setError((e as Error).message);setCredential("");} finally {setBusy("");}
  };
  return <div className="space-y-3">{error && <p role="alert">{error}</p>}
      <div className="flex flex-wrap gap-2">{channel.actions.map(a => <Button key={a} size="toolbar" variant="subtle" disabled={!canManage || !!busy} onClick={() => a === "reconnect" ? (setEditing(channel.key),setCredential("")) : void command(channel,a)}>{busy === channel.key ? <Loader2 className="animate-spin" /> : a === "check" ? <RefreshCw /> : a === "pause" ? <Pause /> : a === "resume" ? <Play /> : <KeyRound />}{busy === channel.key ? "Working…" : ({connect:"Connect Google account",check:"Check connection",pause:"Pause",resume:"Resume",reconnect:"Reconnect bot"} as Record<string,string>)[a] || a}</Button>)}</div>
      {editing === channel.key && <form className="space-y-3 border-t border-border pt-4" onSubmit={(e: {preventDefault:()=>void}) => {e.preventDefault();void command(channel,"reconnect");}}>
        <Label className="block space-y-2 text-xs"><span>Bot token</span><Input aria-label="Telegram bot token" type="password" autoComplete="new-password" value={credential} onChange={(e: {target:{value:string}}) => setCredential(e.target.value)} /></Label>
        <p className="text-xs text-muted-foreground">Use a token for {channel.account}. The token is verified and stored on the server; existing conversations stay connected to this bot.</p>
        <div className="flex gap-2"><Button type="submit" disabled={!!busy || !credential.trim()}>Verify and reconnect</Button><Button type="button" variant="outline" disabled={!!busy} onClick={() => {setCredential("");setEditing("");}}>Cancel</Button></div>
      </form>}
</div>;
}
registerExtension({id:"onno.channels.setup",slot:"crm.channel.settings",
 visible:context=>["gmail","telegram","instagram","whatsapp"].includes(String(context.record?.key)),component:ConnectorSetup});
