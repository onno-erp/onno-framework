import { useId } from "react";
import { Button, DatePicker, Input, Label, Popover, PopoverContent, PopoverTrigger, useState } from "@onno/widget-sdk";
import { Video } from "lucide-react";
import { request } from "./CrmWorkspace";

export function callInvitation(when: string, link: string, duration: string, now = Date.now()) {
  const date = new Date(when);
  if (!when || !Number.isFinite(date.getTime()) || date.getTime() <= now) throw new Error("Choose a future date and time.");
  let url: URL;
  try { url = new URL(link.trim()); } catch { throw new Error("Paste a valid Zoom meeting link."); }
  if (url.protocol !== "https:" || !(url.hostname === "zoom.us" || url.hostname.endsWith(".zoom.us")) || url.username || url.password)
    throw new Error("Use an HTTPS meeting link on zoom.us.");
  if (!Number.isInteger(Number(duration)) || Number(duration) < 5 || Number(duration) > 240) throw new Error("Choose a duration between 5 and 240 minutes.");
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
    <PopoverTrigger asChild><Button size="toolbar" variant="subtle"><Video className="size-4" />Schedule Zoom</Button></PopoverTrigger>
    <PopoverContent align="end" className="w-96 max-w-[calc(100vw-32px)] space-y-3 p-4" aria-label="Schedule Zoom call">
      <div className="text-sm font-semibold">Schedule a Zoom call</div>
      <div className="space-y-1"><div className="text-sm font-medium">Date and time</div><DatePicker value={when} onChange={setWhen} includeTime aria-label="Date and time" isDisabled={busy} /><p className="text-xs text-muted-foreground">Time zone: {Intl.DateTimeFormat().resolvedOptions().timeZone}</p></div>
      <div className="space-y-1"><Label htmlFor={`${id}-duration`}>Duration (minutes)</Label><Input id={`${id}-duration`} type="number" min={5} max={240} value={duration} onChange={e=>setDuration(e.target.value)} disabled={busy} /></div>
      <div className="space-y-1"><Label htmlFor={`${id}-link`}>Zoom meeting link</Label><Input id={`${id}-link`} type="url" placeholder="https://zoom.us/j/…" maxLength={2000} value={link} onChange={e=>setLink(e.target.value)} disabled={busy} /></div>
      <p className="text-xs text-muted-foreground">Paste a link from Zoom. Saving adds the call to this client's history and prepares an invitation in the reply box. Send it when ready.</p>
      {error&&<p role="alert" className="text-xs text-destructive">{error}</p>}
      <div className="flex justify-end gap-2"><Button variant="ghost" disabled={busy} onClick={()=>setOpen(false)}>Cancel</Button><Button disabled={busy||!when||!link.trim()} onClick={()=>void save()}>{busy?"Saving…":"Save & prepare invitation"}</Button></div>
    </PopoverContent>
  </Popover>;
}
