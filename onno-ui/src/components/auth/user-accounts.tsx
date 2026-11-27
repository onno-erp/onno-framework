import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Copy, KeyRound, LogOut, Plus, UserRoundCheck, UserRoundX } from "lucide-react";
import { api, ApiError } from "../../lib/api";
import { notifyError } from "../../lib/notify-error";
import { normalizeRole } from "../../lib/app-model";
import { useModel } from "../../lib/model-store";
import type { UserAccountView } from "../../lib/types";
import { Boundary } from "../pages/boundary";
import { toast } from "../ui/toast";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { DialogShell } from "../ui/dialog-shell";
import { HintIcon } from "../ui/hint-icon";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { ChangePasswordForm } from "./change-password-form";

const parseRoles = (text: string) =>
  Array.from(new Set(text.split(/[\s,]+/).map((r) => r.trim()).filter(Boolean)));

const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : "—");

/**
 * Account administration for onno.auth.mode=database — `<UserAccounts />`. Lists
 * accounts and lets a holder of `users.manage` create one (with a generated one-time password),
 * change roles (offering the roles the app declares), disable/enable, reset the password and sign
 * an account out everywhere. Every command goes through /api/auth/users, which enforces
 * `users.manage` and the last-manager guard server-side.
 */
export function UserAccounts({ title, hint }: UserAccountsProps) {
  return (
    <Boundary label={title || "Users"}>
      <UserAccountsBody title={title} hint={hint} />
    </Boundary>
  );
}

/** Props of {@link UserAccounts}. */
export interface UserAccountsProps {
  /** Card heading (default "Users"). */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
}

