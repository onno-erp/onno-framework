import { useRecordViewers } from "../../lib/presence-store";
import { PresenceAvatars } from "./presence-avatars";

function idFromPath(path: string): string | null {
  const seg = path.split("/").filter(Boolean);
  return seg.length === 3 && (seg[0] === "catalogs" || seg[0] === "documents") ? seg[2] : null;
}

/** The focused pane's other viewers, as a stacked avatar pile pinned to the right of the tab bar. */
export function TabPresence({ path }: { path: string }) {
  const viewers = useRecordViewers(idFromPath(path));
  if (viewers.length === 0) return null;
  return <PresenceAvatars viewers={viewers} size={22} max={4} overlap />;
}
