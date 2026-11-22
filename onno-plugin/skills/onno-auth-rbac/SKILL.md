---
name: onno-auth-rbac
description: >-
  Configure onno-auth-starter and onno-framework authorization. Use when setting onno.auth.mode,
  database accounts (UserAccounts, user admin, password reset/change), in-memory users, OIDC/SSO, resource-server JWT, public paths, CSRF ignored paths, remember-me,
  session timeout, AuthMethodsProvider/AuthMethodsContributor, RoleGrants / RoleKey / Permission
  (entity grants, capabilities, open mode, IdP role mapping), UI page roles and permissions, MCP/web authorization, record-level access policies
  (RecordAccessPolicy, RecordScope, AccessSubject, RecordAccess, external users / customer portals),
  or debugging 401/403/404 access issues.
---

# onno Auth And RBAC

Authentication decides who the caller is. The application's `RoleGrants` bean (and record policies)
decide what they can see or change.

## Rules

- `/api/**` requires auth except configured public bootstrap endpoints.
- The framework's bootstrap paths are always public; add your own anonymous paths with
  `onno.auth.additional-public-paths`, and custom anonymous `/api/**` SSO callback POSTs also to
  `csrf-ignored-paths` in cookie modes. Spring's normal `/login/oauth2/code/{registrationId}` callback is outside `/api/**`.
  The full default list is in [references/gotchas.md](references/gotchas.md).
- Prefer `onno.auth.mode: database` for any real app: accounts live in `onno_auth_user` and are
  managed at runtime (`/api/auth/users` and the `<UserAccounts />` / `<ChangePassword />`
  components from `@onno/ui`). `onno.auth.users` then only seeds an empty store once, and seeded accounts must change
  their password at first sign-in. Never ship `admin`/`admin` in an `in-memory` config — it stays
  valid forever. Disabling, resetting, role changes and "sign out everywhere" end live sessions.
- In the password modes (`in-memory`, `database`), when remember-me is enabled, a blank
  `onno.auth.session.remember-me.key` fails startup; dev opt-out is
  `allow-ephemeral-key: true`. Never ship prod without a real key.
- In-memory, database and OIDC modes use sessions and CSRF.
- Resource-server mode uses bearer JWT and no CSRF.
- The starter protects `/api/**` and permits other same-port routes. Treat exposed Actuator or custom
  non-API endpoints as a separate security decision; use a management port/chain when needed.
- Roles are an app enum (`enum AppRole implements RoleKey`) and one `RoleGrants` bean says what
  each may do: `read`/`write`/`post`/`unpost(Entity.class…)` (each implies the weaker ones),
  `readAll()`…, `permit(Permission…)`, `fromIdp("erp-admins")`. Declaring it makes access
  deny-by-default; **there is no superuser** — a role named `ADMIN` gets exactly its grants.
- **No `RoleGrants` bean = open mode**: every signed-in user may do everything (startup warns),
  except the explicit-only `periodClose.override`, `processes.administer`, `crm.administer`.
  Anonymous callers get nothing. Moving from open mode to grants is the switch to deny-by-default.
- Admin-only screens are permissions, not roles: `settings.edit`, `users.manage`,
  `periodClose.manage`, `print.templates.manage`, `comments.moderate`, `processes.administer`,
  `crm.administer` (constants in `CorePermissions`, `AuthPermissions`, `PrintPermissions`,
  `CrmPermissions`). An IdP without an `ADMIN` role works: grant the permissions to whatever role
  `fromIdp(...)` maps.
- Role names are normalized everywhere (`ROLE_manager` → `MANAGER`): account roles, IdP claims,
  grants, command/print-form/`@McpTool` role lists, CRM workspaces and UI gates. Once grants are
  declared, a role list naming no declared role or `fromIdp` name (a typo such as `"MANGER"`) fails
  startup.
- Posting is its own right: `post(Invoice.class)` lets reception create and post invoices,
  `unpost(Invoice.class)` lets a manager unpost (or delete a posted) one; `write(...)` alone allows
  neither. REST/MCP answer `403`; the UI hides the Post/Unpost actions.
- Database mode lockout protection: the first seeded account gets a role holding `users.manage` if
  no seed has one, and the last enabled holder cannot be disabled or demoted. When existing accounts
  hold none (an upgrade from the `ADMIN` superuser), startup warns and names the roles granting it.
- Record-level access (external users seeing only their own records) is a `RecordAccessPolicy`
  bean: `RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
  .read(RecordScope.eq("owner", Subject.recordId()))`; `via("tenant")` scopes through a ref;
  `write(...)` defaults to read; `defaults(d -> d.set("owner", Subject.recordId()))` fills creates.
  `Subject.recordId()` needs an `IdentityLink` bean. Out-of-scope records are 404, refs to them
  render restricted. Startup rejects invalid/dead policies (a dead policy's `appliesTo` roles have no
  read grant). Only `exemptRoles(...)` exempts a role; none is exempt implicitly.
- Typed repositories are NOT record-scoped (trusted code). Custom endpoints/actions/`@McpTool`s
  serving users take an `AccessSubject` (controller parameter, `ctx.subject()`) and check with
  `RecordAccess`; never pass `AccessSubject.system()` on a user's behalf.
- Page/nav `roles` and `permission` in `defineApp` curate UI (typed from `model.gen.ts`); entity
  grants and permissions still gate data and API calls on the server.
- With password + ≥1 SSO provider configured, the login screen renders a method chooser first —
  extra providers come from `AuthMethodsContributor` beans (Telegram lives in
  onno-enterprise/onno-telegram-starter; broker mode via cloud.onno.su needs no client secret).
- Custom sign-in (one-time code, magic link, badge): a `su.onno.auth.spi.LoginMethod` bean
  (`POST /api/auth/login/{id}[/challenge]`, CSRF-protected, password modes only) returns the username;
  the framework creates the session. The screen is the app's React `defineApp({ login })` page using
  `useSignIn()` — see `onno-auth-starter/README.md#application-login-methods`.
- Demo login buttons are `onno.ui.login.demo-accounts` (under `onno.ui`, not `onno.auth`).
- Public demo auto-login is `onno.auth.demo.auto-login-username`; it bypasses sign-in and is only
  safe for non-sensitive demo data.
- Iframe embedding requires an explicit `onno.auth.embedding.frame-ancestors` allowlist. Cross-site
  session iframes also need `onno.auth.embedding.cross-site-cookies=true` and HTTPS
  `SameSite=None; Secure` servlet-session cookies.

Read [Record access policies](https://github.com/onno-erp/onno-framework/blob/main/docs/RECORD_ACCESS_POLICIES.md) for the full policy
model and enforcement table, [references/examples.md](references/examples.md) for config and
debugging flows, and
[references/gotchas.md](references/gotchas.md) for the public bootstrap list and common failure
signatures.
