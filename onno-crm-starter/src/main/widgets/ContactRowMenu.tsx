import { text as t, useTranslate } from "@onno/widget-sdk";
import type { ChatGroup, GroupChange } from "./chatGroups";
import { ExtensionSlot, Button, Input } from "@onno/widget-sdk";
import type { ReactNode } from "react";
import { ContextMenuSub, ContextMenuContent, ContextMenuItem, useState } from "@onno/widget-sdk";

export function ContactRowMenu({ id, canEdit, onOpen, groups, groupKey, conversationId, onGroupChange, workspaceKey, record, children }: {
  workspaceKey?: string; record?: Readonly<Record<string,unknown>>;
  groups?: ChatGroup[]; groupKey?: string; conversationId?: string; onGroupChange?: (change: GroupChange) => Promise<void>;
  id: string; canEdit: boolean; onOpen: () => void; children: ReactNode;
}) {
  const [position, setPosition] = useState<{ x: number; y: number } | null>(null);
  const [creating, setCreating] = useState(false);
  const [label, setLabel] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const changeGroup = async (change: GroupChange) => {
    if (!onGroupChange || busy) return;
    setBusy(true);setError("");
    try {await onGroupChange({...change,conversationId});setPosition(null);setCreating(false);setLabel("");}
    catch(e) {setError((e as Error).message);} finally {setBusy(false);}
  };
  const show = (x: number, y: number) => { if (!id) return; setCreating(false);setError("");setPosition({ x, y }); };
  return <div onContextMenu={event => { event.preventDefault(); show(event.clientX, event.clientY); }}
    onKeyDown={event => { if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
      event.preventDefault(); const box = event.currentTarget.getBoundingClientRect(); show(box.left + 20, box.top + 20);
    } }}>
    {children}
    <ContextMenuContent open={!!position} position={position} width={200}
      onOpenChange={(open: boolean) => { if (!open) setPosition(null); }}>
      <ContextMenuItem onSelect={() => { setPosition(null); onOpen(); }}>{t("crm.contact.openConversation")}</ContextMenuItem>
      {onGroupChange && <ContextMenuSub label="Move to group" width={240}>
        <div className="max-h-64 overflow-y-auto">
          {groups?.map(group => <ContextMenuItem key={group.key} disabled={busy || group.key === groupKey} onSelect={() => void changeGroup({operation:"move",key:group.key})}>{group.label}{group.key === groupKey ? " ✓" : ""}</ContextMenuItem>)}
        </div>
        {groupKey && <ContextMenuItem disabled={busy} onSelect={() => void changeGroup({operation:"move",key:null})}>{t("crm.group.remove")}</ContextMenuItem>}
        {creating ? <form className="space-y-2 p-2" onSubmit={event => {event.preventDefault();void changeGroup({operation:"create",label});}}>
          <Input autoFocus aria-label={t("crm.group.name")} placeholder={t("crm.group.name")} maxLength={80} value={label} onChange={event => setLabel(event.target.value)} />
          <Button size="sm" type="submit" disabled={busy || !label.trim()}>{t("crm.group.createAndMove")}</Button>
        </form> : <ContextMenuItem disabled={busy} onSelect={() => setCreating(true)}>{t("crm.group.new")}</ContextMenuItem>}
        {error && <p role="alert" className="p-2 text-xs text-destructive">{error}</p>}
      </ContextMenuSub>}
      <ExtensionSlot name="crm.chat.context-menu" context={{surface:"crm-chat",kind:"catalogs",name:"crm_conversations",recordId:conversationId,record:record??{id:conversationId,customer:id},workspaceKey,openRecord:(kind,name,id)=>window.dispatchEvent(new CustomEvent("onno:action",{detail:`onno://${kind}/${name}/${id}`})),permissions:{canWrite:canEdit},closeMenu:()=>setPosition(null)}} />
    </ContextMenuContent>
  </div>;
}
