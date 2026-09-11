import { useState } from "@onno/widget-sdk";

/** Stable abstract fallback without putting contact names in the external image URL. */
export function glassAvatar(seed: string) {
  let hash = 2166136261;
  for (const character of seed.trim() || "unknown") hash = Math.imul(hash ^ character.charCodeAt(0), 16777619);
  return `https://api.dicebear.com/10.x/glass/svg?seed=${(hash >>> 0).toString(16)}`;
}

export function ContactAvatarImage({name, url}: {name:string; url?:string|null}) {
  const [failed, setFailed] = useState<string[]>([]);
  const fallback = glassAvatar(name);
  const source = url && !failed.includes(url) ? url : fallback;
  if (failed.includes(source)) return null;
  return <img src={source} alt="" referrerPolicy="no-referrer"
    className={`absolute inset-0 size-full object-cover ${source === fallback ? "scale-110 blur-[2px]" : ""}`}
    onError={() => setFailed(previous => [...previous, source])} />;
}
