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

Read [references/examples.md](references/examples.md) for when to use each command and what failures
mean.
