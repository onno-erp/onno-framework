import { useCallback, useEffect, useRef, useState } from "react";
import { api, type PresenceViewer } from "@/lib/api";
import type { UiEvent } from "@/lib/types";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";

/** The record a presence bar tracks — the same triple the detail route uses. */
export type PresenceTarget = { kind: "catalogs" | "documents"; name: string; id: string };

// Must stay comfortably under the server's viewer TTL (≈3× this) so an active viewer never expires.
const HEARTBEAT_MS = 15_000;
const MAX_AVATARS = 5;

// Up to two initials from a display name, for a viewer avatar.
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

// Deterministic avatar tint from the user id, so the same person keeps the same colour everywhere.
function tint(userId: string): string {
  let hash = 0;
  for (let i = 0; i < userId.length; i++) hash = (hash * 31 + userId.charCodeAt(i)) | 0;
  return `hsl(${Math.abs(hash) % 360} 55% 45%)`;
}

/**
 * Record-level presence markers: the avatars of everyone *else* currently viewing this record, like the
 * collaborator dots in a shared document. Renders nothing when you're alone, so a solo viewer sees an
 * empty bar.
 *
 * <p>Lifecycle: {@code enter} on mount, {@code heartbeat} on an interval, {@code leave} on unmount. The
 * server pushes the full viewer set over SSE whenever it changes (a join or a leave); the heartbeat
 * response doubles as a resync in case an SSE event was missed. The caller filters itself out via the
 * {@code you} id the enter response returns.
 */
export function PresenceBar({ target }: { target: PresenceTarget }) {
  const { kind, name, id } = target;
  const [viewers, setViewers] = useState<PresenceViewer[]>([]);
  const meRef = useRef<string | null>(null);

  // Markers show *other* people — drop the caller themselves from any viewer set.
  const others = useCallback(
    (list: PresenceViewer[]) => list.filter((v) => v.userId !== meRef.current),
    []
  );

  useEffect(() => {
    let active = true;
    api
      .presence(kind, name, id, "enter")
      .then((state) => {
        if (!active) return;
        meRef.current = state.you;
        setViewers(state.viewers.filter((v) => v.userId !== state.you));
      })
      .catch(() => {
        /* read-gated or offline — just show nothing */
      });

    const beat = window.setInterval(() => {
      api
        .presence(kind, name, id, "heartbeat")
        .then((state) => {
          if (active) setViewers(others(state.viewers));
        })
        .catch(() => {});
    }, HEARTBEAT_MS);

    return () => {
      active = false;
      window.clearInterval(beat);
      meRef.current = null;
      setViewers([]);
      api.leavePresence(kind, name, id);
    };
  }, [kind, name, id, others]);

  // Live updates ride the shared `onno:dataevent` fan-out (divkit-view's single SSE stream) the other
  // island widgets use, rather than opening a second stream. The id is globally unique so it scopes the
  // record; the event's entityType is the "presence" sentinel, so it never collides with row changes.
  useEffect(() => {
    const onData = (e: Event) => {
      const ev = (e as CustomEvent<UiEvent>).detail;
      if (ev?.type === "presence" && ev.entityName === name && ev.id === id) {
        setViewers(others(ev.viewers ?? []));
      }
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
  }, [name, id, others]);

  if (viewers.length === 0) return null;

  const shown = viewers.slice(0, MAX_AVATARS);
  const overflow = viewers.length - shown.length;

  return (
    <div
      className="flex items-center gap-2 text-xs text-muted-foreground"
      role="status"
      aria-label={`${viewers.length} other ${viewers.length === 1 ? "person" : "people"} viewing this record`}
    >
      <div className="flex -space-x-2">
        {shown.map((v) => (
          <Avatar key={v.userId} className="h-6 w-6 border-2 border-background" title={v.displayName}>
            <AvatarFallback className="text-[10px] text-white" style={{ backgroundColor: tint(v.userId) }}>
              {initials(v.displayName)}
            </AvatarFallback>
          </Avatar>
        ))}
        {overflow > 0 && (
          <Avatar className="h-6 w-6 border-2 border-background" title={`+${overflow} more`}>
            <AvatarFallback className="text-[10px]">+{overflow}</AvatarFallback>
          </Avatar>
        )}
      </div>
      <span>also here</span>
    </div>
  );
}
