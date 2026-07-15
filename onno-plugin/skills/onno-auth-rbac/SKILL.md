---
name: onno-auth-rbac
description: >-
  Configure onno-auth-starter and onno-framework authorization. Use when setting onno.auth.mode,
  in-memory users, OIDC/SSO, resource-server JWT, public paths, CSRF ignored paths, remember-me,
  session timeout, AuthMethodsProvider/AuthMethodsContributor, @AccessControl readRoles/writeRoles,
  ADMIN behavior, UI profile roles, MCP/web authorization, or debugging 401/403 access issues.
---

# onno Auth And RBAC

Authentication decides who the caller is. `@AccessControl` and UI access decide what they can see or
change.

## Rules

- `/api/**` requires auth except configured public bootstrap endpoints.
- **`onno.auth.public-paths` REPLACES the defaults — re-list all of them when overriding**, and add
  SSO callback paths to both `public-paths` and `csrf-ignored-paths`. Full default list in
  `docs/GOTCHAS.md`.
- A blank `onno.auth.session.remember-me.key` fails startup; dev opt-out is
  `allow-ephemeral-key: true`. Never ship prod without a real key.
- In-memory and OIDC modes use sessions and CSRF.
- Resource-server mode uses bearer JWT and no CSRF.
- `@AccessControl` is deny-by-default. `ADMIN` always passes.
- `writeRoles` falls back to `readRoles` when empty.
- Layout/page roles curate UI; entity access still gates data and API calls.
- With password + ≥1 SSO provider configured, the login screen renders a method chooser first —
  extra providers come from `AuthMethodsContributor` beans (Telegram lives in
  onno-enterprise/onno-telegram-starter; broker mode via cloud.onno.su needs no client secret).
- Demo login buttons are `onno.ui.login.demo-accounts` (under `onno.ui`, not `onno.auth`).

Read [references/examples.md](references/examples.md) for config and debugging flows, and
`docs/GOTCHAS.md` for the failure-signature list (401 after config change, sign-out after
redeploy, HTML instead of JSON).
