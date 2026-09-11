import {Globe, MessageCircle, MessageSquare} from "lucide-react";
import {useEffect,useState} from "@onno/widget-sdk";
type Channel = {key:string;label:string;iconUrl:string};
let definitions:Promise<Channel[]> | undefined;
function load() {
  return definitions ??= fetch("/api/crm/channels/types",{credentials:"same-origin"}).then(async r=>{
    if(!r.ok)throw new Error("Channel metadata unavailable");return r.json();
  }).catch(()=>{definitions=undefined;return [];});
}
const builtins: Record<string, {label:string;iconUrl:string}> = {
  email:{label:"Gmail",iconUrl:"/crm/channel-icons/gmail.svg"},
  gmail:{label:"Gmail",iconUrl:"/crm/channel-icons/gmail.svg"},
  instagram:{label:"Instagram",iconUrl:"/crm/channel-icons/instagram.png"},
  whatsapp:{label:"WhatsApp",iconUrl:"/crm/channel-icons/whatsapp.svg"},
};
/** Connector branding takes precedence; familiar channels retain icons without an installed connector. */
export function ChannelLogo({channel,className="size-5"}:{channel:string;className?:string}) {
  const [channels,setChannels]=useState<Channel[]>([]);
  useEffect(()=>{let live=true;void load().then(items=>{if(live)setChannels(items);});return()=>{live=false;};},[]);
  const definition=channels.find(item=>item.key.toLowerCase()===channel.toLowerCase() && item.iconUrl) || builtins[channel.toLowerCase()];
  if(definition?.iconUrl)return <img src={definition.iconUrl} alt={definition.label} className={`${className} shrink-0 object-contain`} />;
  const Icon=channel.toLowerCase()==="internal"?MessageSquare:channel.toLowerCase()==="web_chat"?Globe:MessageCircle;
  return <Icon aria-label={definition?.label || channel} className={`${className} shrink-0 text-muted-foreground`} strokeWidth={1.8} />;
}
