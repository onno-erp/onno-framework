import { useEffect, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "@/providers/auth-provider";
import { useTheme } from "@/providers/theme-provider";
import { useBranding } from "@/providers/branding-provider";
import { useMessages } from "@/providers/messages-provider";
import { DivKitContent, type ContentAction, type ContentCard } from "@/views/divkit-content";
import { LoginFormPortals } from "@/lib/login-form-bridge";
import { IconPortals } from "@/lib/icon-bridge";
import { SsoIconPortals } from "@/lib/sso-icon-bridge";

/** Sign-in error codes with a specific message; any other ?error value falls back to generic. */
const KNOWN_LOGIN_ERRORS = new Set(["telegram", "access_denied", "session_expired", "sso"]);

/**
 * The login screen is server-driven, like the rest of the app: the server emits a DivKit card
 * (GET /api/divkit/login) describing the available methods — a password sub-form (the
 * `onno-login-form` custom block) and/or one SSO button per OIDC provider — and we render it here.
 * The password form posts credentials through the auth context; an SSO button taps an
 * `onno://auth/sso/{id}` action which we turn into a full-page redirect to the authorization
 * endpoint.
 */
export function LoginView() {
  const { user } = useAuth();
  const location = useLocation();
  const { theme } = useTheme();
  const branding = useBranding();
  const t = useMessages();
  const resolved: "light" | "dark" =
    theme === "dark" || theme === "light"
      ? theme
      : window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
  // The login mark: a configured logo (dark variant in dark mode), else the app name, else "onno".
  const logo = (resolved === "dark" && branding.logoUrlDark) || branding.logoUrl;
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/";

  // An explicit sign-in error, carried back as ?error=<code> (e.g. a failed Telegram/SSO round-trip
  // redirects to /login?error=telegram). Known codes get a specific message; anything else falls
  // back to a generic one. All messages are overridable via onno.ui.messages.login.error.*.
  const errorCode = new URLSearchParams(location.search).get("error");
  const errorMessage = errorCode
    ? t(KNOWN_LOGIN_ERRORS.has(errorCode) ? `login.error.${errorCode}` : "login.error.generic")
    : null;

  const [card, setCard] = useState<ContentCard>(null);
  const [failed, setFailed] = useState(false);
  // When the server offers several kinds of method it splits login into two steps: a method picker
  // (default) and the password credentials step. Selecting a method re-requests the card for that
  // step; a single-kind screen ignores `step` and renders inline. (Re-fetching keeps the server the
  // single source of truth for what each step looks like.)
  const [step, setStep] = useState<"choose" | "password">("choose");

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    const q = step === "password" ? `&step=password` : "";
    fetch(`/api/divkit/login?theme=${resolved}${q}`, { credentials: "include" })
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
  }, [resolved, step]);

  function onAction(action: ContentAction) {
    const url = action?.url;
    if (!url || !url.startsWith("onno://")) return;
    const rest = url.slice("onno://".length);
    if (rest === "auth/password") {
      // Advance to the credentials step.
      setStep("password");
      return;
    }
    if (rest === "auth/back") {
      // Back to the method picker.
      setStep("choose");
      return;
    }
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
          <div className="text-sm font-semibold">{branding.appName ?? "onno"}</div>
        )}
        <div className="max-w-md">
          <p className="text-3xl font-semibold tracking-tight">{t("login.hero.title")}</p>
          <p className="mt-4 text-sm leading-6 text-muted-foreground">{t("login.hero.subtitle")}</p>
        </div>
        <p className="text-xs text-muted-foreground">{t("login.footer")}</p>
      </section>

      <section className="flex min-h-screen w-full items-center justify-center px-5 md:w-[440px]">
        <div className="w-full max-w-sm">
          {errorMessage && (
            <div
              role="alert"
              className="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
            >
              {errorMessage}
            </div>
          )}
          {failed ? (
            <p className="text-sm text-destructive">Couldn't load the sign-in screen. Try refreshing.</p>
          ) : card ? (
            <DivKitContent
              surfaceKey={`login:${resolved}:${step}`}
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
      <IconPortals />
      <SsoIconPortals />
    </main>
  );
}
