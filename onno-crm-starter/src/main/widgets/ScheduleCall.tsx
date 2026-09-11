import { text as t } from "@onno/widget-sdk";
import { useId } from "react";
import { Button, DatePicker, Input, Label, Popover, PopoverContent, PopoverTrigger, useState } from "@onno/widget-sdk";
import { Video } from "lucide-react";
import { request } from "./CrmWorkspace";

export function callInvitation(when: string, link: string, duration: string, now = Date.now()) {
  const date = new Date(when);
  if (!when || !Number.isFinite(date.getTime()) || date.getTime() <= now) throw new Error(t("crm.zoom.pastDate"));
  let url: URL;
  try { url = new URL(link.trim()); } catch { throw new Error(t("crm.zoom.invalidLink")); }
  if (url.protocol !== "https:" || !(url.hostname === "zoom.us" || url.hostname.endsWith(".zoom.us")) || url.username || url.password)
    throw new Error(t("crm.zoom.hostError"));
  if (!Number.isInteger(Number(duration)) || Number(duration) < 5 || Number(duration) > 240) throw new Error(t("crm.zoom.durationError"));
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const display = new Intl.DateTimeFormat(undefined, {dateStyle:"medium",timeStyle:"short"}).format(date);
  return `Let's meet on Zoom on ${display} (${zone}).\nDuration: ${duration} minutes\n${url.href}`;
}

/** Schedules an internal CRM event using an existing meeting URL; no provider API is invoked. */
export function ScheduleCall({customerId, conversationId, onSaved, onInvitation}: {
  customerId:string; conversationId:string; onSaved:()=>Promise<void>; onInvitation:(text:string)=>void;
}) {
  const id=useId();
  const [open,setOpen]=useState(false);
  const [when,setWhen]=useState("");
  const [link,setLink]=useState("");
  const [duration,setDuration]=useState("30");
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const save=async()=>{
    if(busy)return;
    setError("");
    let invitation:string;
    try {invitation=callInvitation(when,link,duration);} catch(e) {setError((e as Error).message);return;}
    setBusy(true);
    try {
      await request(`/contacts/${encodeURIComponent(customerId)}/activity`,{
        conversationId,type:"MEETING_PLANNED",scheduledFor:when,
        details:`${invitation}\nUTC: ${new Date(when).toISOString()}`,
      });
      onInvitation(invitation);
      setOpen(false);setWhen("");setLink("");
      await onSaved();
    } catch(e) {setError((e as Error).message);} finally {setBusy(false);}
  };
  return <Popover open={open} onOpenChange={value=>{if(!busy){setOpen(value);setError("");}}}>
    <PopoverTrigger asChild><Button size="toolbar" variant="subtle"><Video className="size-4" />{t("crm.zoom.schedule")}</Button></PopoverTrigger>
    <PopoverContent align="end" className="w-96 max-w-[calc(100vw-32px)] space-y-3 p-4" aria-label={t("crm.zoom.scheduleCall")}>
      <div className="text-sm font-semibold">{t("crm.zoom.scheduleCall")}</div>
      <div className="space-y-1"><div className="text-sm font-medium">{t("crm.zoom.dateTime")}</div><DatePicker value={when} onChange={setWhen} includeTime aria-label={t("crm.zoom.dateTime")} isDisabled={busy} /><p className="text-xs text-muted-foreground">Time zone: {Intl.DateTimeFormat().resolvedOptions().timeZone}</p></div>
      <div className="space-y-1"><Label htmlFor={`${id}-duration`}>{t("crm.zoom.duration")}</Label><Input id={`${id}-duration`} type="number" min={5} max={240} value={duration} onChange={e=>setDuration(e.target.value)} disabled={busy} /></div>
      <div className="space-y-1"><Label htmlFor={`${id}-link`}>{t("crm.zoom.link")}</Label><Input id={`${id}-link`} type="url" placeholder="https://zoom.us/j/…" maxLength={2000} value={link} onChange={e=>setLink(e.target.value)} disabled={busy} /></div>
      <p className="text-xs text-muted-foreground">Paste a link from Zoom. Saving adds the call to this client's history and prepares an invitation in the reply box. Send it when ready.</p>
      {error&&<p role="alert" className="text-xs text-destructive">{error}</p>}
      <div className="flex justify-end gap-2"><Button variant="ghost" disabled={busy} onClick={()=>setOpen(false)}>{t("crm.group.cancel")}</Button><Button disabled={busy||!when||!link.trim()} onClick={()=>void save()}>{busy?t("crm.activity.saving"):t("crm.zoom.save")}</Button></div>
    </PopoverContent>
  </Popover>;
}
