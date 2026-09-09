import { useCallback, useEffect, useState, useUiEvents, type EntityRecord } from "@onno/widget-sdk";
import { request } from "./CrmWorkspace";
export function useConversationStatuses() {
  const [statuses,setStatuses]=useState<EntityRecord[]>([]);
  const [error,setError]=useState("");
  const load=useCallback(()=>{void request<EntityRecord[]>("/statuses").then(rows=>{setStatuses(rows.filter(row=>!row.deletionMark));setError("");}).catch(()=>setError("Could not load conversation statuses."));},[]);
  useEffect(load,[load]);
  useUiEvents(load,{types:["created","updated","deleted"],entityType:"catalog"});
  return {statuses,error};
}
