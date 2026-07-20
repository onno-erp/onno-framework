---
name: onno-runtime-api
description: >-
  Inspect or call onno-framework generated REST and UI APIs. Use when logging in, handling CSRF,
  calling /api/catalogs, /api/documents, /api/registers, posting preview/post/unpost endpoints,
  reading list/get JSON contracts with *_display/*_ref/*_color, testing media/import endpoints,
  debugging route names, distinguishing SPA fallback 200 from real API success, or writing curl/API
  smoke tests for an onno app.
---

# onno Runtime API

Generated APIs are authenticated. Browser page routes are not a substitute for API checks.

## Rules

- Login with `/api/auth/login` or your selected auth mode.
- Mutations need `X-XSRF-TOKEN` in cookie-based modes.
- Use entity display names in URLs: `/api/catalogs/Products`, not Java class names.
- Unknown non-API routes can return SPA HTML with 200.
- There is no anonymous `/api/ui/metadata/manifest`.
- Reads use snake_case storage columns; writes use camelCase model field names and are partial.
- `LocalDate` is `yyyy-MM-dd`. `LocalDateTime` is offset-free ISO wall time
  (`yyyy-MM-ddTHH:mm[:ss[.fraction]]`). Offset-bearing writes are accepted without shifting the
  local fields, but callers should send the canonical offset-free form.

Read [references/examples.md](references/examples.md) for curl flows and response-shape examples.
