import {MessageCircle, MessageSquare} from "lucide-react";
import {useEffect,useState} from "@onno/widget-sdk";
type Channel = {key:string;label:string;iconUrl:string};
let definitions:Promise<Channel[]> | undefined;
function load() {
  return definitions ??= fetch("/api/crm/channels/types",{credentials:"same-origin"}).then(async r=>{
    if(!r.ok)throw new Error("Channel metadata unavailable");return r.json();
  }).catch(()=>{definitions=undefined;return [];});
}
/** Branding comes from installed connector metadata; unknown keys remain readable. */
export function ChannelLogo({channel,className="size-5"}:{channel:string;className?:string}) {
  const [channels,setChannels]=useState<Channel[]>([]);
  useEffect(()=>{let live=true;void load().then(items=>{if(live)setChannels(items);});return()=>{live=false;};},[]);
  const definition=channels.find(item=>item.key.toLowerCase()===channel.toLowerCase());
  if(definition?.iconUrl)return <img src={definition.iconUrl} alt={definition.label} className={`${className} shrink-0 object-contain`} />;
  const Icon=channel.toLowerCase()==="internal"?MessageSquare:MessageCircle;
  return <Icon aria-label={definition?.label || channel} className={`${className} shrink-0 text-muted-foreground`} strokeWidth={1.8} />;
}
