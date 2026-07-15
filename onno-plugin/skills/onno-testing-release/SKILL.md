---
name: onno-testing-release
description: >-
  Test, verify, package, or publish onno-framework changes. Use when choosing Gradle verification
  commands, running module tests, frontend builds/tests, clean check, publishToMavenLocal,
  aggregateJavadoc, generated docs checks, integration doc generation, local app smoke tests,
  Maven-consumption verification, release readiness, or diagnosing warnings that are known
  non-blocking versus publication-breaking.
---

# onno Testing And Release Verification

Use the narrowest useful command while iterating, then broaden before handing off.

## Command Ladder

```bash
./gradlew :onno-framework:test
./gradlew :onno-framework-starter:compileJava
./gradlew :onno-ui-starter:buildFrontend
./gradlew :onno-ui-starter:compileJava
./gradlew clean check
./gradlew publishToMavenLocal
```

## Runtime Smoke

Prefer live verification over rebuild-and-stare: run the app with dev mode
(`developmentOnly` devtools + `./gradlew -t :example:classes`; `touch .onno-reload` to force a
browser reload) — see `docs/RUNNING.md`. Example logins: `admin@onnobooks.local`/`admin`,
`manager@onnobooks.local`/`manager`.

## Releases

Publishing is tag-driven CI only (`vX.Y.Z` → Central Portal + cloud.onno.su/modules mirror) — never
`./gradlew publish` locally. Verify an artifact landed before bumping consumers:
`curl -sI https://repo1.maven.org/maven2/su/onno/onno-framework-starter/<v>/…pom`, or the registry
with the license key as password. Consumer setup + upgrade checklist: `docs/CONSUMING.md`.

Read [references/examples.md](references/examples.md) for when to use each command and what failures
mean.
