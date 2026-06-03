import { FormEvent, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { LockKeyhole } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/providers/auth-provider";

export function LoginView() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? "/";

  if (user) {
    return <Navigate to={from} replace />;
  }

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
    <main className="flex min-h-screen bg-background">
      <section className="hidden flex-1 border-r border-border bg-muted/30 px-12 py-10 md:flex md:flex-col md:justify-between">
        <div className="text-sm font-semibold">onec</div>
        <div className="max-w-md">
          <p className="text-3xl font-semibold tracking-tight">Business apps shaped around roles.</p>
          <p className="mt-4 text-sm leading-6 text-muted-foreground">
            Sign in to see the catalogs, documents, dashboards, and forms your role is allowed to use.
          </p>
        </div>
        <p className="text-xs text-muted-foreground">Basic auth today. OIDC-ready tomorrow.</p>
      </section>

      <section className="flex min-h-screen w-full items-center justify-center px-5 md:w-[440px]">
        <form onSubmit={handleSubmit} className="w-full max-w-sm space-y-6">
          <div className="space-y-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-md border border-border">
              <LockKeyhole className="h-5 w-5" />
            </div>
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">Sign in</h1>
              <p className="mt-1 text-sm text-muted-foreground">Use your workspace credentials.</p>
            </div>
          </div>

          <div className="space-y-4">
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
          </div>

          {error && <p className="text-sm text-destructive">{error}</p>}

          <Button className="w-full" type="submit" disabled={submitting || !username || !password}>
            {submitting ? "Signing in..." : "Sign in"}
          </Button>
        </form>
      </section>
    </main>
  );
}
