---
name: onno-runtime-api
description: >-
  Inspect or call onno-framework generated REST and UI APIs. Use when logging in, handling CSRF,
  calling /api/catalogs, /api/documents, /api/registers, posting preview/post/unpost endpoints,
  reading list/get JSON contracts with *Display/*Ref/*Color companions, testing media/import endpoints,
  debugging route names, distinguishing SPA fallback 200 from real API success, or writing curl/API
  smoke tests for an onno app.
---

# onno Runtime API

To drive a running app without writing curl, use the `onno-verify` skill. Its `onno-drive` CLI
handles login, cookies and CSRF, prints the feature map, and wraps the endpoints below.

Generated APIs are authenticated. Browser page routes are not a substitute for API checks.

## Rules

- Login with `/api/auth/login` or your selected auth mode.
- Mutations need `X-XSRF-TOKEN` in cookie-based modes.
- Use the annotation's logical `name` in URLs, not the Java class name. Collection reads use
  `/api/list/catalogs/{name}` and `/api/list/documents/{name}`; CRUD uses
  `/api/catalogs/{name}` and `/api/documents/{name}`.
- Unknown non-API routes can return SPA HTML with 200.
- There is no anonymous `/api/ui/metadata/manifest`.
- Catalog/document reads default to logical camelCase names matching partial writes. Add
  `?representation=storage` only for legacy reads; writes accept either vocabulary and reject
  conflicting aliases.
- `LocalDate` is `yyyy-MM-dd`. `LocalDateTime` is offset-free ISO wall time
  (`yyyy-MM-ddTHH:mm[:ss[.fraction]]`). Offset- or zone-bearing writes are rejected with a
  field-specific `400`; callers must send the canonical offset-free form.
- `LocalTime` is `HH:mm[:ss[.fraction]]`. `Instant` is the reverse of `LocalDateTime`: writes must
  carry `Z` or `±HH:mm` (offset-free is a `400`), reads are ISO UTC (`…Z`).
- Every collection read is a keyset window `{rows, nextCursor, hasMore}` (`limit` default 100,
  max 500): the list feeds, `/api/registers/{name}/movements|balance|turnover`, information-register
  records, notifications, comments, `/api/history/{kind}/{name}/{id}`. Replay `nextCursor` as
  `?cursor=` unchanged; there is no `offset`. `total` only with an explicit `count=`.
- Commands answer `{refresh, feedback, navigateTo}`; `navigateTo` (from `CommandResult.open` /
  `navigate`) is an app path or `onno://` link.
- Period close: `GET /api/period-close`, `/audit`, `POST /close`, `/reopen`; a write into a closed
  period is `409 period-closed`. `/api/ui/model` reports `features.periodClose` and per-entity
  `history`.

Read [references/examples.md](references/examples.md) for curl flows and response-shape examples.
