import { useAuth } from "@/providers/auth-provider";
import { Button } from "@/components/ui/button";
import { ChangePasswordForm } from "@/components/change-password-form";

/**
 * Shown instead of the app while the signed-in account still holds a one-time password
 * (`passwordChangeRequired` from /api/auth/me). The server refuses every other API call until the
 * password is replaced, so this is the only useful screen; success refreshes the session.
 */
export function ChangePasswordView() {
  const { user, refresh, logout } = useAuth();
  return (
    <main className="flex min-h-screen items-center justify-center bg-background p-3">
      <div className="w-full max-w-sm rounded-card border border-border bg-card px-6 py-8">
        <h1 className="text-lg font-semibold tracking-tight">Set a new password</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {user?.username ? <span className="font-medium text-foreground">{user.username}</span> : "This account"} signed
          in with a one-time password. Choose your own to continue.
        </p>
        <div className="mt-5">
          <ChangePasswordForm onChanged={refresh} submitLabel="Save and continue" />
        </div>
        <Button variant="link" className="mt-3 h-auto px-0 text-xs" onClick={() => void logout()}>
          Sign out
        </Button>
      </div>
    </main>
  );
}
