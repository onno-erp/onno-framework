---
name: onno-runtime
description: >-
  Verify and debug a running onno-framework app through authenticated REST, DivKit/generic UI
  endpoints, SSE events, MCP tools, generated read APIs, auth/CSRF, import/media endpoints, and
  build or Maven-local publication checks. Use when inspecting runtime behavior, testing model
  metadata, reading catalogs/documents/registers, posting/unposting documents, checking generated
  JSON response contracts, or distinguishing framework bugs from modeling mistakes.
---

# onno Runtime Verification

Most `/api/**` routes are authenticated; the configured bootstrap allowlist is public. Cookie-mode
mutations are CSRF-protected. Resource-server mode uses bearer tokens and disables CSRF.

## API Traps

- There is no anonymous `/api/ui/metadata/manifest` endpoint.
- Unknown non-`/api` paths return the SPA `index.html` with HTTP 200. Test API URLs, not page URLs.
- `{name}` route segments are annotation logical names, not Java class names or localized titles.
- Detect optional starters before testing them. The canonical example does not include MCP/import.
- Separate read-only probes, dry-run validation, and mutations. Use isolated storage/database for
  seeders, uploads, imports, posting, and CRUD.
- Catalog/document list/get JSON defaults to logical keys and expands refs/enums with companions
  such as `FieldDisplay`, `FieldRef`, and `FieldColor`; add `?representation=storage` for the legacy
  column-shaped response. Secrets read as `__SECRET_SET__`.

## Curl Recipe

```bash
base=http://localhost:8080
jar=$(mktemp)
curl -fsS -c "$jar" "$base/api/auth/csrf" >/dev/null
curl -fsS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"manager@onnobooks.local","password":"manager"}' | jq -e '.authenticated'

csrf=$(curl -fsS -b "$jar" -c "$jar" "$base/api/auth/csrf" | jq -er .token)
book_id=$(curl -fsS -b "$jar" "$base/api/list/catalogs/Books?limit=1" | jq -er '.rows[0].id')
curl -fsS -b "$jar" "$base/api/catalogs/Books/$book_id" | jq -e '.id'

order_id=$(curl -fsS -b "$jar" "$base/api/list/documents/Orders?limit=1" | jq -er '.rows[0].id')
curl -fsS -b "$jar" "$base/api/documents/Orders/$order_id" | jq -e '.items'
curl -fsS -b "$jar" "$base/api/registers/Book%20Stock/balance" | jq -e .
curl -fsS -b "$jar" "$base/api/divkit/shell" | jq -e '.nav'

curl -NsS --max-time 3 -b "$jar" "$base/api/events" 2>/dev/null | grep -q '^event:ready'

# Safe dry run: no persistence.
curl -fsS -b "$jar" -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' \
  -d '{}' "$base/api/documents/Orders/validate" | jq -e 'has("valid")'
```

The example enables demo auto-login by default, so a real anonymous/401 check requires an isolated
instance with it disabled. Startup also seeds data. Use ephemeral H2/media paths.

For an app that includes import, use CSV preview or `dryRun=true`. For media, an empty
CSRF-protected upload expecting `400` safely confirms routing; a real upload writes storage. MCP uses
streamable HTTP with stateless HTTP Basic: initialize, list tools, and call only
`describe_metadata` while diagnosing; do not invoke write/post tools by default.

## Verification Commands

Use the narrowest useful command while iterating, then broaden:

```bash
./gradlew :onno-framework:test
./gradlew :onno-framework-starter:compileJava
./gradlew :onno-ui-starter:buildFrontend
./gradlew :onno-ui-starter:compileJava
./gradlew clean check
./gradlew publishToMavenLocal
```

`publishToMavenLocal` catches sources, javadocs, POM metadata, and binary artifact issues that
project-dependency builds can miss.

## Bug Triage

A framework bug contradicts the documented contract: generated endpoint shape, annotation behavior,
posting/register integrity, schema migration, or `onno.*` config. A modeling mistake is missing
`EntityView`, wrong route/name, missing RBAC roles, wrong context, or an invalid save/post sequence.

Reduce framework bugs to the smallest repro and check for duplicate issues before filing. Confirm
with the user before creating public GitHub issues.

Read `docs/ARCHITECTURE.md` for endpoint catalogs and `docs/HEADLESS_READ_API.md` for JSON contracts.
