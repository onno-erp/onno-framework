import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { DivKit, type DivKitProps } from "@divkitframework/react";
import "@divkitframework/divkit/dist/client.css";

/**
 * Generic DivKit surface renderer. Fetches the server-emitted DivKit card for
 * the current route from /api/ui/divkit/* and renders it with the official
 * DivKit React wrapper. Navigation/profile-switch intents arrive as onec://
 * action URLs (a non-builtin protocol, so DivKit routes them to onCustomAction)
 * and are mapped to react-router. This same component backs every ?renderer=divkit
 * route — the server owns the screens, the client just renders + routes.
 */
function withDivkit(path: string): string {
  return path.includes("?") ? `${path}&renderer=divkit` : `${path}?renderer=divkit`;
}

export function DivKitView() {
  const location = useLocation();
  const navigate = useNavigate();
  const [profile, setProfile] = useState<string | null>(null);
  const [card, setCard] = useState<DivKitProps["json"] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // location.pathname excludes the /ui basename: "/", "/catalogs/foo", etc.
  const endpoint = useMemo(() => {
    const p = location.pathname;
    if (p === "/" || p === "") {
      return profile
        ? `/api/ui/divkit/app?profile=${encodeURIComponent(profile)}`
        : "/api/ui/divkit/app";
    }
    return `/api/ui/divkit${p}`;
  }, [location.pathname, profile]);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    setCard(null);
    fetch(endpoint, { credentials: "include" })
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then((json) => {
        if (!cancelled) setCard(json as DivKitProps["json"]);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint]);

  const onCustomAction = useCallback(
    (action: { url?: string }) => {
      const url = action?.url;
      if (!url || !url.startsWith("onec://")) return;
      const rest = url.slice("onec://".length); // "app?profile=cleaning" | "documents/foo/id"
      if (rest.startsWith("app")) {
        const q = rest.indexOf("?");
        const params = new URLSearchParams(q >= 0 ? rest.slice(q + 1) : "");
        setProfile(params.get("profile"));
        if (location.pathname !== "/") navigate(withDivkit("/"));
        return;
      }
      navigate(withDivkit("/" + rest));
    },
    [navigate, location.pathname]
  );

  if (error) {
    return <div className="p-6 text-sm text-destructive">Failed to load DivKit surface: {error}</div>;
  }
  if (!card) {
    return <div className="p-6 text-sm text-muted-foreground">Loading…</div>;
  }

  return (
    <div className="p-4">
      <DivKit
        id={`onec:${endpoint}`}
        json={card}
        onCustomAction={onCustomAction as NonNullable<DivKitProps["onCustomAction"]>}
      />
    </div>
  );
}
