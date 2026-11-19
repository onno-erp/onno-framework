import { useCallback, useMemo, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, LockKeyhole } from "lucide-react";
import { api, ApiError } from "../../lib/api";
import { cn } from "../../lib/utils";
import { useAuth } from "../../providers/auth-provider";
import { useBranding } from "../../providers/branding-provider";
import { useMessages } from "../../providers/messages-provider";
import { useTheme } from "../../providers/theme-provider";
import { LoginForm, type DemoAccount } from "./login-form";
import { SsoIcon } from "./sso-icon";

/** An SSO option on the login screen: a full-page redirect to `url` (OIDC/Keycloak, Telegram, …). */
export type SsoOption = {
  id: string;
  label: string;
  url: string;
  iconUrl?: string | null;
  monochrome?: boolean;
};

/** What the server offers on the login screen (`GET /api/ui/login`). */
export type LoginMethods = {
  passwordEnabled: boolean;
  providers: SsoOption[];
  /** The auth backend: `in-memory`, `database`, `oidc` or `resource-server`. */
  mode: string;
  demoAccounts: DemoAccount[];
};

/** Props of an app's own login page (`defineApp({ login: MyLogin })`). */
export type LoginPageProps = {
  /** The server's login methods; null while loading or when they could not be loaded. */
  methods: LoginMethods | null;
  /** A sign-in error carried back in `?error=` (e.g. a failed SSO round-trip), already localized. */
  error: string | null;
  /** Where the user was headed; sign-in returns there. */
  from: string;
};

/** A refused sign-in. `message` is fit to show; `status` is the HTTP status (401 wrong credentials). */
export class SignInError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = "SignInError";
  }
}

/** The sign-in actions a login page calls. Each one that signs in returns the user to `from`. */
export type SignIn = {
  /** Where the user was headed before the login page. */
  from: string;
  /** The password form's sign-in. Rejects with a {@link SignInError} on wrong credentials. */
  password: (username: string, password: string, options?: { remember?: boolean }) => Promise<void>;
  /**
   * Sign in through an application `LoginMethod` bean (`POST /api/auth/login/{method}`), e.g.
   * `method("code", { email, code })`. Rejects with the method's own message.
   */
  method: (method: string, credentials: Record<string, unknown>) => Promise<void>;
  /** A `LoginMethod`'s first step, e.g. `challenge("code", { email })` to send the code. */
  challenge: <T = Record<string, unknown>>(method: string, input: Record<string, unknown>) => Promise<T>;
  /** Start an SSO flow: a full-page redirect to the provider (an option from `methods.providers`, or its URL). */
  redirect: (provider: SsoOption | string) => void;
};

/** The server's login methods (`GET /api/ui/login`), for a custom login page. */
export function useLoginMethods() {
  return useQuery({
    queryKey: ["onno", "login-methods"],
    queryFn: async (): Promise<LoginMethods> => {
      const res = await fetch("/api/ui/login", { credentials: "include" });
      if (!res.ok) throw new Error(String(res.status));
      return res.json();
    },
    staleTime: Infinity,
    retry: 1,
  });
}

