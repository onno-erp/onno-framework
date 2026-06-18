# Upgrading a consumer app from onec to onno

The onno release renames the framework end to end: Maven coordinates, Java packages,
the `onec.*` config namespace, internal DB tables, and a few externally-observable
identifiers (metrics, frontend keys). There are **no backward-compat shims** — apply the
changes below in one pass per consumer.

## 1. Build coordinates (Gradle/Maven)

| Old | New |
| --- | --- |
| group `io.github.onec-erp` | `su.onno` |
| artifacts `onec-framework-starter`, `onec-ui-starter`, … | `onno-framework-starter`, `onno-ui-starter`, … |
| Gradle plugin id `com.onec.desktop` | `su.onno.desktop` |

```kotlin
// before: implementation("io.github.onec-erp:onec-ui-starter:<ver>")
implementation("su.onno:onno-ui-starter:<ver>")
```

## 2. Java code

All packages move `com.onec.*` → `su.onno.*`; class prefixes `Onec*` → `Onno*`.
A find-and-replace over imports/usages covers it:

```
import com.onec.       →  import su.onno.
com.onec.              →  su.onno.
OnecProperties / Onec* →  OnnoProperties / Onno*
```

## 3. Configuration — properties, env vars, JVM props, CLI

The config namespace moves `onec.*` → `onno.*`. This affects **every form** of binding:

| Form | Before | After |
| --- | --- | --- |
| YAML/properties | `onec.auth.mode: oidc` | `onno.auth.mode: oidc` |
| Environment variable | `ONEC_AUTH_MODE=oidc` | `ONNO_AUTH_MODE=oidc` |
| JVM system property | `-Donec.schema.mode=apply` | `-Donno.schema.mode=apply` |
| JVM scan-packages prop | `-Donec.scan-packages=…` | `-Donno.scan-packages=…` |
| CLI arg | `--onec.ui.path=/ui` | `--onno.ui.path=/ui` |

Spring relaxed binding maps env vars by upper-casing and replacing `.`/`-` with `_`
(e.g. `onno.ui.update-check.url` ← `ONNO_UI_UPDATECHECK_URL`). Prefixes in use:

```
onno · onno.auth · onno.ui · onno.ui.update-check · onno.schema · onno.mail
onno.mcp · onno.kafka · onno.cluster · onno.import · onno.print · onno.media
onno.comments · onno.desktop
```

The full property reference is in [docs/CONFIGURATION.md](../CONFIGURATION.md).
Logging config follows the package rename: `logging.level.com.onec` → `logging.level.su.onno`.

## 4. Database

Run [`onec-to-onno.sql`](onec-to-onno.sql) once per database (app stopped, after a backup).
It renames the framework's internal tables/indexes; your business tables are unaffected.
See that file's header for the full rationale and procedure.

## 5. Metrics / observability (don't miss this)

The framework publishes Micrometer meters that carry the prefix:

| Old meter | New meter |
| --- | --- |
| `onec.operation.duration` | `onno.operation.duration` |
| `onec.operation.items` | `onno.operation.items` |

In Prometheus these surface as `onec_operation_duration_seconds` etc. **Update Grafana
dashboards, recording rules, and alerts** to the `onno_operation_*` names, or they go silent.

## 6. Cosmetic / self-healing (usually no action)

- **Default media dir** — uploads with no explicit `onno.media.directory` default to
  `${java.io.tmpdir}/onno-media` (was `…/onec-media`). If you relied on the default *and*
  a persistent tmp, move the directory or set `onno.media.directory` to the old path.
- **Custom UI CSS** — the bundled frontend renamed its classes (`onec-*` → `onno-*`,
  `fc-onec-*` → `fc-onno-*`). Only relevant if you wrote CSS overriding framework styles.
- **Browser-local state** — `localStorage` theme key and the dismissed-update key changed,
  so a user's theme choice and dismissed update-notice reset once. Per-browser, harmless.
- **Custom frontend JS** — internal DOM events renamed (`onec:action`/`onec:dataevent` →
  `onno:*`). Only matters if you dispatched/listened to them from custom code.
- **Dev remember-me fallback** — the built-in non-secret ephemeral key changed, so existing
  remember-me cookies are invalidated once (dev only; production must set
  `onno.auth.session.remember-me.key`).

## Not affected

JobRunr job IDs (UUIDs), Kafka topic names and CloudEvent `type`/`source` (application-defined,
never framework-branded), session/CSRF cookie names (standard Spring), and your business
catalog/document/register tables (named after your entities).