function UserAccountsBody({ title, hint }: UserAccountsProps) {
  const [accounts, setAccounts] = useState<UserAccountView[] | null>(null);
  const [unavailable, setUnavailable] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<UserAccountView | null>(null);
  const [secret, setSecret] = useState<{ username: string; password: string } | null>(null);

  const load = useCallback(() => {
    api
      .listUserAccounts()
      .then(setAccounts)
      .catch((e) => {
        const status = e instanceof ApiError ? e.status : 0;
        setUnavailable(
          status === 404
            ? "Account management needs onno.auth.mode=database."
            : status === 403
              ? "Managing accounts needs the users.manage permission."
              : e instanceof Error
                ? e.message
                : String(e)
        );
      });
  }, []);

  useEffect(load, [load]);

  // The roles the app declares (its RoleKey enum), plus any an account already holds — normalized,
  // so an account stored before roles were upper-cased ("manager") does not offer MANAGER twice.
  const declaredRoles = useModel()?.roles;
  const knownRoles = useMemo(
    () => Array.from(new Set([...(declaredRoles ?? []), ...(accounts ?? []).flatMap((a) => a.roles)]
      .map(normalizeRole).filter(Boolean))).sort(),
    [accounts, declaredRoles]
  );

  const run = async (label: string, action: () => Promise<unknown>) => {
    try {
      await action();
      toast.success(label);
      load();
    } catch (e) {
      notifyError(e);
    }
  };

  const reset = async (account: UserAccountView) => {
    try {
      const { temporaryPassword } = await api.resetUserPassword(account.id);
      setSecret({ username: account.username, password: temporaryPassword });
      load();
    } catch (e) {
      notifyError(e);
    }
  };

  return (
    <Card className="pointer-events-auto w-full">
      <CardContent className="p-5">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <h3 className="text-sm font-medium text-foreground">{title || "Users"}</h3>
            {hint ? <HintIcon text={hint} /> : null}
          </div>
          {accounts ? (
            <Button size="toolbar" variant="outline" onClick={() => setCreating(true)}>
              <Plus /> New user
            </Button>
          ) : null}
        </div>

        {unavailable ? (
          <p className="mt-3 text-sm text-muted-foreground">{unavailable}</p>
        ) : !accounts ? (
          <div className="mt-3 h-24 animate-pulse rounded-control bg-muted/40" />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3 font-medium">Username</th>
                  <th className="py-2 pr-3 font-medium">Roles</th>
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium">Last sign-in</th>
                  <th className="py-2 font-medium" />
                </tr>
              </thead>
              <tbody>
                {accounts.map((a) => (
                  <tr key={a.id} className="border-t border-border align-middle">
                    <td className="py-2 pr-3">
                      <button
                        type="button"
                        className="text-left font-medium hover:underline"
                        onClick={() => setEditing(a)}
                      >
                        {a.username}
                      </button>
                    </td>
                    <td className="py-2 pr-3">
                      <div className="flex flex-wrap gap-1">
                        {a.roles.length ? (
                          a.roles.map((r) => (
                            <Badge key={r} variant="secondary">
                              {r}
                            </Badge>
                          ))
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </div>
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      {!a.enabled ? (
                        <span className="text-muted-foreground">Disabled</span>
                      ) : a.passwordChangeRequired ? (
                        <span className="text-amber-700 dark:text-amber-300">Must set password</span>
                      ) : (
                        <span>Active</span>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-xs text-muted-foreground">{when(a.lastLoginAt)}</td>
                    <td className="py-2">
                      <div className="flex justify-end gap-1">
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-8 w-8"
                          title="Reset password"
                          aria-label={`Reset password for ${a.username}`}
                          onClick={() => void reset(a)}
                        >
                          <KeyRound />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-8 w-8"
                          title="Sign out everywhere"
                          aria-label={`Sign out ${a.username} everywhere`}
                          onClick={() => void run("Signed out everywhere", () => api.signOutUserAccount(a.id))}
                        >
                          <LogOut />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-8 w-8"
                          title={a.enabled ? "Disable" : "Enable"}
                          aria-label={`${a.enabled ? "Disable" : "Enable"} ${a.username}`}
                          onClick={() =>
                            void run(a.enabled ? "Account disabled" : "Account enabled", () =>
                              api.updateUserAccount(a.id, { enabled: !a.enabled })
                            )
                          }
                        >
                          {a.enabled ? <UserRoundX /> : <UserRoundCheck />}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>

      {creating ? (
        <CreateUserDialog
          knownRoles={knownRoles}
          onClose={() => setCreating(false)}
          onCreated={(username, password) => {
            setCreating(false);
            if (password) setSecret({ username, password });
            else toast.success("User created");
            load();
          }}
        />
      ) : null}

      {editing ? (
        <RolesDialog
          account={editing}
          knownRoles={knownRoles}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast.success("Roles updated");
            load();
          }}
        />
      ) : null}

      {secret ? (
        <DialogShell
          title="One-time password"
          description={`Give this to ${secret.username}. It is shown only now and must be changed at first sign-in.`}
          onOpenChange={(open) => !open && setSecret(null)}
          size="sm"
          footer={<Button onClick={() => setSecret(null)}>Done</Button>}
        >
          <div className="flex items-center gap-2">
            <code className="flex-1 select-all rounded-field border border-input bg-muted px-3 py-2 font-mono text-sm">
              {secret.password}
            </code>
            <Button
              size="icon"
              variant="outline"
              aria-label="Copy password"
              onClick={() =>
                navigator.clipboard?.writeText(secret.password).then(() => toast.success("Copied"), () => {})
              }
            >
              <Copy />
            </Button>
          </div>
        </DialogShell>
      ) : null}
    </Card>
  );
}

function RoleHints({ roles, onPick }: { roles: string[]; onPick: (role: string) => void }) {
  if (!roles.length) return null;
  return (
    <div className="flex flex-wrap gap-1">
      {roles.map((r) => (
        <button key={r} type="button" onClick={() => onPick(r)}>
          <Badge variant="outline">{r}</Badge>
        </button>
      ))}
    </div>
  );
}

const addRole = (text: string, role: string) => parseRoles(`${text} ${role}`).join(", ");

function CreateUserDialog({
  knownRoles,
  onClose,
  onCreated,
}: {
  knownRoles: string[];
  onClose: () => void;
  onCreated: (username: string, temporaryPassword: string | null) => void;
}) {
  const [username, setUsername] = useState("");
  const [roles, setRoles] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const created = await api.createUserAccount(username, parseRoles(roles));
      onCreated(created.account.username, created.temporaryPassword);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <DialogShell
      title="New user"
      description="A one-time password is generated; the user sets their own at first sign-in."
      onOpenChange={(open) => !open && onClose()}
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={saving || !username.trim()} onClick={() => void submit()}>
            Create
          </Button>
        </>
      }
    >
      <form className="flex flex-col gap-3" onSubmit={submit}>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="new-user-name">Username</Label>
          <Input
            id="new-user-name"
            autoFocus
            autoComplete="off"
            placeholder="name@company.com"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="new-user-roles">Roles</Label>
          <Input
            id="new-user-roles"
            placeholder="MANAGER, CLERK"
            value={roles}
            onChange={(e) => setRoles(e.target.value)}
          />
          <RoleHints roles={knownRoles} onPick={(r) => setRoles((t) => addRole(t, r))} />
        </div>
        {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
      </form>
    </DialogShell>
  );
}

function RolesDialog({
  account,
  knownRoles,
  onClose,
  onSaved,
}: {
  account: UserAccountView;
  knownRoles: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [roles, setRoles] = useState(account.roles.join(", "));
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setError(null);
    try {
      await api.updateUserAccount(account.id, { roles: parseRoles(roles) });
      onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  return (
    <DialogShell
      title={account.username}
      description={`Created ${when(account.createdAt)} · password set ${when(account.passwordChangedAt)}. Changing roles signs the user out.`}
      onOpenChange={(open) => !open && onClose()}
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void save()}>Save</Button>
        </>
      }
    >
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="edit-user-roles">Roles</Label>
        <Input id="edit-user-roles" value={roles} onChange={(e) => setRoles(e.target.value)} />
        <RoleHints roles={knownRoles} onPick={(r) => setRoles((t) => addRole(t, r))} />
        {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
      </div>
    </DialogShell>
  );
}

/**
 * The signed-in account's own password change — `<ChangePassword />`.
 * Database mode only; other modes show a short note instead of a form that cannot work.
 */
export function ChangePassword({ title, hint }: ChangePasswordProps) {
  return (
    <Boundary label={title || "Change password"}>
      <ChangePasswordBody title={title} hint={hint} />
    </Boundary>
  );
}

/** Props of {@link ChangePassword}. */
export interface ChangePasswordProps {
  /** Card heading (default "Change password"). */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
}

function ChangePasswordBody({ title, hint }: ChangePasswordProps) {
  const [supported, setSupported] = useState<boolean | null>(null);

  useEffect(() => {
    api
      .getPasswordPolicy()
      .then(() => setSupported(true))
      .catch(() => setSupported(false));
  }, []);

  return (
    <Card className="pointer-events-auto w-full">
      <CardContent className="p-5">
        <div className="flex items-center gap-1.5">
          <h3 className="text-sm font-medium text-foreground">{title || "Change password"}</h3>
          {hint ? <HintIcon text={hint} /> : null}
        </div>
        <div className="mt-3 max-w-sm">
          {supported === null ? (
            <div className="h-24 animate-pulse rounded-control bg-muted/40" />
          ) : supported ? (
            <ChangePasswordForm onChanged={() => { toast.success("Password changed"); }} />
          ) : (
            <p className="text-sm text-muted-foreground">Passwords are managed by your identity provider.</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
