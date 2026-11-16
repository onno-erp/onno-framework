import { Button, text as t } from "@onno/ui";
import React from "react";
import {Inbox, Mail, Layers} from "@onno/ui/icons";
import {ChannelLogo} from "./ChannelLogo";

type Channel = {value:string;label:string};
type Account = {id:string;channel:string;label:string};
/** Authorized workspace metadata owns the available destinations, never the current page of rows. */
export function InboxChannelNavigation({channels,accounts,channel,account,showChannels,showAccounts,onChannel,onAccount}: {
  channels:Channel[];accounts:Account[];channel:string;account:string;
  showChannels:boolean;showAccounts:boolean;onChannel:(value:string)=>void;onAccount:(value:string)=>void;
}) {
  const row = "min-h-10 h-auto w-full justify-start gap-2.5 rounded-field px-3 py-2 text-left text-sm font-normal";
  return <div className="space-y-5" data-inbox-channel-navigation="">
    {showChannels && <fieldset className="min-w-0 space-y-2">
      <legend className="px-3 text-xs font-semibold text-muted-foreground">{t("crm.inbox.channels")}</legend>
      <div className="space-y-1">
        <Button className={row} variant={!channel ? "secondary" : "ghost"} aria-pressed={!channel} onClick={()=>{onChannel("");onAccount("");}}><Layers className="size-4 shrink-0" /><span className="min-w-0 truncate">{t("crm.inbox.allChannels")}</span></Button>
        {channels.map(item=><Button key={item.value} className={row} variant={channel===item.value?"secondary":"ghost"} aria-pressed={channel===item.value} onClick={()=>{onChannel(item.value);onAccount("");}}><span aria-hidden="true"><ChannelLogo channel={item.value} className="size-4" /></span><span className="min-w-0 truncate">{item.label}</span></Button>)}
      </div>
    </fieldset>}
    {showAccounts && <fieldset className="min-w-0 space-y-2">
      <legend className="px-3 text-xs font-semibold text-muted-foreground">{t("crm.inbox.accounts")}</legend>
      <div className="space-y-1">
        <Button className={row} variant={!account?"secondary":"ghost"} aria-pressed={!account} onClick={()=>onAccount("")}><Inbox className="size-4 shrink-0" /><span className="min-w-0 truncate">{t("crm.inbox.allAccounts")}</span></Button>
        {accounts.filter(item=>!channel||item.channel===channel).map(item=><Button key={item.id} className={row} variant={account===item.id?"secondary":"ghost"} aria-pressed={account===item.id} title={item.label} onClick={()=>onAccount(item.id)}><Mail className="size-4 shrink-0" /><span className="min-w-0 truncate">{item.label}</span></Button>)}
        {!accounts.some(item=>!channel||item.channel===channel) && <p className="px-3 text-xs leading-relaxed text-muted-foreground">{t("crm.inbox.noAccounts")}</p>}
      </div>
    </fieldset>}
  </div>;
}
