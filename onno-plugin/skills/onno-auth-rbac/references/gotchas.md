# Auth Failure Signatures

## Public Bootstrap Defaults

Setting `onno.auth.public-paths` replaces, rather than appends to, these defaults:

```yaml
onno.auth.public-paths:
  - /error
  - /api/theme
  - /api/config
  - /api/branding
  - /api/auth/login
  - /api/auth/me
  - /api/auth/csrf
  - /api/divkit/login
  - /api/desktop/ready
  - /api/desktop/manifest
```

Add custom anonymous endpoints after preserving the required defaults. In cookie modes, also add an
anonymous POST callback to `onno.auth.csrf-ignored-paths`. Resource-server mode disables CSRF, so
that list is ignored.

## Common Signatures

- Login UI returns `401` after a public-path change: a default bootstrap route was omitted.
- Mutation returns `403` in in-memory/OIDC mode: authenticate, fetch `/api/auth/csrf`, and echo its
  token in `X-XSRF-TOKEN`.
- Mutation returns `403` in resource-server mode: CSRF is not the cause; check bearer validity,
  mapped roles, entity `@AccessControl`, and `onno.ui.read-only`.
- Resource-server `/api/auth/login` returns `409`: clients authenticate with bearer tokens; the
  starter does not issue them.
- A same-port Actuator/custom non-API route is unexpectedly public: the default chain protects
  `/api/**` and permits other routes. Use a separate management port/chain when required.
- Remember-me fails startup: in in-memory mode with remember-me enabled, configure a stable key or
  explicitly allow an ephemeral development key.
- HTML appears where JSON was expected: validate the exact API route and `Content-Type`; the SPA
  fallback can return HTML with status 200 for unmatched routes.
