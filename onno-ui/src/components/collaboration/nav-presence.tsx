import { PresenceAvatars } from "./presence-avatars";
import { useEntityViewers } from "../../lib/presence-store";

/**
 * Map a nav route path to the (kind, name) the presence store aggregates by — mirroring the server's
 * route identity: a catalogs/documents route resolves to its entity (so the nav item unions everyone on
 * the list and on any of its records); every other route is a "page" keyed by its normalized path.
 */
function navIdentity(path: string): { kind: string; name: string } {
  const seg = path.split("/").filter(Boolean);
  if (seg.length >= 2 && (seg[0] === "catalogs" || seg[0] === "documents")) {
    return { kind: seg[0], name: seg[1] };
  }
  return { kind: "page", name: "/" + seg.join("/") };
}

/** A compact face-pile of the route's viewers — up to three tiny avatars, pinned to the slot's right. */
export function NavPresenceIndicator({ path }: { path: string }) {
  const { kind, name } = navIdentity(path);
  const viewers = useEntityViewers(kind, name);
  return <PresenceAvatars viewers={viewers} size={20} max={3} overlap className="h-full w-full justify-end" />;
}
