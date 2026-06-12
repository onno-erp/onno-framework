# onec-framework

Reusable Spring Boot starters for building onec-style business applications in Java.

The repository is a Gradle multi-module build. Applications usually consume one or more published artifacts rather than including this repository as a composite build.

For teams or AI agents using these libraries to build an ERP application, start with [Building ERPs With onec-framework And AI Agents](BUILDING_ERPS_WITH_AGENTS.md).

Building a headless front end or integration against the generic API? See the
[Headless Read API](docs/HEADLESS_READ_API.md) for the JSON response contract (column-name keys,
reference/enum expansion, secret redaction, list vs get) and how to react to changes.

## Modules

| Module | Purpose |
| --- | --- |
| `onec-framework` | Core annotations, metadata scanners, repository contracts, schema generation, posting, UI layout model, and shared types. |
| `onec-framework-starter` | Spring Boot auto-configuration for the core framework and repositories. |
| `onec-ui-starter` | Generic web UI controllers and packaged frontend assets. |
| `onec-auth-starter` | Basic Spring Security auto-configuration and auth API endpoints. |
| `onec-print-starter` | Thymeleaf-based document rendering and PDF output support. |
| `onec-mail-starter` | Mail templates, dispatchers, suppression, preview endpoints, and outbox relay support. |
| `onec-kafka-starter` | Kafka event publishing, inbox routing, service registry, and remote reference helpers. |
| `onec-desktop-starter` | Desktop runtime support and packaged Tauri shell resources. |
| `onec-desktop-gradle-plugin` | Gradle plugin for packaging a Spring Boot app as a native desktop bundle. |
| `example` | Local example application. It is not intended to be published as a library. |

Commercial vertical connectors — `onec-guesty-starter` (Guesty Open API) and
`onec-hospedajes-starter` (Spanish SES.HOSPEDAJES) — live in the separate, commercially licensed
[onec-enterprise](https://github.com/onec-erp/onec-enterprise) repository. See the
[License](#license) section.

## Requirements

- Java 21
- Gradle wrapper from this repository
- Spring Boot 3.4.x in consuming applications
- Node 20 is downloaded automatically when building `onec-ui-starter`

## Local Development

Build and test all modules:

```bash
./gradlew clean check
```

Publish artifacts to the local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then consume them from another local project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.onec-erp:onec-framework-starter:0.1.0-SNAPSHOT")
    implementation("io.github.onec-erp:onec-ui-starter:0.1.0-SNAPSHOT")
}
```

> Maven coordinates use the `io.github.onec-erp` group; the Java packages are still `com.onec.*`,
> so your imports don't change.

## Maven Central

Released modules are published to [Maven Central](https://central.sonatype.com/namespace/io.github.onec-erp)
under the `io.github.onec-erp` group. Consumers need **no credentials and no custom repository** —
just `mavenCentral()`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.onec-erp:onec-framework-starter:0.6.0")
}
```

### Publishing a release

CI publishes from version tags via the [vanniktech maven-publish](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin. Push a tag named `v0.6.0` to publish artifacts with version `0.6.0`:

```bash
git tag v0.6.0
git push origin v0.6.0
```

Release candidates work the same way — a `v0.6.0-rc1` tag publishes `0.6.0-rc1`. The workflow also
supports manual dispatch with an explicit version. In both cases CI runs `clean check`, signs the
artifacts, and uploads a deployment to the Central Portal; with `SONATYPE_AUTOMATIC_RELEASE=false`
the deployment is staged for a manual **Publish** click at [central.sonatype.com](https://central.sonatype.com/publishing/deployments).
A GitHub Release with generated notes is created too (suffixed versions like `0.6.0-rc1` are marked pre-release).

Publishing requires these repository secrets (see the publish workflow):

| Secret | What it is |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | Central Portal **user token** (Account → Generate User Token). |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored GPG private key (`gpg --armor --export-secret-keys`). |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Passphrase for that GPG key. |

To publish locally to Maven Central instead, set the matching `ORG_GRADLE_PROJECT_*` properties and
run `./gradlew publishToMavenCentral`. A bare `./gradlew publishToMavenLocal` needs no signing (it is
skipped for `-SNAPSHOT` versions).

## Desktop Plugin

Inside this repository, `settings.gradle.kts` uses `includeBuild("onec-desktop-gradle-plugin")`, so the example app can apply:

```kotlin
plugins {
    id("com.onec.desktop")
}
```

External projects should either resolve the plugin from the published package repository or include the plugin build locally during development:

```kotlin
pluginManagement {
    includeBuild("../onec-framework/onec-desktop-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Spring Boot Auto-Configuration

Each starter exposes its auto-configuration through Spring Boot 3's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` mechanism. In a consuming app, adding the starter dependency is enough to make its conditional beans available.

Most integration starters are disabled by default and are enabled through `onec.*` configuration properties. See the module READMEs for integration-specific setup.

## Schema Migrations

The database schema is derived from the metadata model (`@Catalog`, `@Document`, registers, …). At startup the framework diffs that model against the live database and brings it up to date — no migration files for structural changes.

```properties
onec.schema.mode=apply              # apply | plan | validate | off (default: apply)
onec.schema.allow-destructive=false # gate for drops and narrowing type changes
```

- **apply** (default) — execute safe changes: new tables/columns, renames, widening type
  changes (e.g. `VARCHAR(100)` → `VARCHAR(200)`, `INTEGER` → `BIGINT`). Destructive changes
  (dropped tables/columns, narrowing types) are logged and skipped unless
  `onec.schema.allow-destructive=true`.
- **plan** — log the full migration plan with its SQL and change nothing. Review in CI, then apply.
- **validate** — fail startup if the database does not match the metadata or migrations are
  unapplied. Use in production with `apply` running as a deploy step.
- **off** — schema is managed externally.

Every applied change-set is recorded in `onec_schema_history` together with a snapshot of the
metadata model; the snapshot is how type changes and removed entities are detected on later boots.

**Renames keep data.** Without a hint, a renamed field would look like “drop + add”. Declare the
former name and the upgrader renames the column (or table) in place:

```java
@Catalog(name = "Counterparties", previousNames = "Suppliers")
public class Counterparty extends CatalogObject {

    @Attribute(length = 50, previousNames = "phone")
    private String phoneNumber;
}
```

**Data migrations** (backfills, reshaping, seeding) are versioned Java beans, run exactly once
per database — in version order, inside a transaction, recorded in `onec_schema_history`:

```java
@Component
public class BackfillWarehouseCodes implements AppMigration {
    public String version() { return "2026.06.001"; }
    public void migrate(MigrationContext context) {
        context.execute("UPDATE catalog_warehouses SET code = 'WH-' || _code WHERE code IS NULL");
    }
}
```

## License

The framework follows an **open-core** model.

- The modules in this repository (published to Maven Central under the `io.github.onec-erp` group)
  are open source under the [Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE) for attribution.
- Separately licensed **commercial** modules (published under the `com.onec.enterprise` group from a
  private repository — the vertical connectors Guesty and SES.HOSPEDAJES) are governed by the
  [onec Commercial License](docs/licensing/COMMERCIAL-LICENSE.md) and are not part of this
  distribution. Authentication, including OIDC / single sign-on, is part of the open-source core
  (`onec-auth-starter`).

The boundary, and the plan for extracting the commercial modules into their own repository, is
documented in [docs/licensing/MODULE-SPLIT-PLAN.md](docs/licensing/MODULE-SPLIT-PLAN.md).
