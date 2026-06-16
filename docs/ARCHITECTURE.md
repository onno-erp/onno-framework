# onec-framework Architecture

A code-grounded map of how the framework fits together: the boot pipeline, each subsystem, the
generated runtime surface, and the module/licensing boundaries. This is the reference companion to
the modeling playbook in [AGENTS.md](../AGENTS.md) and the consumer guide in
[BUILDING_ERPS_WITH_AGENTS.md](../BUILDING_ERPS_WITH_AGENTS.md). For the exhaustive list of
`onec.*` properties see [CONFIGURATION.md](CONFIGURATION.md).

> **Keep this current.** When you change a public annotation, base class, repository contract,
> endpoint, auto-configuration property, or module boundary, update this file (and the other docs it
> cross-references) in the same change. See [Keeping docs in sync](../AGENTS.md#keeping-docs-in-sync).

## The core idea

You describe a business as **typed Java metadata** — `@Catalog`, `@Document`, `@TabularSection`,
`@AccumulationRegister`, `@InformationRegister`, `@Enumeration`, `@Constant`, scheduled jobs — and
the framework generates everything downstream from that model: the database schema, repositories, a
type-safe query layer, a generic REST API, a server-driven UI, an MCP tool surface for AI agents,
and migration history. You do not hand-write tables, DTOs, or CRUD controllers. Behaviour that *is*
code — posting rules, validation, lifecycle hooks, UI authoring — is plain, refactorable,
compiler-checked Java, never string-mapped configuration.

Java packages are always `com.onec.*`. The published Maven group is `io.github.onec-erp` (core,
Apache-2.0) and `com.onec.enterprise` (commercial connectors). The desktop Gradle plugin id is
`com.onec.desktop`.

## Modules

| Module | Group | Role |
| --- | --- | --- |
| `onec-framework` | `io.github.onec-erp` | Core: annotations, metadata scanners + registry, schema diff/migration, JDBI persistence, posting engine, `QueryEngine`, repository contracts, events, outbox, UI model (`Layout`/`Page`/`EntityView`). |
| `onec-framework-starter` | `io.github.onec-erp` | Spring Boot auto-configuration that wires the core: metadata registry, repositories, schema initializer, posting service, query engine, number generation, secret cipher, background jobs. |
| `onec-ui-starter` | `io.github.onec-erp` | Generic REST controllers under `/api/**`, the DivKit server-driven UI layer, the bundled React/Vite SPA, media uploads, SSE event stream. |
| `onec-auth-starter` | `io.github.onec-erp` | Spring Security: in-memory, OIDC/SSO, and resource-server (JWT) modes; JSON login/logout; CSRF; per-request principal. |
| `onec-mcp-starter` | `io.github.onec-erp` | Model Context Protocol server exposing the model + CRUD + register reads + posting as AI-agent tools, generated from the registry. |
| `onec-import-starter` | `io.github.onec-erp` | CSV import (preview, mapping, upsert, dry-run, document grouping) through the same command path as the UI. |
| `onec-cluster-starter` | `io.github.onec-erp` | Cross-node delivery of entity-change events for horizontal scale-out via a pluggable `ClusterEventBus` SPI (default Postgres `LISTEN`/`NOTIFY`; no-op on H2). Keeps the SSE live UI in sync across instances. |
| `onec-kafka-starter` | `io.github.onec-erp` | Transactional outbox → Kafka relay as CloudEvents, de-duplicating inbox, service registry, remote `Ref` client. |
| `onec-mail-starter` | `io.github.onec-erp` | `@MailTemplate` Thymeleaf rendering, pluggable dispatchers (SMTP/HTTP/file/log/failover), outbox + suppression + preview. |
| `onec-print-starter` | `io.github.onec-erp` | `@PrintTemplate` Thymeleaf → HTML/PDF (Flying Saucer / OpenPDF) document rendering. |
| `onec-desktop-starter` | `io.github.onec-erp` | Runs the app as a native desktop window (Tauri shell), config-as-code window manifest, H2/session relocation. |
| `onec-desktop-gradle-plugin` | (`com.onec.desktop` plugin) | Packages a Spring Boot app into a native `.dmg`/`.msi`/`.AppImage` via jlink + Tauri. |
| `example` | (not published) | A vacation-rentals ERP that exercises every concept; the canonical reference app. |
| `onec-guesty-starter`, `onec-hospedajes-starter`, `onec-tochka-starter` | `com.onec.enterprise` | Commercial vertical connectors in the separate [onec-enterprise](https://github.com/onec-erp/onec-enterprise) repo. |

## Boot pipeline

`onec-framework-starter` auto-configuration (`OnecAutoConfiguration`,
`onec-framework-starter/src/main/java/com/onec/spring/OnecAutoConfiguration.java`) runs after
`DataSourceAutoConfiguration` and assembles the runtime in this order:

1. **Resolve scan packages.** `onec.scan-packages` if set, otherwise Spring Boot's
   auto-configuration base packages (the package of your `@SpringBootApplication`). There is **no**
   `onec.base-packages` for the core scan — that name belongs only to `onec.mail.base-packages` /
   `onec.print.base-packages`.
2. **Scan metadata.** Reflection scanners read the annotations and build immutable descriptors
   (`CatalogDescriptor`, `DocumentDescriptor`, `AccumulationRegisterDescriptor`, …) into the
   `MetadataRegistry` (`onec-framework/src/main/java/com/onec/metadata/`).
3. **Migrate the schema.** `SchemaInitializer` derives the desired schema from the registry, diffs
   it against the live database + the last snapshot in `onec_schema_history`, and applies/plans/
   validates per `onec.schema.mode`. Versioned `AppMigration` beans run once each, in version order.
4. **Wire persistence + behaviour.** JDBI, Spring Data JDBC repositories, register persistence,
   `PostingService`, `QueryEngine`, `NumberGenerator`, `SecretCipher`, callbacks (id generation,
   numbering, secret encryption, change-event publishing, `isNew` reset), background jobs, and the
   `Layout`/`Page`/`EntityView` UI model beans.
5. **Layer on optional starters.** `onec-ui-starter` adds the REST + DivKit + SPA surface;
   `onec-auth-starter` adds the security chain; the integration starters add their endpoints and
   beans, each gated by an `onec.<module>.enabled` flag (default on).

## Domain concepts → annotations

The modeling guidance lives in [AGENTS.md](../AGENTS.md); the full annotation reference with every
attribute and default lives in the skill cheat sheet
([`onec-plugin/skills/onec/reference/cheatsheet.md`](../onec-plugin/skills/onec/reference/cheatsheet.md)).
In brief:

- **`@Catalog`** — stable reference data (Products, Customers). `codeLength`, `codePrefix`,
  `autoNumber`, `hierarchical`, `previousNames`, `context`. Base class `CatalogObject`
  (`id`, `code`, `description`, `deletionMark`, `folder`, `parent`, `@Version version`, `isNew`).
- **`@Document`** — business events (Sales Order, Invoice). `numberPrefix`, `numberLength`,
  `autoNumber`, `previousNames`, `context`. Base class `DocumentObject`
  (`id`, `number`, `date`, `posted`, `deletionMark`, `version`, `isNew`).
- **`@TabularSection`** — line-item collections on a document; rows extend `TabularSectionRow`.
- **`@AccumulationRegister`** — ledgers, `type = BALANCE | TURNOVER`; `@Dimension` keys and
  `@Resource` numbers; rows extend `AccumulationRecord` (`period`, `active`, `documentRef`,
  `movementType = RECEIPT | EXPENSE`).
- **`@InformationRegister`** — facts by dimension over time, `periodicity = NONE|DAY|MONTH|QUARTER|YEAR`;
  rows extend `InformationRecord`.
- **`@Enumeration`** (on a Java `enum`), **`@Constant`** (singleton setting), **`@ScheduledJob`**
  (`cron`) / `@Scheduled` background jobs, **`@DomainEvent`** (outbox), **`@AccessControl`**
  (`readRoles`/`writeRoles`), **`@Attribute`** (`required`, `length`, `precision`/`scale`, `secret`,
  validation `min`/`max`/`pattern`/`email`, `previousNames`).
- **`Ref<T>`** (`com.onec.types.Ref`) — a typed `(Class<T>, UUID)` reference, stored as a UUID
  column; resolved with `RefResolver`.

Deprecated, do not add to new code: `@UiHint`, `@UiSection`, `@DashboardWidget` (UI is authored as
beans instead).

## Persistence & schema migration

The schema is derived from metadata and reconciled at boot — there are no hand-written migration
files for structural changes. The diff-based engine lives in
`onec-framework/src/main/java/com/onec/schema/`:

- **Modes** (`onec.schema.mode`): `apply` (default — safe changes run; destructive ones are logged
  and skipped unless `onec.schema.allow-destructive=true`), `plan` (log only), `validate` (fail on
  drift / unapplied migrations), `off`.
- **Diff inputs**: the desired `SchemaModel` (from the registry), the live DB
  (`INFORMATION_SCHEMA`), and the previous `SchemaSnapshot` (stored as JSON in
  `onec_schema_history`). The snapshot is how *type changes* and *removed entities* are detected on
  later boots.
- **Renames keep data**: declare the former name with `previousNames` on `@Catalog`/`@Document`/
  `@Attribute`; the engine emits a `RENAME_TABLE`/`RENAME_COLUMN` instead of drop+add.
- **Change kinds** (`SchemaChange.Type`): `CREATE_TABLE`, `RENAME_TABLE`, `RENAME_COLUMN`,
  `ADD_COLUMN`, `ALTER_COLUMN_TYPE`, `DROP_COLUMN`, `DROP_TABLE`. Drops are only ever proposed for
  objects present in the previous snapshot (never user-created tables).
- **Data migrations**: implement `AppMigration` (`version()` compared segment-wise, `migrate(MigrationContext)`)
  as a Spring bean. Each runs exactly once per database, in version order, inside a transaction,
  recorded in `onec_schema_history` (a unique constraint arbitrates concurrent starts).

Every applied change-set plus a fresh metadata snapshot is written to `onec_schema_history`.

## Posting engine

Posting turns a document into register movements. A document implements `Postable` and writes
movements in `handlePosting(PostingContext)`:

```java
@Override
public void handlePosting(PostingContext context) {
    var stock = context.movements(StockRegister.class);
    for (var line : items) {
        stock.addExpense(m -> { m.setProduct(line.getProduct()); m.setQuantity(line.getQuantity()); });
    }
}
```

`PostingEngine` (`onec-framework/src/main/java/com/onec/posting/PostingEngine.java`) runs
`beforeWrite` → `beforePost` → business-rule validation, then **inside its own JDBI transaction**
inserts movements, updates register totals, rejects negative `BALANCE` results, writes back computed
fields, and sets `_posted = true`. After commit it emits `@DomainEvent` outbox rows, calls
`afterPost`, and publishes a Spring `DocumentPostedEvent` (`DocumentUnpostedEvent` for unpost).

Two semantics that bite every integration:

- **Posting is its own transaction**, not enlisted in an ambient `@Transactional`. Save the document
  (let it commit), *then* post. Wrapping save+post in one `@Transactional` silently leaves
  `_posted = false`.
- **React to a post with a Spring `@EventListener` on `DocumentPostedEvent`** (full DI), not from
  inside `handlePosting`. The domain `AfterPostHandler.afterPost()` hook has no Spring access.

`GET /api/documents/{name}/{id}/posting-preview` (and the MCP `posting_preview` tool) dry-run the
movements without writing them.

## Query engine

`QueryEngine` (`onec-framework/src/main/java/com/onec/query/`) is a type-safe query layer over
catalogs, documents, and registers with `Ref`-navigation auto-joins. A declarative `QuerySpec` AST
(`select`/`where`/`groupBy`/`orderBy`/`totals`/`limit`/`offset`) is assembled by a fluent
`QueryBuilder`, rendered by a shared `SqlRenderer` (which also backs register virtual tables), and
executed via JDBI into untyped `Row`s or mapped DTOs. The `Q` helper builds type-safe paths from
method references, e.g. `Q.ref(SalesOrder::getCustomer, Customer::getName)` emits the join.

## Generic REST API

All endpoints are under `/api/**`, authenticated, and (for mutations) CSRF-protected. `{name}` is
the entity's **display/logical name** (e.g. `Properties`, not the class `Property`), matched
case-insensitively with spaces/underscores stripped. **There is no anonymous manifest endpoint** —
the only `/manifest` route is the desktop shell's `/api/desktop/manifest`; agents introspect the
model via the real generated endpoints below or the MCP `describe_metadata` tool. The read-response
contract (column-name keys, `{col}_display`/`{col}_ref` expansion, `__SECRET_SET__` redaction) is in
[HEADLESS_READ_API.md](HEADLESS_READ_API.md).

| Area | Endpoints (served by) |
| --- | --- |
| Catalogs | `GET /api/catalogs/{name}` (`?q=`/`?limit=` typeahead), `/{id}`, `/children?parent=`, `/tree`, `/{id}/related/{relatedName}`; `POST`/`PUT /{id}`/`DELETE /{id}` (ui-starter) |
| Documents | `GET /api/documents/{name}` (`?from=&to=`), `/{id}`, `/{id}/posting-preview`; `POST`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/post`, `POST /{id}/unpost` (ui-starter) |
| Registers | `GET /api/registers/{name}/movements`, `/balance`, `/turnover?from=&to=` (ui-starter) |
| List feed | `GET /api/list/catalogs/{name}`, `/api/list/documents/{name}` — paged/sorted/filtered data for grids (ui-starter) |
| Settings | `GET`/`PUT /api/settings` — `@Constant` values, ADMIN (ui-starter) |
| Actions | `POST /api/actions/{kind}/{name}/{key}` — authored toolbar/row/detail actions (ui-starter) |
| Media | `POST /api/media`, `GET /api/media/{key}` — uploads ([MEDIA_UPLOADS.md](MEDIA_UPLOADS.md)) (ui-starter) |
| Comments | `GET`/`POST /api/comments/{kind}/{name}/{id}`, `DELETE /api/comments/{commentId}` — per-entity discussion threads, gated on read access to the entity (ui-starter) |
| DivKit UI | `GET /api/divkit/{shell,home,menu,account,settings}` and `/api/divkit/{catalogs,documents}/{name}[/{id}|/new|/{id}/edit]`, `/api/divkit/registers/{name}` (ui-starter) |
| Theme/config | `GET /api/theme`, `GET /api/config`, `GET /api/branding` (ui-starter) |
| Events | `GET /api/events` — SSE stream of CRUD/posting changes (ui-starter) |
| Auth | `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` (auth-starter) |
| Import | `POST /api/import/{catalogs,documents}/{name}/csv[/preview]` (import-starter) |
| Desktop | `GET /api/desktop/ready`, `GET /api/desktop/manifest` (desktop-starter) |
| MCP | `POST /mcp` — streamable-HTTP MCP transport (mcp-starter) |
| Mail (dev) | `GET /onec/mail/preview[/{name}]`, `POST /onec/mail/events` webhook (mail-starter) |

> **SPA fallback gotcha:** any non-`/api` path returns `index.html` with HTTP 200 (React Router
> deep-linking). A mistyped URL "succeeds" with the SPA shell. Only `/api/**` produces real
> `404`/`401`/`403`. When debugging, hit API URLs, not page URLs.

## UI layer

The UI is authored as Spring beans, never as annotations on domain classes:

- **`Layout`** — navigation, shell (`NavStyle`), branding, persona (`profile()`), `roles`, and an
  optional `viewport()` (DESKTOP/TABLET/MOBILE). The default layout (`profile() == null`) is the
  back-office shell.
- **`Page`** — a route you compose (`compose(PageBuilder)`): `title`, `widget(...)` (count, metric,
  chart, calendar, list, kanban, or app-registered custom), `text`, `list`, `constants`, `custom`.
- **`EntityView`** — per-entity `list(ListSpec)` columns/filters and `fields(EntityConfigBuilder)`
  hints (`order`, `group`, `width`, `widget`, `format`, `hideInList/Form/Detail`, related lists,
  actions). **An entity is only visible in the UI if it has an `EntityView` for the active profile —
  the view layer is the allowlist.**

Server-side rendering uses **DivKit**: the controllers emit DivKit card JSON resolved for the
caller's persona, roles, theme, and viewport. The same contract drives the bundled React/Vite SPA
today and is intended to drive a native client later. The frontend lives in
`onec-ui-starter/src/main/frontend` and is built by Gradle (`buildFrontend`, Node 20) into
`static/ui/`. See [onec-ui-starter/README.md](../onec-ui-starter/README.md) for the full widget DSL
and `config(key,value)` reference.

## Auth & RBAC

`onec-auth-starter` contributes the `SecurityFilterChain` and picks a mode from `onec.auth.mode`:

- **`in-memory`** (default) — users from `onec.auth.users[*]`, session cookie + optional remember-me,
  JSON `POST /api/auth/login`, CSRF via `XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header.
- **`oidc`** — server-side OpenID Connect (Keycloak/Zitadel/custom); realm/client role mapping from
  the token via `onec.auth.oidc.*`; RP-initiated logout.
- **`resource-server`** — stateless JWT bearer validation, no session/CSRF.

`/api/**` requires authentication (except the public allowlist: `/error`, `/api/theme`,
`/api/config`, `/api/branding`, `/api/auth/login`, `/api/auth/me`, `/api/divkit/login`,
`/api/desktop/**`). **Per-entity RBAC is deny-by-default**: a catalog/document/register is invisible
and uneditable unless its `@AccessControl` read/write roles grant the caller; the `ADMIN` role is a
superuser. Override the whole thing by setting `onec.auth.enabled=false` and supplying your own
`SecurityFilterChain`.

## Integrations

- **MCP** (`onec-mcp-starter`) — a streamable-HTTP MCP server at `/mcp` (HTTP Basic, same users as
  the web UI), exposing tools generated from the registry and gated by RBAC: `describe_metadata`,
  `list_catalog`/`get_catalog`, `list_documents`/`get_document`, `register_balance`/`register_movements`,
  `create_*`/`update_*`/`delete_*` (gated by `onec.mcp.writes-enabled`), `posting_preview`,
  `post_document`/`unpost_document` (gated by `onec.mcp.posting-enabled`). This is the
  agent-readable model surface that replaced the old idea of an HTTP manifest.
- **Import** (`onec-import-starter`) — CSV preview + import for catalogs/documents through the same
  command services as the UI (so validation, numbering, posting, events all apply); modes
  `CREATE_ONLY` / `UPSERT_BY_CODE` (catalogs) / `UPSERT_BY_NUMBER` (documents), dotted mapping keys
  for tabular sections, optional `groupBy` + `postAfterImport` for documents.
- **Kafka** (`onec-kafka-starter`) — drains the `onec_outbox` to a Kafka topic as CloudEvents via
  `OutboxRelay.relayPending()` (call it from your own `@Scheduled`); optional de-duplicating inbox
  dispatches to `EventHandler` beans; `RemoteRefClient` resolves references against other services.
- **Mail** (`onec-mail-starter`) — `@MailTemplate` on a domain class, rendered by Thymeleaf,
  dispatched by a pluggable `MailDispatcher` (smtp/http/file/log/failover), optionally queued in
  `onec_mail_outbox` with scheduled relay, retry/backoff, and per-recipient suppression.
- **Print** (`onec-print-starter`) — `@PrintTemplate` → `PrintService.render(...)` returns
  HTML/PDF bytes (Flying Saucer / OpenPDF; PDF templates must be valid XHTML). No endpoint; expose
  the bytes from your own controller.
- **Desktop** (`onec-desktop-starter` + `onec-desktop-gradle-plugin`) — a `DesktopApp` bean
  declares the window (config-as-code); the starter serves `/api/desktop/{ready,manifest}` and
  relocates the H2 file + session store under the per-user home; the Gradle plugin
  (`id("com.onec.desktop")`, task `packageDesktop`) jlinks a runtime and runs `cargo tauri build`.

## Events & outbox

Every write — through the generic controllers **and** through `repository.save(...)` — publishes a
Spring `EntityChangedEvent(changeType, entityType, entityName, id, naturalKey)`
(`onec-framework/src/main/java/com/onec/events/EntityChangedEvent.java`). It drives the `/api/events`
SSE stream and lets server-side consumers (cache revalidation, search indexing) react to a specific
resource instead of polling. `@DomainEvent` declarations append to the transactional `onec_outbox`;
`onec-kafka-starter` relays those rows when you want cross-service streaming.

The SSE fan-out is in-JVM by default — fine for one node. Add `onec-cluster-starter` (see below) to
make `EntityChangedEvent`s reach browsers on **every** node of a scaled-out deployment.

## Scaling out (horizontal)

Running more than one instance behind a load balancer needs these, beyond a shared database:

- **Live-UI events across nodes** — add `onec-cluster-starter`. It relays each `EntityChangedEvent`
  over a pluggable `ClusterEventBus` (`com.onec.cluster`, an SPI swappable like `MediaStorage`);
  the default uses Postgres `LISTEN`/`NOTIFY` (no extra infrastructure) and is a no-op on H2. A
  received event is pushed straight to the local SSE stream and is **never** re-published as a Spring
  event, so business `@EventListener`s (cache, search, post-hooks, the kafka outbox) still run exactly
  once, on the node that made the change. `DocumentPostedEvent`/`DocumentUnpostedEvent` are node-local
  by design; their cross-node *visibility* rides on the `posted`/`unposted` `EntityChangedEvent`. To
  swap in Kafka/Redis, expose your own `ClusterEventBus` bean (`@ConditionalOnMissingBean`).
- **Schema apply** — every node runs the boot-time diff/migration. On Postgres it is serialized by a
  session-level advisory lock, so one node applies DDL while the others wait and then re-run against
  the now-current schema (idempotent via the diff + `onec_schema_history`). H2 is single-node and
  skips the lock.
- **Auth** — set a stable `onec.auth.session.remember-me.key`; a blank key fails fast (per-node random
  keys make cookies non-portable behind a load balancer). For single-node/dev, opt in with
  `onec.auth.session.remember-me.allow-ephemeral-key=true`. Sessions are in-memory servlet sessions,
  so use sticky routing or a shared session store.
- **Media** — `FilesystemMediaStorage` is per-node; supply a shared `MediaStorage` bean (object store)
  so uploads are reachable from any node.
- Already cluster-safe: **JobRunr** scheduled jobs (DB-backed leader election), `onec_schema_history`
  idempotency, and the "update available" notice (each node polls independently and converges).

## Build, versioning, publishing

- **Toolchain**: Java 21 (pinned via Gradle toolchain), Spring Boot 3.4.x, Gradle wrapper is the
  source of truth. Build everything with `./gradlew clean check`; verify consumable artifacts with
  `./gradlew publishToMavenLocal`.
- **Publishing**: the vanniktech maven-publish plugin publishes to the Maven Central Portal
  (`SONATYPE_HOST=CENTRAL_PORTAL`, `SONATYPE_AUTOMATIC_RELEASE=true`). Pushing a `vX.Y.Z` tag runs
  `clean check`, signs, uploads, auto-releases, and creates a GitHub release; `-rcN` tags publish
  pre-releases. Consumers need only `mavenCentral()` and the `io.github.onec-erp:*` coordinates.
  Maven Central does not allow replacing a released version — a bad tag is permanent.

## Open-core boundary

The framework is open-core. Everything in this repo (Maven group `io.github.onec-erp`) is
Apache-2.0, **including authentication and OIDC/SSO** (`onec-auth-starter`). Separately licensed
commercial connectors (Maven group `com.onec.enterprise`: Guesty, SES.HOSPEDAJES, Tochka) live in
the private [onec-enterprise](https://github.com/onec-erp/onec-enterprise) repo under the onec
Commercial License and consume the core as published Maven artifacts. An enterprise connector is a
Spring Boot auto-configuration starter that wraps an external API; the framework metadata, posting,
and UI live in the *consuming application*, not in the connector. The boundary and extraction plan
are in [docs/licensing/MODULE-SPLIT-PLAN.md](licensing/MODULE-SPLIT-PLAN.md).

## Community extensions

The same starter mechanism is open to anyone — community extensions are first-class, not a fork.
There are four extension surfaces: **connectors** (auto-config starters wrapping an external
system), **SPI implementations** (`MediaStorage`, `MailDispatcher`, an additive
`AuthMethodsContributor` login button, custom `SecurityFilterChain`/`UserDetailsService`, Kafka
`EventHandler`), **UI** (`Page`/`Layout`/`EntityView` beans and custom widgets/actions), and Claude
**skills/plugins** (via [.claude-plugin/marketplace.json](../.claude-plugin/marketplace.json)).

The contributor-facing how-to — the starter shape, the conventions that keep `io.github.onec-erp`
and the `com.onec.*` packages reserved, and a definition of done — is in
[EXTENDING.md](EXTENDING.md). Community-built integrations are cataloged in
[INTEGRATIONS.md](../INTEGRATIONS.md), generated from the machine-readable
[`community/registry.json`](../community/registry.json) by the `generateIntegrationsDoc` Gradle task.
