import { text as t, useTranslate } from "@onno/widget-sdk";
import { Button, Input, Label, Popover, PopoverContent, PopoverTrigger, useState } from "@onno/widget-sdk";
import { Check, Folder, MoreHorizontal, Trash2 } from "lucide-react";
import type { ChatGroup, GroupChange } from "./chatGroups";

export function ChatGroupActions({group,change}:{group:ChatGroup;change:(body:GroupChange)=>Promise<void>}) {
  const [open,setOpen]=useState(false);
  const [label,setLabel]=useState(group.label);
  const [error,setError]=useState("");
  const [busy,setBusy]=useState(false);
  const nameId = `chat-group-name-${group.key}`;
  const canRename = !!label.trim() && label.trim() !== group.label;
  const save=async(operation:"rename"|"delete")=>{
    if (busy || (operation === "rename" && !canRename)) return;
    setBusy(true);setError("");
    try {await change({operation,key:group.key,label:label.trim()});setOpen(false);}catch(e){setError((e as Error).message);}finally{setBusy(false);}
  };
  return <Popover open={open} onOpenChange={value=>{if(busy)return;setOpen(value);if(value){setLabel(group.label);setError("");}}}>
    <PopoverTrigger asChild><Button className="ml-auto" size="sm" variant="ghost" aria-label={t("crm.group.options")}><MoreHorizontal className="size-4" /></Button></PopoverTrigger>
    <PopoverContent className="w-60 max-w-[calc(100vw-32px)] space-y-2 rounded-card p-2" align="end" sideOffset={8} aria-label={t("crm.group.settings")}>
      <div className="flex items-center justify-center gap-2 py-1 text-sm font-medium"><Folder className="size-4 text-muted-foreground" />{t("crm.group.settings")}</div>
      <form className="space-y-2" onSubmit={event=>{event.preventDefault();void save("rename");}}>
        <div className="space-y-1.5">
          <Label htmlFor={nameId} className="block text-center text-xs text-muted-foreground">{t("crm.group.name")}</Label>
          <Input id={nameId} value={label} maxLength={80} disabled={busy} className="h-8 rounded-control text-center text-sm shadow-none" onChange={event=>setLabel(event.target.value)} />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Button type="button" size="toolbar" className="h-8 rounded-control" variant="subtle" disabled={busy} onClick={()=>setOpen(false)}>{t("crm.group.cancel")}</Button>
          <Button type="submit" size="toolbar" className="h-8 rounded-control" disabled={busy||!canRename}><Check className="size-3.5" />{t("crm.group.save")}</Button>
        </div>
      </form>
      <div className="space-y-1 border-t border-border pt-2">
        <Button type="button" size="toolbar" variant="ghost" className="h-8 w-full justify-center rounded-control font-normal text-destructive hover:bg-destructive/10 hover:text-destructive" disabled={busy} onClick={()=>void save("delete")}><Trash2 className="size-3.5" />{t("crm.group.delete")}</Button>
        <p className="px-2 text-center text-xs leading-5 text-muted-foreground">{t("crm.group.deleteHint")}</p>
      </div>
      {error&&<p role="alert" className="text-xs text-destructive">{error}</p>}
    </PopoverContent>
  </Popover>;
}
