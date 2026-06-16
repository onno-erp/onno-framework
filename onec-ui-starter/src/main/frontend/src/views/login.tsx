import { useEffect, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "@/providers/auth-provider";
import { useTheme } from "@/providers/theme-provider";
import { useBranding } from "@/providers/branding-provider";
import { DivKitContent, type ContentAction, type ContentCard } from "@/views/divkit-content";
import { LoginFormPortals } from "@/lib/login-form-bridge";
import { MagicLinkPortals } from "@/lib/magic-link-bridge";
import { IconPortals } from "@/lib/icon-bridge";

/**
 * The login screen is server-driven, like the rest of the app: the server emits a DivKit card
 * (GET /api/divkit/login) describing the available methods — a password sub-form (the
 * `onec-login-form` custom block) and/or one SSO button per OIDC provider — and we render it here.
 * The password form posts credentials through the auth context; an SSO button taps an
 * `onec://auth/sso/{id}` action which we turn into a full-page redirect to the authorization
 * endpoint.
 */
export function LoginView() {
  const { user } = useAuth();
  const location = useLocation();
  const { theme } = useTheme();
  const branding = useBranding();
  const resolved: "light" | "dark" =
    theme === "dark" || theme === "light"
      ? theme
      : window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
  // The login mark: a configured logo (dark variant in dark mode), else the app name, else "onec".
  const logo = (resolved === "dark" && branding.logoUrlDark) || branding.logoUrl;
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/";

  const [card, setCard] = useState<ContentCard>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    fetch(`/api/divkit/login?theme=${resolved}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((json) => {
        if (!cancelled) setCard(json as ContentCard);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [resolved]);

  function onAction(action: ContentAction) {
    const url = action?.url;
    if (!url || !url.startsWith("onec://")) return;
    const rest = url.slice("onec://".length);
    if (rest.startsWith("auth/sso/")) {
      // Full-page redirect to the IdP — the server-side authorization-code flow returns to the SPA
      // shell, where /api/auth/me reflects the new session. The server carries the destination as
      // `?to=` so a non-OIDC provider (e.g. a Telegram login flow) points at its own start URL; we
      // fall back to the OIDC `/oauth2/authorization/{id}` convention when none is supplied. The
      // `to.startsWith("/")` guard keeps the redirect same-origin.
      const tail = rest.slice("auth/sso/".length);
      const q = tail.indexOf("?");
      const id = q >= 0 ? tail.slice(0, q) : tail;
      const to = q >= 0 ? new URLSearchParams(tail.slice(q + 1)).get("to") : null;
      const dest = to && to.startsWith("/") ? to : id ? `/oauth2/authorization/${id}` : null;
      if (dest) window.location.href = dest;
    }
  }

  // Already signed in (e.g. landed here after an SSO round-trip) — go where we were headed.
  if (user) {
    return <Navigate to={from} replace />;
  }

  return (
    <main className="flex min-h-screen bg-background">
      <section className="hidden flex-1 border-r border-border bg-muted/30 px-12 py-10 md:flex md:flex-col md:justify-between">
        {logo ? (
          // Honor the configured logo size (px) the same way the DivKit shell does — a fixed
          // box with object-fit: contain stays uncropped; unset falls back to h-7 / intrinsic width.
          <img
            src={logo}
            alt={branding.appName ?? "Logo"}
            className="self-start"
            style={{
              height: branding.logoHeight != null ? `${branding.logoHeight}px` : "1.75rem",
              width: branding.logoWidth != null ? `${branding.logoWidth}px` : "auto",
              objectFit: "contain",
            }}
          />
        ) : (
          <div className="text-sm font-semibold">{branding.appName ?? "onec"}</div>
        )}
        <div className="max-w-md">
          <p className="text-3xl font-semibold tracking-tight">Business apps shaped around roles.</p>
          <p className="mt-4 text-sm leading-6 text-muted-foreground">
            Sign in to see the catalogs, documents, dashboards, and forms your role is allowed to use.
          </p>
        </div>
        <p className="text-xs text-muted-foreground">Server-driven sign-in.</p>
      </section>

      <section className="flex min-h-screen w-full items-center justify-center px-5 md:w-[440px]">
        <div className="w-full max-w-sm">
          {failed ? (
            <p className="text-sm text-destructive">Couldn't load the sign-in screen. Try refreshing.</p>
          ) : card ? (
            <DivKitContent
              surfaceKey={`login:${resolved}`}
              card={card}
              theme={resolved}
              onAction={onAction}
            />
          ) : (
            <p className="text-sm text-muted-foreground">Loading…</p>
          )}
        </div>
      </section>

      {/* DivKit custom blocks on the card mount their React widgets through these portals. */}
      <LoginFormPortals />
      <MagicLinkPortals />
      <IconPortals />
    </main>
  );
}
