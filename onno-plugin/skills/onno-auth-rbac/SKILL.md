---
name: onno-auth-rbac
description: >-
  Configure onno-auth-starter and onno-framework authorization. Use when setting onno.auth.mode,
  in-memory users, OIDC/SSO, resource-server JWT, public paths, CSRF ignored paths, remember-me,
  session timeout, AuthMethodsProvider/AuthMethodsContributor, @AccessControl readRoles/writeRoles,
  ADMIN behavior, UI profile roles, MCP/web authorization, record-level access policies
  (RecordAccessPolicy, RecordScope, AccessSubject, RecordAccess, external users / customer portals),
  or debugging 401/403/404 access issues.
---

# onno Auth And RBAC

Authentication decides who the caller is. `@AccessControl` and UI access decide what they can see or
change.

## Rules

- `/api/**` requires auth except configured public bootstrap endpoints.
- **`onno.auth.public-paths` REPLACES the defaults — re-list all of them when overriding**, and add
  custom anonymous `/api/**` SSO callback paths to both `public-paths` and `csrf-ignored-paths` in
  cookie modes. Spring's normal `/login/oauth2/code/{registrationId}` callback is outside `/api/**`.
  The full default list is in [references/gotchas.md](references/gotchas.md).
- In in-memory mode, when remember-me is enabled, a blank
  `onno.auth.session.remember-me.key` fails startup; dev opt-out is
  `allow-ephemeral-key: true`. Never ship prod without a real key.
- In-memory and OIDC modes use sessions and CSRF.
- Resource-server mode uses bearer JWT and no CSRF.
- The starter protects `/api/**` and permits other same-port routes. Treat exposed Actuator or custom
  non-API endpoints as a separate security decision; use a management port/chain when needed.
- `@AccessControl` is deny-by-default. `ADMIN` always passes.
- `writeRoles` falls back to `readRoles` when empty.
- Record-level access (external users seeing only their own records) is a `RecordAccessPolicy`
  bean: `RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
  .read(RecordScope.eq("owner", Subject.recordId()))`; `via("tenant")` scopes through a ref;
  `write(...)` defaults to read; `defaults(d -> d.set("owner", Subject.recordId()))` fills creates.
  `Subject.recordId()` needs `Layout.identity(...)`. Out-of-scope records are 404, refs to them
  render restricted. Startup rejects invalid/dead policies.
- Typed repositories are NOT record-scoped (trusted code). Custom endpoints/actions/`@McpTool`s
  serving users take an `AccessSubject` (controller parameter, `ctx.subject()`) and check with
  `RecordAccess`; never pass `AccessSubject.system()` on a user's behalf.
- Layout/page roles curate UI; entity access still gates data and API calls.
- With password + ≥1 SSO provider configured, the login screen renders a method chooser first —
  extra providers come from `AuthMethodsContributor` beans (Telegram lives in
  onno-enterprise/onno-telegram-starter; broker mode via cloud.onno.su needs no client secret).
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
