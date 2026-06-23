import { useRecordViewers, usePanePresence } from "@/lib/presence-store";
import { PresenceAvatars } from "@/components/presence-avatars";

function idFromPath(path: string): string | null {
  const seg = path.split("/").filter(Boolean);
  return seg.length === 3 && (seg[0] === "catalogs" || seg[0] === "documents") ? seg[2] : null;
}

/**
 * Invisible: drives enter/heartbeat/leave for one pane from its active route. Mounted once per pane, it
 * owns only the lifecycle — the viewer data comes back through the presence store (snapshot + SSE).
 */
export function PanePresence({ path }: { path: string }) {
  usePanePresence(path);
  return null;
}

/** The focused pane's other viewers, as a stacked avatar pile pinned to the right of the tab bar. */
export function TabPresence({ path }: { path: string }) {
  const viewers = useRecordViewers(idFromPath(path));
  if (viewers.length === 0) return null;
  return <PresenceAvatars viewers={viewers} size={22} max={4} overlap />;
}
