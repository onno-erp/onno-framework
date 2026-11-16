import { FormEvent, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useAuth } from "../../providers/auth-provider";
import { useMessages } from "../../providers/messages-provider";

/** A one-tap demo sign-in: a button label plus the credentials it submits (server-configured). */
export type DemoAccount = { label: string; username: string; password: string };

/**
 * The login screen's password form: it captures the credentials, calls the auth context, and routes
 * to the intended page on success. SSO buttons sit beside it (a tap is just a redirect).
 *
 * <p>When the server passes {@code demoAccounts} (configured via {@code onno.ui.login.demo-accounts},
 * a demo-only convenience), they render as one-tap buttons above the fields — each signs in directly
 * with its credentials, so an evaluator never types anything.</p>
 */
export function LoginForm({ demoAccounts = [] }: { demoAccounts?: DemoAccount[] }) {
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
    // No extra horizontal inset here: the card already pads its content, and adding px-2 on top
    // made the password "Sign in" button narrower than the full-width SSO buttons.
    <div className="space-y-4 pointer-events-auto">
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
      {demoAccounts.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center gap-3 pb-1 text-xs text-muted-foreground">
            <span className="h-px flex-1 bg-border" />
            {t("login.orDemo")}
            <span className="h-px flex-1 bg-border" />
          </div>
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
        </div>
      )}
    </div>
  );
}
