# onno-framework

Reusable Spring Boot starters for building onno-style business applications in Java.

The repository is a Gradle multi-module build. Applications usually consume one or more published artifacts rather than including this repository as a composite build.

For teams or AI agents using these libraries to build an ERP application, start with [Building ERPs With onno-framework And AI Agents](BUILDING_ERPS_WITH_AGENTS.md).

Building a headless front end or integration against the generic API? See the
[Headless Read API](docs/HEADLESS_READ_API.md) for the JSON response contract (column-name keys,
reference/enum expansion, secret redaction, list vs get) and how to react to changes.

## Documentation

The published docs site — hand-written guides plus reference **generated from the source of truth** —
lives at **<https://docs.onno.su/>** (built with [VitePress](https://vitepress.dev)
by [`.github/workflows/docs.yml`](.github/workflows/docs.yml)).

| Doc | What it covers |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Operating rules for AI agents + the playbook for modeling a business into framework concepts. |
| [BUILDING_ERPS_WITH_AGENTS.md](BUILDING_ERPS_WITH_AGENTS.md) | Handoff guide for building an ERP in a separate project on the published libraries. |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the framework fits together: boot pipeline, each subsystem, the full endpoint catalog, open-core boundary. |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Every `onno.*` configuration property, by module, with defaults. **Generated** from the `@ConfigurationProperties` Javadoc — see below. |
| [docs/HEADLESS_READ_API.md](docs/HEADLESS_READ_API.md) | JSON response contract for the generic read API. |
| [docs/MEDIA_UPLOADS.md](docs/MEDIA_UPLOADS.md) | Binary upload endpoint and the `MediaStorage` SPI. |
| [docs/EXTENDING.md](docs/EXTENDING.md) | How to build a community extension (connector, SPI, UI, skill), the naming/namespace conventions, and how to get it listed. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute code and how to list a community integration. |
| [INTEGRATIONS.md](INTEGRATIONS.md) | Catalog of community-built integrations (generated from `community/registry.json`). |
| Java API (Javadoc) | Aggregated API reference at [`/api`](https://docs.onno.su/api/) on the docs site (`./gradlew aggregateJavadoc`). |
| [`onno` skills](onno-plugin/skills/onno/SKILL.md) | Focused agent skills that make Claude Code good at this framework. Auto-loaded for anyone working in this repo; installable by downstream apps (see below). |

Each module also has its own `README.md` with integration-specific setup.

### Generated reference (don't hand-edit)

`docs/CONFIGURATION.md` is **generated**, not hand-maintained — its tables come from each starter's
`spring-configuration-metadata.json`, which Spring's annotation processor builds from the
`@ConfigurationProperties` field Javadoc. This keeps the config reference from drifting:

```bash
./gradlew generateConfigDocs   # rewrite docs/CONFIGURATION.md from the @ConfigurationProperties Javadoc
./gradlew checkConfigDocs      # fail if it drifted (also runs as part of `./gradlew check`)
```

To change a property's docs, edit its **Javadoc** and regenerate — never edit the table by hand.

Preview the full site locally:

```bash
./gradlew generateConfigDocs aggregateJavadoc       # refresh generated reference
mkdir -p docs/public/api && cp -R build/docs/javadoc/. docs/public/api/   # stage Javadoc under /api
cd docs && npm install && npm run docs:dev          # http://localhost:5173/onno-framework/
```

### Using the onno skills

The `onno` plugin teaches Claude Code to model a business into framework concepts, get posting /
validation / migration right, author the generated UI, build extensions, and verify the runtime API.
The canonical copies live under [`onno-plugin/skills/`](onno-plugin/skills/onno/SKILL.md).

- **Working in this repo?** They're auto-discovered — `.claude/skills/*` symlinks point to the
  canonical plugin copies, so Claude Code loads them with no setup.
- **Building a separate app on the published artifacts?** Install it as a plugin (this repo doubles as
  a plugin marketplace via [`.claude-plugin/marketplace.json`](.claude-plugin/marketplace.json)):

  ```text
  /plugin marketplace add onno-erp/onno-framework
  /plugin install onno@onno-framework
  ```

  Claude then auto-invokes the relevant skill, or you can run one explicitly:

  | Skill | Use it for |
  | --- | --- |
  | `/onno:onno` | Overview, architecture routing, cross-cutting framework work |
  | `/onno:onno-modeling-interview` | What to ask the user and how to extract the model |
  | `/onno:onno-modeling` | Overall modeling workflow and concept routing |
  | `/onno:onno-catalogs-enums` | Master data, refs, enums, labels, soft delete |
  | `/onno:onno-documents-lines` | Business events, document headers, tabular sections |
  | `/onno:onno-registers` | Balance, turnover, and information registers |
  | `/onno:onno-rules-lifecycle` | Defaults, derived fields, validation hooks |
  | `/onno:onno-schema-migrations` | Renames, generated schema diffs, data migrations |
  | `/onno:onno-posting` | Register movements, post/unpost side effects |
  | `/onno:onno-ui-layout-pages` | Sidebar, personas, dashboards, pages |
  | `/onno:onno-ui-entity-views` | Lists, forms, actions, filters, related lists |
  | `/onno:onno-ui-widgets` | Built-in and custom React widgets |
  | `/onno:onno-runtime-api` | Authenticated REST/API smoke tests |
  | `/onno:onno-auth-rbac` | Auth modes, roles, `@AccessControl`, CSRF |
  | `/onno:onno-mcp` | MCP tool surface and client workflow |
  | `/onno:onno-testing-release` | Gradle checks, docs generation, Maven local verification |
  | `/onno:onno-extensions` | Community extensions and starter contracts |
  | `/onno:onno-connectors` | External API connector patterns |

## Modules

| Module | Purpose |
| --- | --- |
| `onno-framework` | Core annotations, metadata scanners, repository contracts, schema generation, posting, typed business-process prototype, UI layout model, and shared types. |
| `onno-framework-starter` | Spring Boot auto-configuration for the core framework and repositories. |
| `onno-ui-starter` | Generic web UI controllers and packaged frontend assets. |
| `onno-auth-starter` | Basic Spring Security auto-configuration and auth API endpoints. |
| `onno-print-starter` | Thymeleaf-based document rendering and PDF output support. |
| `onno-mail-starter` | Mail templates, dispatchers, suppression, preview endpoints, and outbox relay support. |
| `onno-kafka-starter` | Kafka event publishing, inbox routing, service registry, and remote reference helpers. |
| `onno-import-starter` | CSV import services and endpoints for catalogs. |
| `onno-cluster-starter` | Cross-node delivery of entity-change events for horizontal scale-out (pluggable bus; default Postgres LISTEN/NOTIFY). |
| `onno-mcp-starter` | MCP server exposing metadata, CRUD, register queries, and posting as AI-agent tools. |
| `onno-desktop-starter` | Desktop runtime support and packaged Tauri shell resources. |
| `onno-desktop-gradle-plugin` | Gradle plugin for packaging a Spring Boot app as a native desktop bundle. |
| `example` | Local example application. It is not intended to be published as a library. |

Commercial vertical connectors — `onno-guesty-starter` (Guesty Open API) and
`onno-hospedajes-starter` (Spanish SES.HOSPEDAJES) — live in the separate, commercially licensed
[onno-enterprise](https://github.com/onno-erp/onno-enterprise) repository. See the
[License](#license) section.

## Extending onno

The framework is built to be extended **without forking** — you ship a separate artifact the host
app opts into. Four extension surfaces: **connectors** (wrap an external system), **SPI
implementations** (`MediaStorage`, `MailDispatcher`, custom auth, …), **UI** (widgets/pages/actions),
and Claude **skills/plugins**. The full how-to — the starter shape, the naming/namespace conventions
that keep the `su.onno` and `su.onno.*` namespaces reserved, and a "definition of done"
checklist — is in [docs/EXTENDING.md](docs/EXTENDING.md).

Built one? Add it to the community catalog in [INTEGRATIONS.md](INTEGRATIONS.md): append an entry to
[`community/registry.json`](community/registry.json), run `./gradlew generateIntegrationsDoc`, and
open a PR (see [CONTRIBUTING.md](CONTRIBUTING.md#listing-a-community-integration)).

## Requirements

- Java 21
- Gradle wrapper from this repository
- Spring Boot 3.4.x in consuming applications
- Node 20 is downloaded automatically when building `onno-ui-starter`

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
    implementation("su.onno:onno-framework-starter:0.1.0-SNAPSHOT")
    implementation("su.onno:onno-ui-starter:0.1.0-SNAPSHOT")
}
```

> Maven coordinates use the `su.onno` group; the Java packages are still `su.onno.*`,
> so your imports don't change.

### Dev mode (live reload)

Add `spring-boot-devtools` to your app and the save-to-screen loop closes itself:

```kotlin
dependencies {
    developmentOnly("org.springframework.boot:spring-boot-devtools")
}
```

Run the app with an exploded classpath (`./gradlew bootRun`) and keep a compiler going in a second
terminal (`./gradlew -t classes`). On every save devtools restarts the application context — which
in onno *is* the whole reload story: the metamodel rescan, the schema diff, and every layout/page
rebuild all happen at boot. The UI starter detects devtools, stamps each SSE `ready` ack with a
per-boot id, and the web client answers a changed id with a full page reload. Restarts take well
under a second on the example app; the browser refreshes itself right after.

Everything is automatic: devtools never ships in the production boot jar, so deployed apps never
self-reload. To force the behaviour without devtools (e.g. a hosted preview instance), set
`onno.ui.dev-mode=true` explicitly — see [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

Need an explicit "refresh now" — from a build script, an agent, or by hand? In dev mode the server
also watches a trigger file (`onno.ui.dev-reload-trigger`, default `.onno-reload` in the working
directory):

```bash
touch .onno-reload   # every connected browser reloads within ~½ s
```

## Maven Central

Released modules are published to [Maven Central](https://central.sonatype.com/namespace/su.onno)
under the `su.onno` group. Consumers need **no credentials and no custom repository** —
just `mavenCentral()`:

```kotlin
val onnoVersion = "<latest>"   // pick the latest release from the link below

repositories {
    mavenCentral()
}

dependencies {
    implementation("su.onno:onno-framework-starter:$onnoVersion")
}
```

> Replace `<latest>` with the latest released version from
> [Maven Central](https://central.sonatype.com/namespace/su.onno).

### Publishing a release

CI publishes from version tags via the [vanniktech maven-publish](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin. Push a tag named `vX.Y.Z` to publish artifacts with version `X.Y.Z`:

```bash
git tag v1.2.3
git push origin v1.2.3
```

Release candidates work the same way — a `vX.Y.Z-rc1` tag publishes `X.Y.Z-rc1`. The workflow also
supports manual dispatch with an explicit version. In both cases CI runs `clean check`, signs the
artifacts, and uploads a deployment to the Central Portal. Because `gradle.properties` sets
`SONATYPE_AUTOMATIC_RELEASE=true`, the deployment **auto-releases** once the Portal finishes
validation — no manual **Publish** click. A GitHub Release with generated notes is created too
(suffixed versions like `X.Y.Z-rc1` are marked pre-release). Maven Central does not allow replacing a
released version, so only push a `v*` tag when you mean it.

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

Inside this repository, `settings.gradle.kts` uses `includeBuild("onno-desktop-gradle-plugin")`, so a project in this build can apply:

```kotlin
plugins {
    id("su.onno.desktop")
}
```

External projects should either resolve the plugin from the published package repository or include the plugin build locally during development:

```kotlin
pluginManagement {
    includeBuild("../onno-framework/onno-desktop-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Spring Boot Auto-Configuration

Each starter exposes its auto-configuration through Spring Boot 3's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` mechanism. In a consuming app, adding the starter dependency is enough to make its conditional beans available.

Most integration starters are disabled by default and are enabled through `onno.*` configuration properties. See the module READMEs for integration-specific setup.

## Schema Migrations

The database schema is derived from the metadata model (`@Catalog`, `@Document`, registers, …). At startup the framework diffs that model against the live database and brings it up to date — no migration files for structural changes.

```properties
onno.schema.mode=apply              # apply | plan | validate | off (default: apply)
onno.schema.allow-destructive=false # gate for drops and narrowing type changes
```

- **apply** (default) — execute safe changes: new tables/columns, renames, widening type
  changes (e.g. `VARCHAR(100)` → `VARCHAR(200)`, `INTEGER` → `BIGINT`). Destructive changes
  (dropped tables/columns, narrowing types) are logged and skipped unless
  `onno.schema.allow-destructive=true`.
- **plan** — log the full migration plan with its SQL and change nothing. Review in CI, then apply.
- **validate** — fail startup if the database does not match the metadata or migrations are
  unapplied. Use in production with `apply` running as a deploy step.
- **off** — schema is managed externally.

Every applied change-set is recorded in `onno_schema_history` together with a snapshot of the
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
per database — in version order, inside a transaction, recorded in `onno_schema_history`:

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

- The modules in this repository (published to Maven Central under the `su.onno` group)
  are open source under the [Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE) for attribution.
- Separately licensed **commercial** modules (published under the `su.onno.enterprise` group from a
  private repository — the vertical connectors Guesty and SES.HOSPEDAJES) are governed by the
  [onno Commercial License](docs/licensing/COMMERCIAL-LICENSE.md) and are not part of this
  distribution. Authentication, including OIDC / single sign-on, is part of the open-source core
  (`onno-auth-starter`).

The boundary, and the plan for extracting the commercial modules into their own repository, is
documented in [docs/licensing/MODULE-SPLIT-PLAN.md](docs/licensing/MODULE-SPLIT-PLAN.md).
