export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export function tint(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  return `hsl(${Math.abs(hash) % 360} 55% 45%)`;
}

export function glassAvatar(seed: string | null | undefined): string {
  const safeSeed = encodeURIComponent((seed || "unknown").trim() || "unknown");
  return `https://api.dicebear.com/10.x/glass/svg?seed=${safeSeed}`;
}
