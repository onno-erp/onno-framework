import { Mail, MessageCircle, Phone, MessageSquare } from "lucide-react";

const logos: Record<string,string> = {
  telegram: "telegram.png", instagram: "instagram.png", whatsapp: "whatsapp.svg",
  gmail: "gmail.svg", outlook: "outlook.svg", "microsoft 365": "outlook.svg",
};
/** Branded channels use unmodified provider artwork; unbranded media retain neutral Onno symbols. */
export function ChannelLogo({channel,className="size-5"}:{channel:string;className?:string}) {
  const key=channel.trim().replaceAll("_"," ").toLowerCase();
  const file=logos[key];
  if(file)return <img src={`/crm/channels/${file}`} alt={channel} className={`${className} shrink-0 object-contain`} />;
  const Icon=key === "email" ? Mail : key === "phone" ? Phone : key === "internal" ? MessageSquare : MessageCircle;
  return <Icon aria-label={channel} className={`${className} shrink-0 text-muted-foreground`} strokeWidth={1.8} />;
}
