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

Everything under `/api/**` is authenticated. Mutations are CSRF-protected.

## API Traps

- There is no anonymous `/api/ui/metadata/manifest` endpoint.
- Unknown non-`/api` paths return the SPA `index.html` with HTTP 200. Test API URLs, not page URLs.
- `{name}` route segments are entity display names such as `Properties` or `Sales Orders`, not Java
  class names.
- List/get JSON expands refs and enums with companion keys such as `{col}_display`, `{col}_ref`, and
  `{col}_color`; secrets read as `__SECRET_SET__`.

## Curl Recipe

```bash
curl -c jar.txt http://localhost:8080/api/config
curl -b jar.txt -c jar.txt -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}'

curl -b jar.txt http://localhost:8080/api/catalogs/Properties

XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
curl -b jar.txt -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d '{...entity JSON...}' http://localhost:8080/api/catalogs/Properties
```

Use MCP `describe_metadata`, CRUD, register read, and posting tools when available; they follow the
same auth and RBAC model.

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
