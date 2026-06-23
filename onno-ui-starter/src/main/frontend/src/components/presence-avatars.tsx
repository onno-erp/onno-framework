import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";
import type { PresenceViewer } from "@/lib/api";

const MAX_AVATARS = 5;

/** Up to two initials from a display name. */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

/** Deterministic avatar tint from the user id, so the same person keeps the same colour everywhere. */
export function tint(userId: string): string {
  let hash = 0;
  for (let i = 0; i < userId.length; i++) hash = (hash * 31 + userId.charCodeAt(i)) | 0;
  return `hsl(${Math.abs(hash) % 360} 55% 45%)`;
}

/**
 * A horizontal stack of collaborator avatars — deterministic per-user colour, initials, and a `+N`
 * overflow chip. Renders nothing when the list is empty. Shared by every ambient-presence surface (the
 * tab bar, list rows, and sidebar), so they all look identical and a person keeps one colour throughout.
 */
export function PresenceAvatars({
  viewers,
  size = 24,
  label,
  className,
}: {
  viewers: PresenceViewer[];
  size?: number;
  label?: string;
  className?: string;
}) {
  if (viewers.length === 0) return null;
  const shown = viewers.slice(0, MAX_AVATARS);
  const overflow = viewers.length - shown.length;
  const dim = { width: size, height: size };
  const fontSize = Math.max(8, Math.round(size * 0.42));

  return (
    <div
      className={cn("flex items-center gap-2 text-xs text-muted-foreground", className)}
      role="status"
      aria-label={`${viewers.length} ${viewers.length === 1 ? "person" : "people"} viewing`}
    >
      <div className="flex -space-x-1.5">
        {shown.map((v) => (
          <Avatar key={v.userId} className="border-2 border-background" style={dim} title={v.displayName}>
            <AvatarFallback className="text-white" style={{ backgroundColor: tint(v.userId), fontSize }}>
              {initials(v.displayName)}
            </AvatarFallback>
          </Avatar>
        ))}
        {overflow > 0 && (
          <Avatar className="border-2 border-background" style={dim} title={`+${overflow} more`}>
            <AvatarFallback style={{ fontSize }}>+{overflow}</AvatarFallback>
          </Avatar>
        )}
      </div>
      {label ? <span className="whitespace-nowrap">{label}</span> : null}
    </div>
  );
}
