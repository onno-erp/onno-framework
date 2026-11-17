import { Button, text as t, type EntityRecord } from "@onno/ui";
import type { ComponentType } from "react";
import { CrmAvatar } from "./ChatTimeline";
import { ContactRowMenu } from "./ContactRowMenu";
import { ConversationRowContent, type ConversationRowOptions } from "./ConversationList";
import { action, rowValue, type Config } from "./CrmWorkspace";
import type { ChatGroup, GroupChange } from "./chatGroups";
import { compactTime, conversationChannel, string } from "./crmModel";

/** What the conversation row's menu (right-click, ⋯) offers besides the app's `crm.chat.context-menu` items. */
export type ConversationRowMenu = {
  /** "Open conversation" (default true). */
  open?: boolean;
  /** Moving the chat into a chat group (default true, where chat groups are on). */
  groups?: boolean;
};

/** Props every conversation row gets, the built-in one or an app's own (`components.ConversationRow`). */
export type ConversationRowProps = {
  /** The conversation (the contact's latest chat, with unread counts summed across their chats). */
  row: EntityRecord;
  selected: boolean;
  onSelect: () => void;
  config?: Config;
  appearance?: ConversationRowOptions;
  menu?: ConversationRowMenu;
  workspaceKey?: string;
  groups?: ChatGroup[];
  groupKey?: string;
  onGroupChange?: (change: GroupChange) => Promise<void>;
};

/**
 * One conversation in the list: avatar with the channel badge, name, time, preview, unread count and
 * the row menu. Use it inside an app's own row to keep the menu and selection behaviour.
 */
export function ConversationRow({ row, selected, onSelect, config, groups, groupKey, onGroupChange, workspaceKey, appearance = {}, menu = {} }: ConversationRowProps) {
  const customer = string(row, "customerDisplay", string(row, "description", t("crm.chat.unknownCustomer")));
  const unread = Number(row.unreadCount ?? 0);
  const channel = conversationChannel(row);
  const badges = (appearance.badges ?? []).filter(badge => rowValue(row, badge.field) === badge.equals);
  // Host facets that opt into showOnRows (for example contact tags) arrive resolved with their colours.
  const facetBadges = Array.isArray(row.rowBadges) ? (row.rowBadges as {label?: unknown; color?: unknown}[])
    .filter(b => typeof b?.label === "string").map(b => ({label: String(b.label), color: typeof b.color === "string" && /^#[0-9a-fA-F]{6}$/.test(b.color) ? b.color : ""})) : [];
  const chipCount = badges.length + facetBadges.length;
  return (
    <ContactRowMenu record={row} workspaceKey={workspaceKey} groups={groups} groupKey={groupKey} conversationId={String(row.id)}
      onGroupChange={menu.groups === false ? undefined : onGroupChange} showOpen={menu.open !== false}
      id={String(row.customer ?? "")} canEdit={!!action(config, "edit")} onOpen={onSelect}>
    <Button variant="ghost"
      type="button"
      onClick={onSelect}
      aria-current={selected ? "true" : undefined}
      className={`h-auto whitespace-normal font-normal relative block min-w-0 w-full max-w-full rounded-field px-2 ${chipCount ? "py-2" : "py-3"} text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected
        ? "bg-primary/20"
        : "bg-transparent hover:bg-muted/50 after:absolute after:bottom-0 after:left-[68px] after:right-3 after:h-px after:bg-border/40 last:after:hidden"}`}
    >
      <ConversationRowContent
        avatar={config?.showAvatar !== false && <CrmAvatar name={customer} url={string(row, "customerAvatar")} channel={appearance.showChannelBadge !== false ? channel : undefined} className="size-12" />}
        title={config ? rowValue(row, config.listTitle) : customer}
        timestamp={config?.showTimestamps !== false && <time>{compactTime(row.lastMessageAt)}</time>}
        metadata={appearance.showAccount && string(row, "inboxDisplay", channel)}
        preview={config ? rowValue(row, config.listPreview) : string(row, "lastMessagePreview", t("crm.chat.noMessages"))}
        badges={chipCount > 0 ? <>
          {/* Like Telegram folder labels: a tinted chip normally, inverted to a solid light chip on the highlighted row. */}
          {facetBadges.map((badge,index)=><span key={`facet-${badge.label}-${index}`}
            className={`max-w-full min-w-0 shrink-0 truncate rounded px-1 text-[9px] leading-4 font-semibold uppercase tracking-wide ${selected ? "bg-white shadow-sm" : "bg-primary/15"} text-primary`}
            style={badge.color ? (selected ? {color:badge.color} : {backgroundColor:`${badge.color}26`, color:badge.color}) : undefined}>{badge.label}</span>)}
          {badges.map((badge,index)=><span key={`${badge.label}-${index}`} className={`max-w-full min-w-0 truncate rounded px-1 text-[9px] leading-4 font-medium uppercase text-primary ${selected ? "bg-white shadow-sm" : "bg-primary/15"}`}>{badge.label}</span>)}
        </> : null}
        previewLines={appearance.previewLines}
        trailing={unread > 0 && <span className="flex h-5 min-w-5 shrink-0 items-center justify-center rounded-full bg-primary px-1 text-xs font-medium text-primary-foreground">{unread > 99 ? "99+" : unread}</span>}
      />
    </Button>
    </ContactRowMenu>
  );
}

/** A component that can stand in for {@link ConversationRow}. */
export type ConversationRowComponent = ComponentType<ConversationRowProps>;
