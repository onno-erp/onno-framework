# Consuming releases

Where artifacts come from, how a consumer app declares them, and the checklist for upgrading.
Maintainer-side release mechanics are in the root [README](../README.md#publishing-a-release) and
`.github/workflows/publish-packages.yml`.

> **Keep this current.** If the registry URL, credential scheme, or publish pipeline changes,
> update this page in the same PR.

## Where artifacts live

- **Open core (`su.onno:*`)** — Maven Central, published by tag-driven CI (Central Portal,
  auto-release). Also mirrored to the cloud registry below.
- **Commercial modules (`su.onno.enterprise:*`)** — **only** the cloud registry
  `https://cloud.onno.su/modules`. They are not on Maven Central and never on GitHub Packages.
- Publishing is CI-only in both repos: push a `vX.Y.Z` tag. Never `./gradlew publish` from a
  workstation.

## Consumer build setup

Put the license key in `~/.gradle/gradle.properties` (never in the repo):

```properties
onnoLicenseKey=ONNO-XXXX-XXXX-XXXX-XXXX
```

```kotlin
repositories {
    mavenCentral() // su.onno:* open-source core
    maven {
        url = uri("https://cloud.onno.su/modules")
        credentials {
            username = "license"   // any value; the key is the password
            password = providers.gradleProperty("onnoLicenseKey").get()
        }
    }
}

dependencies {
    implementation("su.onno:onno-framework-starter:<version>")
    implementation("su.onno.enterprise:onno-tochka-starter:<version>")   // if entitled
}
```

Resolve-time errors mean licensing, not networking: **403** = key valid but not entitled to that
module; **401** = unknown or suspended key.

Runtime enforcement is separate from resolve-time access: `onno.license.key` +
`onno.license.server-url=https://cloud.onno.su` (self-hosted) or `ONNO_LICENSE_OFFLINE_TOKEN`
(cloud tenant), with `onno.license.enforcement=soft|hard`; individual features gate on
`@ConditionalOnEntitlement`. See `onno-license-support/README.md`.

## Upgrade checklist

1. **Confirm the version is actually published** — probe Central and/or the registry (curl
   commands in [RUNNING.md](RUNNING.md#verify-what-is-actually-running)), or check
   `https://cloud.onno.su/releases/v1/latest`. Tags on `main` are not releases until CI finishes.
2. Bump the version in the consumer build; keep `su.onno` and `su.onno.enterprise` on the **same
   version** unless a release note says otherwise.
3. Read the release notes / [GOTCHAS.md](GOTCHAS.md) for behaviour changes (e.g. blank
   `remember-me.key` now fails startup).
4. Run the app locally; schema migration is diff-based and runs at boot — check the migration log
   output before deploying.
5. Deploy, then verify the live version via `GET /api/config` (`update.current`), not by eyeballing
   the UI.
6. If the framework misbehaves in a consumer app, **file the bug on `onno-erp/onno-framework`**
   (repro + versions) rather than patching around it in the app.