/** Where the login page returns to after sign-in (the page that sent the user here). */
function useFrom(): string {
  const location = useLocation();
  return (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/";
}

/**
 * The sign-in actions for a login page — the default one or an app's own: password, an
 * application `LoginMethod` (one-time code, magic link, …), or an SSO redirect. Successful
 * sign-in establishes the session and returns to the page the user was headed to.
 *
 *   const signIn = useSignIn();
 *   await signIn.challenge("code", { email });          // the server sends a code
 *   await signIn.method("code", { email, code });       // signs in, navigates back
 */
export function useSignIn(): SignIn {
  const { login, loginWith } = useAuth();
  const t = useMessages();
  const navigate = useNavigate();
  const from = useFrom();

  const refused = useCallback((err: unknown, fallback: string): SignInError => {
    if (err instanceof ApiError) {
      // A bare status line means the server sent no message of its own.
      const own = err.message && !/^\d{3}\b/.test(err.message) ? err.message : null;
      return new SignInError(own ?? fallback, err.status);
    }
    return new SignInError(t("login.error.generic"));
  }, [t]);

  const password = useCallback(async (username: string, pass: string, options?: { remember?: boolean }) => {
    try {
      await login(username, pass, options?.remember);
    } catch (err) {
      throw refused(err, t("login.invalid"));
    }
    navigate(from, { replace: true });
  }, [login, navigate, from, refused, t]);

  const method = useCallback(async (id: string, credentials: Record<string, unknown>) => {
    try {
      await loginWith(id, credentials);
    } catch (err) {
      throw refused(err, t("login.error.generic"));
    }
    navigate(from, { replace: true });
  }, [loginWith, navigate, from, refused, t]);

  const challenge = useCallback(async <T,>(id: string, input: Record<string, unknown>): Promise<T> => {
    try {
      return (await api.loginChallenge(id, input)) as T;
    } catch (err) {
      throw refused(err, t("login.error.generic"));
    }
  }, [refused, t]);

  const redirect = useCallback((provider: SsoOption | string) => {
    const url = typeof provider === "string" ? provider : provider.url;
    // Same-origin starts only (the server's /oauth2/authorization/… or a connector's own path).
    if (url.startsWith("/") && !url.startsWith("//")) window.location.href = url;
  }, []);

  return useMemo(() => ({ from, password, method, challenge, redirect }), [from, password, method, challenge, redirect]);
}

/**
 * The login screen's frame: the branded hero on the left (logo, headline, footer) and the sign-in
 * card on the right, holding `children`. Compose a custom login page inside it to keep the app's
 * look, or render something else entirely.
 */
export function LoginLayout({ children, title, subtitle, footer, error }: {
  children: ReactNode;
  /** The hero headline; defaults to the `login.hero.title` message. */
  title?: ReactNode;
  /** The hero text under the headline; defaults to `login.hero.subtitle`. */
  subtitle?: ReactNode;
  /** The hero footer; defaults to `login.footer`. */
  footer?: ReactNode;
  /** An error banner above the card's content (e.g. the page's `error` prop). */
  error?: ReactNode;
}) {
  const branding = useBranding();
  const t = useMessages();
  const { resolvedTheme: resolved } = useTheme();
  // The login mark: a configured logo (dark variant in dark mode), else the app name, else "onno".
  const logo = (resolved === "dark" && branding.logoUrlDark) || branding.logoUrl;
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
          <p className="text-3xl font-semibold tracking-tight">{title ?? t("login.hero.title")}</p>
          <p className="mt-4 text-sm leading-6 text-muted-foreground">{subtitle ?? t("login.hero.subtitle")}</p>
        </div>
        <p className="text-xs text-muted-foreground">{footer ?? t("login.footer")}</p>
      </section>

      {/* Only the right sign-in area is an island: a large rounded panel (shape token), bordered and
          inset by the page gutter, no shadow — matching the shell's notification/panel islands. The
          left hero stays flush on the page background. */}
      <section className="flex w-full items-stretch p-3 md:w-[500px]">
        <div className="flex w-full flex-col items-center justify-center rounded-card border border-border bg-card px-6 py-10">
          <div className="w-full max-w-sm">
            {error ? (
              <div
                role="alert"
                className="mb-4 rounded-field border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
              >
                {error}
              </div>
            ) : null}
            {children}
          </div>
        </div>
      </section>
    </main>
  );
}

