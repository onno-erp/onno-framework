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
- In-memory and OIDC modes use sessions and CSRF.
- Resource-server mode uses bearer JWT and no CSRF.
- `@AccessControl` is deny-by-default. `ADMIN` always passes.
- `writeRoles` falls back to `readRoles` when empty.
- Layout/page roles curate UI; entity access still gates data and API calls.

Read [references/examples.md](references/examples.md) for config and debugging flows.
