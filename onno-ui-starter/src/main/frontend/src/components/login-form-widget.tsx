import { FormEvent, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/providers/auth-provider";
import { useMessages } from "@/providers/messages-provider";

/** A one-tap demo sign-in: a button label plus the credentials it submits (server-configured). */
export type DemoAccount = { label: string; username: string; password: string };

/**
 * The React widget behind the DivKit {@code onno-login-form} custom block. DivKit can't read input
 * values on a button tap, so the password sub-form of the server-driven login screen is a real React
 * form: it captures the credentials, calls the auth context, and routes to the intended page on
 * success. SSO buttons, by contrast, stay pure DivKit (a tap is just a redirect).
 *
 * <p>When the server passes {@code demoAccounts} (configured via {@code onno.ui.login.demo-accounts},
 * a demo-only convenience), they render as one-tap buttons above the fields — each signs in directly
 * with its credentials, so an evaluator never types anything.</p>
 */
export function LoginFormWidget({ demoAccounts = [] }: { demoAccounts?: DemoAccount[] }) {
  const { login } = useAuth();
  const t = useMessages();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/";

  async function signIn(user: string, pass: string) {
    setSubmitting(true);
    setError("");
    try {
      await login(user, pass);
      navigate(from, { replace: true });
    } catch {
      setError(t("login.invalid"));
      setSubmitting(false);
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void signIn(username, password);
  }

  return (
    // The login screen is a server-driven DivKit card whose container blocks carry
    // pointer-events:none (taps fall through to DivKit actions). That inherits into this React
    // island and would make the inputs/button unclickable, so re-enable pointer events here.
    // No extra horizontal inset here: the card already pads its content (Div.pad in LoginDivBuilder),
    // and adding px-2 on top made the password "Sign in" button narrower than the full-width DivKit
    // SSO buttons. Sharing the single card inset keeps every button the same width.
    <div className="space-y-4 pointer-events-auto">
      {demoAccounts.length > 0 && (
        <div className="space-y-2">
          {demoAccounts.map((account) => (
            <Button
              key={account.username}
              type="button"
              variant="outline"
              className="w-full"
              disabled={submitting}
              onClick={() => void signIn(account.username, account.password)}
            >
              {account.label}
            </Button>
          ))}
          <div className="flex items-center gap-3 pt-1 text-xs text-muted-foreground">
            <span className="h-px flex-1 bg-border" />
            {t("login.orManual")}
            <span className="h-px flex-1 bg-border" />
          </div>
        </div>
      )}
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="username">{t("login.username")}</Label>
          <Input
            id="username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="password">{t("login.password")}</Label>
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button className="w-full" type="submit" disabled={submitting || !username || !password}>
          {submitting ? t("login.submitting") : t("login.submit")}
        </Button>
      </form>
    </div>
  );
}