/** A full-width login method button: a centered label with an optional mark on the right. */
export function LoginButton({ label, icon, primary, onClick, disabled, type = "button" }: {
  label: ReactNode;
  icon?: ReactNode;
  primary?: boolean;
  onClick?: () => void;
  disabled?: boolean;
  type?: "button" | "submit";
}) {
  return (
    <button type={type} onClick={onClick} disabled={disabled}
      className={cn("flex w-full items-center gap-2 rounded-control px-3.5 py-3 text-sm font-medium disabled:opacity-50",
        primary ? "bg-primary text-primary-foreground" : "border border-border bg-card text-foreground")}>
      {icon ? <span className="size-[18px]" /> : null}
      <span className="flex-1 truncate text-center">{label}</span>
      {icon ? <span className="grid size-[18px] place-items-center">{icon}</span> : null}
    </button>
  );
}

/** One button per SSO provider the server offers (Keycloak/OIDC, Telegram, …). */
export function SsoButtons({ providers, primaryFirst = false }: {
  providers: SsoOption[];
  /** Paint the first provider as the primary action (when it is the only way in). */
  primaryFirst?: boolean;
}) {
  const { redirect } = useSignIn();
  return (
    <>
      {providers.map((p, i) => {
        const primary = primaryFirst && i === 0;
        return (
          <LoginButton key={p.id} primary={primary} label={p.label} onClick={() => redirect(p)}
            icon={p.iconUrl ? (
              <SsoIcon src={p.iconUrl} monochrome={p.monochrome}
                color={p.monochrome ? (primary ? "hsl(var(--background))" : "hsl(var(--primary))") : undefined} />
            ) : null} />
        );
      })}
    </>
  );
}

/** The password form (username, password, demo accounts), signing in through {@link useSignIn}. */
export function PasswordLogin({ demoAccounts }: { demoAccounts?: DemoAccount[] }) {
  return <LoginForm demoAccounts={demoAccounts} />;
}

/**
 * The framework's sign-in card content: the password form and/or one button per SSO provider. With
 * both, sign-in is two steps — a method picker, then the password form.
 */
export function SignInMethods({ methods }: { methods: LoginMethods }) {
  const t = useMessages();
  const [step, setStep] = useState<"choose" | "password">("choose");
  const hasPassword = methods.passwordEnabled;
  const hasSso = methods.providers.length > 0;
  const picker = hasPassword && hasSso;
  const passwordStep = picker && step === "password";
  const showPasswordForm = passwordStep || (hasPassword && !picker);
  const subtitle = picker && !passwordStep ? "login.subtitle.choose"
    : showPasswordForm ? "login.subtitle.password" : "login.subtitle.sso";
  return (
    <div className="flex w-full flex-col gap-3 px-1.5">
      {passwordStep ? (
        <button type="button" onClick={() => setStep("choose")}
          className="flex w-fit items-center gap-1.5 text-[13px] font-medium text-muted-foreground">
          <ArrowLeft className="size-4" /> {t("login.back")}
        </button>
      ) : null}
      <LockKeyhole className="size-6" />
      <h1 className="text-2xl font-medium">{t("login.title")}</h1>
      <p className="mb-2 text-[13px] text-muted-foreground">{t(subtitle)}</p>
      {showPasswordForm ? <PasswordLogin demoAccounts={methods.demoAccounts} /> : null}
      {picker && !passwordStep ? (
        <LoginButton primary label={t("login.method.password")} onClick={() => setStep("password")}
          icon={<LockKeyhole className="size-[18px]" />} />
      ) : null}
      {hasSso && !passwordStep ? <SsoButtons providers={methods.providers} primaryFirst={!hasPassword} /> : null}
      {!hasPassword && !hasSso ? <p className="text-[13px] text-muted-foreground">{t("login.none")}</p> : null}
    </div>
  );
}

/** The framework's whole login page — wrap or reuse it from a custom one. */
export function DefaultLogin({ methods, error }: LoginPageProps) {
  const { isError } = useLoginMethods();
  return (
    <LoginLayout error={error}>
      {methods ? <SignInMethods methods={methods} />
        : isError ? <p className="text-sm text-destructive">Couldn't load the sign-in screen. Try refreshing.</p>
        : <p className="text-sm text-muted-foreground">Loading…</p>}
    </LoginLayout>
  );
}
