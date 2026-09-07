import { useCallback, useEffect, useState } from "@onno/widget-sdk";
export type ChatGroup = {key: string; label: string; customerIds: string[]};
export type GroupChange = {operation: "create" | "move" | "rename" | "delete"; key?: string | null; label?: string; conversationId?: string};
export function useChatGroups(workspace?: string) {
  const url = `/api/crm/chat-groups${workspace ? `?workspace=${encodeURIComponent(workspace)}` : ""}`;
  const [groups, setGroups] = useState<ChatGroup[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    const response = await fetch(url, {credentials: "same-origin"});
    if (!response.ok) throw new Error("Could not load chat groups");
    const data: ChatGroup[] = await response.json();
    setGroups(data); setReady(true); setError("");
  }, [url]);
  useEffect(() => {
    setGroups([]); setReady(false);
    const reload = () => {void load().catch(e => setError(e.message));};
    reload();window.addEventListener("focus",reload);window.addEventListener("onno-chat-groups",reload);
    return () => {window.removeEventListener("focus",reload);window.removeEventListener("onno-chat-groups",reload);};
  }, [load]);
  const change = async (body: GroupChange) => {
    const token = document.cookie.split(";").map(s => s.trim()).find(s => s.startsWith("XSRF-TOKEN="))?.slice(11);
    const response = await fetch(url,{method:"POST",credentials:"same-origin",headers:{"Content-Type":"application/json",...(token?{"X-XSRF-TOKEN":decodeURIComponent(token)}:{})},body:JSON.stringify(body)});
    if(!response.ok) {const error=await response.json().catch(()=>({}));throw new Error(error.message || error.detail || "Could not save chat group");}
    setGroups(await response.json());setError("");window.dispatchEvent(new Event("onno-chat-groups"));
  };
  return {groups,ready,error,change};
}
