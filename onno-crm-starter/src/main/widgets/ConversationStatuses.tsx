import { api, useCallback, useEffect, useState, useUiEvents, type EntityRecord } from "@onno/widget-sdk";
export function useConversationStatuses() {
  const [statuses,setStatuses]=useState<EntityRecord[]>([]);
  const [error,setError]=useState("");
  const load=useCallback(()=>{void api.listCatalog("crm_conversation_statuses").then(rows=>{setStatuses(rows.filter(row=>!row.deletionMark));setError("");}).catch(()=>setError("Could not load conversation statuses."));},[]);
  useEffect(load,[load]);
  useUiEvents(load,{types:["created","updated","deleted"],entityType:"catalog",entityName:"CrmConversationStatuses"});
  return {statuses,error};
}
