import { useEffect, useState } from "react";
import { Navigate, useLocation } from "react-router";
import { useAuth } from "../providers/auth-provider";
import { useTheme } from "../providers/theme-provider";
import { useBranding } from "../providers/branding-provider";
import { useMessages } from "../providers/messages-provider";
import { ArrowLeft, LockKeyhole } from "lucide-react";
import { LoginForm, type DemoAccount } from "../components/auth/login-form";
import { SsoIcon } from "../components/auth/sso-icon";
import { cn } from "../lib/utils";

/** The login screen description (`GET /api/ui/login`). */
type LoginMethods = {
  passwordEnabled: boolean;
  providers: { id: string; label: string; url: string; iconUrl?: string | null; monochrome?: boolean }[];
  mode: string;
  demoAccounts: DemoAccount[];
};

/** Sign-in error codes with a specific message; any other ?error value falls back to generic. */
const KNOWN_LOGIN_ERRORS = new Set(["telegram", "access_denied", "session_expired", "sso"]);

/**
 * The login screen. The server describes which methods are available (`GET /api/ui/login`): a
 * password form and/or one button per SSO provider. When both kinds are offered, login is two
 * steps — a method picker, then the password form. An SSO button is a full-page redirect to the
 * provider's start URL.
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

  const [methods, setMethods] = useState<LoginMethods | null>(null);
  const [failed, setFailed] = useState(false);
  const [step, setStep] = useState<"choose" | "password">("choose");

  useEffect(() => {
    let cancelled = false;
    fetch("/api/ui/login", { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((json: LoginMethods) => {
        if (!cancelled) setMethods(json);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Already signed in (e.g. landed here after an SSO round-trip) — go where we were headed.
  if (user) {
    return <Navigate to={from} replace />;
  }

  return (
    <main className="flex min-h-screen bg-background">
      <section className="hidden flex-1 flex-col justify-between px-12 py-10 md:flex">
        {logo ? (
          // Honor the configured logo size (px) the same way the shell does — a fixed
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

      {/* Only the right sign-in area is an island: a large rounded panel (shape token), bordered and
          inset by the page gutter, no shadow — matching the shell's notification/panel islands. The
          left hero stays flush on the page background. */}
      <section className="flex w-full items-stretch p-3 md:w-[500px]">
        <div className="flex w-full flex-col items-center justify-center rounded-card border border-border bg-card px-6 py-10">
          <div className="w-full max-w-sm">
          {errorMessage && (
            <div
              role="alert"
              className="mb-4 rounded-field border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
            >
              {errorMessage}
            </div>
          )}
          {failed ? (
            <p className="text-sm text-destructive">Couldn't load the sign-in screen. Try refreshing.</p>
          ) : methods ? (
            <LoginMethodsView methods={methods} step={step} onStep={setStep} theme={resolved} />
          ) : (
            <p className="text-sm text-muted-foreground">Loading…</p>
          )}
          </div>
        </div>
      </section>
    </main>
  );
}

function LoginMethodsView({ methods, step, onStep, theme }: {
  methods: LoginMethods;
  step: "choose" | "password";
  onStep: (step: "choose" | "password") => void;
  theme: "light" | "dark";
}) {
  const t = useMessages();
  const hasPassword = methods.passwordEnabled;
  const hasSso = methods.providers.length > 0;
  const picker = hasPassword && hasSso;
  const passwordStep = picker && step === "password";
  const showPasswordForm = passwordStep || (hasPassword && !picker);
  const subtitle = picker && !passwordStep ? "login.subtitle.choose"
    : showPasswordForm ? "login.subtitle.password" : "login.subtitle.sso";
  const accent = getComputedStyle(document.documentElement).getPropertyValue("--primary") ? "hsl(var(--primary))" : undefined;
  void theme;
  return (
    <div className="flex w-full flex-col gap-3 px-1.5">
      {passwordStep ? (
        <button type="button" onClick={() => onStep("choose")}
          className="flex w-fit items-center gap-1.5 text-[13px] font-medium text-muted-foreground">
          <ArrowLeft className="size-4" /> {t("login.back")}
        </button>
      ) : null}
      <LockKeyhole className="size-6" />
      <h1 className="text-2xl font-medium">{t("login.title")}</h1>
      <p className="mb-2 text-[13px] text-muted-foreground">{t(subtitle)}</p>
      {showPasswordForm ? <LoginForm demoAccounts={methods.demoAccounts} /> : null}
      {picker && !passwordStep ? (
        <MethodButton primary label={t("login.method.password")} onClick={() => onStep("password")}
          icon={<LockKeyhole className="size-[18px]" />} />
      ) : null}
      {hasSso && !passwordStep
        ? methods.providers.map((p, i) => {
            const primary = !hasPassword && i === 0;
            return (
              <MethodButton key={p.id} primary={primary} label={p.label}
                onClick={() => {
                  if (p.url.startsWith("/")) window.location.href = p.url;
                }}
                icon={p.iconUrl ? (
                  <SsoIcon src={p.iconUrl} monochrome={p.monochrome}
                    color={p.monochrome ? (primary ? "hsl(var(--background))" : accent) : undefined} />
                ) : null} />
            );
          })
        : null}
      {!hasPassword && !hasSso ? <p className="text-[13px] text-muted-foreground">{t("login.none")}</p> : null}
    </div>
  );
}

/** A full-width login method button: a centered label with an optional mark on the right. */
function MethodButton({ label, icon, primary, onClick }: {
  label: string; icon?: React.ReactNode; primary?: boolean; onClick: () => void;
}) {
  return (
    <button type="button" onClick={onClick}
      className={cn("flex w-full items-center gap-2 rounded-control px-3.5 py-3 text-sm font-medium",
        primary ? "bg-primary text-primary-foreground" : "border border-border bg-card text-foreground")}>
      {icon ? <span className="size-[18px]" /> : null}
      <span className="flex-1 truncate text-center">{label}</span>
      {icon ? <span className="grid size-[18px] place-items-center">{icon}</span> : null}
    </button>
  );
}
