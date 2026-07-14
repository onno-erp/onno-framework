# Testing And Release Examples

## Table Of Contents

- Choose A Command
- Docs Generation
- Maven Local Verification
- Runtime Smoke Test
- Known Warnings

## Choose A Command

| Change | Start With | Finish With |
| --- | --- | --- |
| annotation/base model/posting core | `./gradlew :onno-framework:test` | `./gradlew clean check` |
| starter auto-config | `./gradlew :module:compileJava` | `./gradlew clean check` |
| UI Java/controller behavior | `./gradlew :onno-ui-starter:test` | `./gradlew clean check` |
| frontend only | `./gradlew :onno-ui-starter:buildFrontend` | `./gradlew clean check` |
| config property docs | `./gradlew generateConfigDocs` | `./gradlew checkConfigDocs clean check` |
| community registry | `./gradlew generateIntegrationsDoc` | `./gradlew clean check` |
| publication/POM/Javadoc | `./gradlew publishToMavenLocal` | `./gradlew publishToMavenLocal` |

## Docs Generation

```bash
./gradlew generateConfigDocs
./gradlew checkConfigDocs
./gradlew generateIntegrationsDoc
./gradlew aggregateJavadoc
```

`docs/CONFIGURATION.md` is generated from configuration metadata. Edit property Javadoc or
additional metadata, then regenerate.

## Maven Local Verification

```bash
./gradlew publishToMavenLocal
```

This catches issues that project dependencies hide:

- missing dependency versions in generated POMs
- broken Javadoc links
- sources/javadoc jar failures
- publication metadata mistakes
- consumers resolving stale coordinates

## Runtime Smoke Test

```bash
./gradlew :example:bootRun
```

Then in another shell:

```bash
base=http://localhost:8080
jar=$(mktemp)
curl -fsS -c "$jar" "$base/api/config" >/dev/null
curl -fsS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' >/dev/null
curl -fsS -b "$jar" "$base/api/catalogs/Books" | jq .
curl -fsS -b "$jar" "$base/api/documents/Orders" | jq .
```

Use API routes for smoke tests. Browser routes can fall through to SPA HTML with status 200.

## Known Warnings

- Javadoc may emit missing-comment warnings. Broken links are not acceptable.
- Frontend npm audit may report moderate vulnerabilities. Do not force-upgrade without checking
  generated frontend behavior and lockfile impact.
- Generated local caches (`.gradle/`, `build/`, `.kotlin/`, frontend `dist/`, `node_modules/`) should
  not be committed.
