import type { ComponentType } from "react";
import { Navigate, useLocation } from "react-router";
import { useAuth } from "../providers/auth-provider";
import { useMessages } from "../providers/messages-provider";
import { DefaultLogin, useLoginMethods, useSignIn, type LoginPageProps } from "../components/auth/sign-in";

/** Sign-in error codes with a specific message; any other ?error value falls back to generic. */
const KNOWN_LOGIN_ERRORS = new Set(["telegram", "access_denied", "session_expired", "sso"]);

/**
 * The `/login` route. The server describes which methods are available (`GET /api/ui/login`); the
 * page is the framework's {@link DefaultLogin} unless the app supplies its own
 * (`defineApp({ login: MyLogin })`), which receives the same {@link LoginPageProps}.
 */
export function LoginView({ component: Page = DefaultLogin }: { component?: ComponentType<LoginPageProps> }) {
  const { user } = useAuth();
  const location = useLocation();
  const t = useMessages();
  const { from } = useSignIn();
  const { data: methods } = useLoginMethods();

  // An explicit sign-in error, carried back as ?error=<code> (e.g. a failed Telegram/SSO round-trip
  // redirects to /login?error=telegram). Known codes get a specific message; anything else falls
  // back to a generic one. All messages are overridable via onno.ui.messages.login.error.*.
  const errorCode = new URLSearchParams(location.search).get("error");
  const error = errorCode
    ? t(KNOWN_LOGIN_ERRORS.has(errorCode) ? `login.error.${errorCode}` : "login.error.generic")
    : null;

  // Already signed in (e.g. landed here after an SSO round-trip) — go where we were headed.
  if (user) {
    return <Navigate to={from} replace />;
  }
  return <Page methods={methods ?? null} error={error} from={from} />;
}
