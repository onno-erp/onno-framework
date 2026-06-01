import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { DivKit, type DivKitProps } from "@divkitframework/react";
import { useAuth } from "@/providers/auth-provider";
import { useTheme } from "@/providers/theme-provider";
import "@divkitframework/divkit/dist/client.css";

/**
 * The authenticated app, rendered as two DivKit cards so navigation never blanks:
 * the chrome (/shell — top bar + nav, no data) paints instantly, and the per-route
 * content (/home, /catalogs/..., ...) streams in beneath it behind a skeleton.
 * The client only routes: onec:// action URLs become navigation, persona switches,
 * theme toggle, and sign-out. viewport + theme are sent so layout/colors are chosen
 * server-side — the same hooks a Flutter client would use.
 */
const MOBILE_BREAKPOINT = 768;

export function DivKitView() {
  const location = useLocation();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const { theme, setTheme } = useTheme();
  const [profile, setProfile] = useState<string | null>(null);
  const [mobile, setMobile] = useState(() => window.innerWidth < MOBILE_BREAKPOINT);
  const [shellCard, setShellCard] = useState<DivKitProps["json"] | null>(null);
  const [contentCard, setContentCard] = useState<DivKitProps["json"] | null>(null);
  const [contentError, setContentError] = useState<string | null>(null);

  const resolvedTheme = useMemo<"light" | "dark">(() => {
    if (theme === "dark" || theme === "light") return theme;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }, [theme]);

  useEffect(() => {
    const onResize = () => setMobile(window.innerWidth < MOBILE_BREAKPOINT);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  const shellEndpoint = useMemo(() => {
    const qs = new URLSearchParams();
    if (mobile) qs.set("viewport", "mobile");
    qs.set("theme", resolvedTheme);
    qs.set("active", location.pathname);
    if (profile) qs.set("profile", profile);
    return `/api/divkit/shell?${qs.toString()}`;
  }, [mobile, resolvedTheme, location.pathname, profile]);

  const contentEndpoint = useMemo(() => {
    const path = location.pathname;
    const isHome = path === "/" || path === "";
    const base = isHome ? "/api/divkit/home" : `/api/divkit${path}`;
    const qs = new URLSearchParams();
    if (mobile) qs.set("viewport", "mobile");
    qs.set("theme", resolvedTheme);
    if (isHome && profile) qs.set("profile", profile);
    return `${base}?${qs.toString()}`;
  }, [location.pathname, mobile, resolvedTheme, profile]);

  // Chrome: fast, no entity data — paints immediately.
  useEffect(() => {
    let cancelled = false;
    fetch(shellEndpoint, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((json) => {
        if (!cancelled) setShellCard(json as DivKitProps["json"]);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [shellEndpoint]);

  // Content: the data-bearing part — show a skeleton until it lands.
  useEffect(() => {
    let cancelled = false;
    setContentCard(null);
    setContentError(null);
    fetch(contentEndpoint, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((json) => {
        if (!cancelled) setContentCard(json as DivKitProps["json"]);
      })
      .catch((e: unknown) => {
        if (!cancelled) setContentError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [contentEndpoint]);

  const onCustomAction = useCallback(
    (action: { url?: string }) => {
      const url = action?.url;
      if (!url || !url.startsWith("onec://")) return;
      const rest = url.slice("onec://".length); // "logout" | "theme/toggle" | "app?profile=x" | "documents/foo/id"
      if (rest === "logout") {
        logout().finally(() => navigate("/login"));
        return;
      }
      if (rest === "theme/toggle") {
        setTheme(resolvedTheme === "dark" ? "light" : "dark");
        return;
      }
      if (rest.startsWith("app")) {
        const q = rest.indexOf("?");
        const params = new URLSearchParams(q >= 0 ? rest.slice(q + 1) : "");
        setProfile(params.get("profile"));
        if (location.pathname !== "/") navigate("/");
        return;
      }
      navigate("/" + rest);
    },
    [navigate, location.pathname, logout, setTheme, resolvedTheme]
  );

  const pageBg = resolvedTheme === "dark" ? "#0A0A0A" : "#FFFFFF";
  const skeletonBg = resolvedTheme === "dark" ? "#1F1F1F" : "#F5F5F5";

  return (
    <div className="min-h-screen w-full overflow-x-hidden" style={{ background: pageBg }}>
      {shellCard ? (
        <DivKit
          id={`shell:${resolvedTheme}:${mobile ? "m" : "d"}`}
          json={shellCard}
          theme={resolvedTheme}
          onCustomAction={onCustomAction as NonNullable<DivKitProps["onCustomAction"]>}
        />
      ) : null}
      <div className="px-4 py-4 sm:px-7 sm:py-6">
        {contentError ? (
          <div className="text-sm text-destructive">Failed to load: {contentError}</div>
        ) : contentCard ? (
          <DivKit
            id={`content:${resolvedTheme}:${mobile ? "m" : "d"}:${location.pathname}`}
            json={contentCard}
            theme={resolvedTheme}
            onCustomAction={onCustomAction as NonNullable<DivKitProps["onCustomAction"]>}
          />
        ) : (
          <div className="flex flex-col gap-3">
            <div className="h-8 w-48 rounded-md" style={{ background: skeletonBg }} />
            <div className="h-64 w-full rounded-xl" style={{ background: skeletonBg }} />
          </div>
        )}
      </div>
    </div>
  );
}
