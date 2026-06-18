import { FormEvent, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/providers/auth-provider";

/**
 * The React widget behind the DivKit {@code onno-login-form} custom block. DivKit can't read input
 * values on a button tap, so the password sub-form of the server-driven login screen is a real React
 * form: it captures the credentials, calls the auth context, and routes to the intended page on
 * success. SSO buttons, by contrast, stay pure DivKit (a tap is just a redirect).
 */
export function LoginFormWidget() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/";

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await login(username, password);
      navigate(from, { replace: true });
    } catch {
      setError("The username or password is not correct.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    // The login screen is a server-driven DivKit card whose container blocks carry
    // pointer-events:none (taps fall through to DivKit actions). That inherits into this React
    // island and would make the inputs/button unclickable, so re-enable pointer events here.
    // The horizontal padding insets the inputs from the DivKit block edge (which clips), so their
    // focus ring isn't cut off.
    <form onSubmit={handleSubmit} className="space-y-4 px-2 pointer-events-auto">
      <div className="space-y-2">
        <Label htmlFor="username">Username</Label>
        <Input
          id="username"
          autoComplete="username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="password">Password</Label>
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
        {submitting ? "Signing in..." : "Sign in"}
      </Button>
    </form>
  );
}
