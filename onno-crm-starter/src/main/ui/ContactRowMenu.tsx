import { text as t, useTranslate } from "@onno/ui";
import type { ChatGroup, GroupChange } from "./chatGroups";
import { ExtensionSlot, Button, Input, openRoute } from "@onno/ui";
import type { KeyboardEvent, ReactNode } from "react";
import { ContextMenuSub, ContextMenuContent, ContextMenuItem } from "@onno/ui";
import { useEffect, useRef, useState } from "react";
import { MoreHorizontal } from "@onno/ui/icons";

const MENU_WIDTH = 200;
const enabledItems = (menu: HTMLElement | null) => Array.from(menu?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
  .filter(item => !item.hasAttribute("disabled") && item.getAttribute("aria-disabled") !== "true");

/**
 * A conversation row's menu: open, move to a chat group (or start one), and contributions. It opens on
 * right-click, on the ContextMenu key / Shift+F10, and from the row's own "⋯" button, which the inbox
 * shows on hover and keeps visible on touch screens, where there is no right-click to discover.
 */
export function ContactRowMenu({ id, canEdit, onOpen, groups, groupKey, conversationId, onGroupChange, workspaceKey, record, children }: {
  workspaceKey?: string; record?: Readonly<Record<string,unknown>>;
  groups?: ChatGroup[]; groupKey?: string; conversationId?: string; onGroupChange?: (change: GroupChange) => Promise<void>;
  id: string; canEdit: boolean; onOpen: () => void; children: ReactNode;
}) {
  useTranslate();
  const [position, setPosition] = useState<{ x: number; y: number } | null>(null);
  const [creating, setCreating] = useState(false);
  const [label, setLabel] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const menuRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  // Opened from the "⋯" button: focus goes into the menu and comes back to the button afterwards.
  const fromButton = useRef(false);
  const close = () => {
    setPosition(null);
    if (fromButton.current) { fromButton.current = false; requestAnimationFrame(() => triggerRef.current?.focus({ preventScroll: true })); }
  };
  const changeGroup = async (change: GroupChange) => {
    if (!onGroupChange || busy) return;
    setBusy(true);setError("");
    try {await onGroupChange({...change,conversationId});close();setCreating(false);setLabel("");}
    catch(e) {setError((e as Error).message);} finally {setBusy(false);}
  };
  const show = (x: number, y: number, viaButton = false) => { if (!id) return; fromButton.current = viaButton; setCreating(false);setError("");setPosition({ x, y }); };
  useEffect(() => {
    if (!position || !fromButton.current) return;
    const frame = requestAnimationFrame(() => enabledItems(menuRef.current)[0]?.focus());
    return () => cancelAnimationFrame(frame);
  }, [position]);
  const moveFocus = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    const items = enabledItems(menuRef.current);
    if (!items.length) return;
    event.preventDefault();
    const at = items.indexOf(document.activeElement as HTMLElement);
    const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1
      : (at + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
    items[at < 0 && event.key === "ArrowUp" ? items.length - 1 : next]?.focus();
  };
  return <div data-row-menu-host onContextMenu={event => { event.preventDefault(); show(event.clientX, event.clientY); }}
    onKeyDown={event => { if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
      event.preventDefault(); const box = event.currentTarget.getBoundingClientRect(); show(box.left + 20, box.top + 20);
    } }}>
    {children}
    {id && <Button ref={triggerRef} type="button" variant="ghost" size="toolbar" data-row-menu-trigger
      className="size-7 shrink-0 rounded-pill p-0 text-muted-foreground hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground" aria-haspopup="menu" aria-expanded={!!position}
      aria-label={t("crm.chat.rowMenu")} title={t("crm.chat.rowMenu")}
      onClick={(event: { stopPropagation: () => void; currentTarget: HTMLElement }) => {
        event.stopPropagation();
        if (position) { close(); return; }
        const box = event.currentTarget.getBoundingClientRect();
        show(Math.max(8, box.right - MENU_WIDTH), box.bottom + 4, true);
      }}>
      <MoreHorizontal className="size-4" aria-hidden="true" />
    </Button>}
    <ContextMenuContent ref={menuRef} open={!!position} position={position} width={MENU_WIDTH} onKeyDown={moveFocus}
      onOpenChange={(open: boolean) => { if (!open) close(); }}>
      <ContextMenuItem onSelect={() => { close(); onOpen(); }}>{t("crm.contact.openConversation")}</ContextMenuItem>
      {onGroupChange && <ContextMenuSub label={t("crm.group.move")} width={240}>
        <div className="max-h-64 overflow-y-auto">
          {groups?.map(group => <ContextMenuItem key={group.key} disabled={busy || group.key === groupKey} onSelect={() => void changeGroup({operation:"move",key:group.key})}>{group.label}{group.key === groupKey ? " ✓" : ""}</ContextMenuItem>)}
        </div>
        {groupKey && <ContextMenuItem disabled={busy} onSelect={() => void changeGroup({operation:"move",key:null})}>{t("crm.group.remove")}</ContextMenuItem>}
        {creating ? <form className="space-y-2 p-2" onSubmit={event => {event.preventDefault();void changeGroup({operation:"create",label});}}>
          <Input autoFocus aria-label={t("crm.group.name")} placeholder={t("crm.group.name")} maxLength={80} value={label} onChange={(event: { target: { value: string } }) => setLabel(event.target.value)} />
          <Button size="sm" type="submit" disabled={busy || !label.trim()}>{t("crm.group.createAndMove")}</Button>
        </form> : <ContextMenuItem disabled={busy} onSelect={() => setCreating(true)}>{t("crm.group.new")}</ContextMenuItem>}
        {error && <p role="alert" className="p-2 text-xs text-destructive">{error}</p>}
      </ContextMenuSub>}
      <ExtensionSlot name="crm.chat.context-menu" context={{surface:"crm-chat",kind:"catalogs",name:"crm_conversations",recordId:conversationId,record:record??{id:conversationId,customer:id},workspaceKey,openRecord:(kind,name,id)=>openRoute(`onno://${kind}/${name}/${id}`),permissions:{canWrite:canEdit},closeMenu:close}} />
    </ContextMenuContent>
  </div>;
}
