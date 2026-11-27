# Auth Failure Signatures

## Public Bootstrap Defaults

These framework bootstrap paths are always public, whatever `onno.auth.public-paths` says:

```text
/error
/api/theme
/api/config
/api/branding
/api/auth/login
/api/auth/me
/api/auth/csrf
/api/ui/login
```

Add your own anonymous endpoints with `onno.auth.additional-public-paths` (or `public-paths`; both
are combined with the framework paths). In cookie modes, also add an
anonymous POST callback to `onno.auth.csrf-ignored-paths`. Resource-server mode disables CSRF, so
that list is ignored.

## Common Signatures

- Login UI returns `401`: a custom security chain or proxy is blocking a bootstrap route above.
- Mutation returns `403` in in-memory/OIDC mode: authenticate, fetch `/api/auth/csrf`, and echo its
  token in `X-XSRF-TOKEN`.
- Mutation returns `403` in resource-server mode: CSRF is not the cause; check bearer validity,
  mapped roles, the entity grants in `RoleGrants`, and `onno.web.read-only`.
- Everything is allowed for every user: the app declares no `RoleGrants` bean, so it runs open
  (see the startup warning). Declare one to switch to deny-by-default.
- An admin screen is gone after an upgrade: there is no `ADMIN` superuser any more; grant the
  screen's permission (`settings.edit`, `users.manage`, …) to the role that should have it.
- Resource-server `/api/auth/login` returns `409`: clients authenticate with bearer tokens; the
  starter does not issue them.
- A same-port Actuator/custom non-API route is unexpectedly public: the default chain protects
  `/api/**` and permits other routes. Use a separate management port/chain when required.
- Remember-me fails startup: in in-memory mode with remember-me enabled, configure a stable key or
  explicitly allow an ephemeral development key.
- HTML appears where JSON was expected: validate the exact API route and `Content-Type`; the SPA
  fallback can return HTML with status 200 for unmatched routes.
