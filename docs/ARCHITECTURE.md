# onno-framework Architecture

A code-grounded map of how the framework fits together: the boot pipeline, each subsystem, the
generated runtime surface, and the module/licensing boundaries. This is the reference companion to
the modeling playbook in [AGENTS.md](../AGENTS.md) and the consumer guide in
[BUILDING_ERPS_WITH_AGENTS.md](../BUILDING_ERPS_WITH_AGENTS.md). For the exhaustive list of
`onno.*` properties see [CONFIGURATION.md](CONFIGURATION.md).

> **Keep this current.** When you change a public annotation, base class, repository contract,
> endpoint, auto-configuration property, or module boundary, update this file (and the other docs it
> cross-references) in the same change. See [Keeping docs in sync](../AGENTS.md#keeping-docs-in-sync).

## The core idea

You describe persisted business data as **typed Java metadata** — `@Catalog`, `@Document`,
`@TabularSection`, `@AccumulationRegister`, `@InformationRegister`, `@Enumeration`, `@Constant`,
and scheduled jobs — and
the framework generates everything downstream from that model: the database schema, repositories, a
generic REST API, a server-driven UI, an MCP tool surface for AI agents,
and migration history. You do not hand-write tables, DTOs, or CRUD controllers. Behaviour that *is*
code — posting rules, validation, lifecycle hooks, UI authoring — is plain, refactorable,
compiler-checked Java, never string-mapped configuration.

The `su.onno.process` model applies the same typed-Java principle to durable business-process routes;
the starter persists instances/work and the UI starter exposes an authenticated human task inbox.

Java packages are always `su.onno.*`. The published Maven group is `su.onno` (core,
Apache-2.0) and `su.onno.enterprise` (commercial connectors). The desktop Gradle plugin id is
`su.onno.desktop`.

## Modules

| Module | Group | Role |
| --- | --- | --- |
| `onno-framework` | `su.onno` | Core: annotations, metadata scanners + registry, schema diff/migration, JDBI persistence, posting engine, typed process contracts/schema, repository contracts, events, outbox, UI model (`Layout`/`Page`/`EntityView`). |
| `onno-framework-starter` | `su.onno` | Spring Boot auto-configuration that wires the core: metadata registry, repositories, schema initializer, posting service, number generation, secret cipher, background jobs. |
| `onno-ui-starter` | `su.onno` | Generic REST controllers under `/api/**`, the DivKit server-driven UI layer, the bundled React/Vite SPA, media uploads, SSE event stream, comment threads, per-user notifications. |
| `onno-observability-starter` | `su.onno` | Opt-in privacy-safe business and UX telemetry, a bounded non-blocking exporter, and deployment-version context. Included transitively by the UI starter but disabled by default. |
| `onno-auth-starter` | `su.onno` | Spring Security: in-memory, OIDC/SSO, and resource-server (JWT) modes; JSON login/logout; CSRF; per-request principal. |
| `onno-mcp-starter` | `su.onno` | Model Context Protocol server exposing the model + CRUD + register reads + posting as AI-agent tools, generated from the registry. |
| `onno-import-starter` | `su.onno` | CSV import (preview, mapping, upsert, dry-run, document grouping) through the same command path as the UI. |
| `onno-cluster-starter` | `su.onno` | Cross-node delivery of `ClusterEvent`s (entity changes, process-task invalidations, presence, and notifications) for horizontal scale-out via a pluggable `ClusterEventBus` SPI (default Postgres `LISTEN`/`NOTIFY`; no-op on H2). Keeps the SSE live UI, task inboxes, collaboration markers, and notification delivery in sync across instances. |
| `onno-kafka-starter` | `su.onno` | Transactional outbox → Kafka relay as CloudEvents, de-duplicating inbox, service registry, remote `Ref` client. |
| `onno-desktop-starter` | `su.onno` | Runs the app as a native desktop window (Tauri shell), config-as-code window manifest, H2/session relocation. |
| `onno-desktop-gradle-plugin` | (`su.onno.desktop` plugin) | Packages a Spring Boot app into a native `.dmg`/`.msi`/`.AppImage` via jlink + Tauri. |
| `onno-widgets-gradle-plugin` | (`su.onno.widgets` plugin) | Compiles consumer-authored React widgets (`src/main/widgets/*.tsx`) into onno UI plugin modules via managed Node + esbuild; bundles the `@onno/widget-sdk` authoring package. |
| `onno-widget-sdk` | (npm `@onno/widget-sdk`) | The authoring surface for custom widgets — types, hooks, UI primitives, and a read-only data client that resolve to the host SPA at runtime. |
| `example` | (not published) | A vacation-rentals ERP that exercises every concept; the canonical reference app. |
| `onno-guesty-starter`, `onno-hospedajes-starter`, `onno-tochka-starter` | `su.onno.enterprise` | Commercial vertical connectors in the separate [onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo. |

## Boot pipeline

`onno-framework-starter` auto-configuration (`OnnoAutoConfiguration`,
`onno-framework-starter/src/main/java/su/onno/spring/OnnoAutoConfiguration.java`) runs after
`DataSourceAutoConfiguration` and assembles the runtime in this order:

1. **Resolve scan packages.** `onno.scan-packages` if set, otherwise Spring Boot's
   auto-configuration base packages (the package of your `@SpringBootApplication`). There is **no**
   `onno.base-packages` property.
2. **Scan metadata.** Reflection scanners read the annotations and build immutable descriptors
   (`CatalogDescriptor`, `DocumentDescriptor`, `AccumulationRegisterDescriptor`, …) into the
   `MetadataRegistry` (`onno-framework/src/main/java/su/onno/metadata/`).
3. **Migrate the schema.** `SchemaInitializer` derives the desired schema from the registry, diffs
   it against the live database + the last snapshot in `onno_schema_history`, and applies/plans/
   validates per `onno.schema.mode`. Versioned `AppMigration` beans run once each, in version order.
4. **Wire persistence + behaviour.** JDBI, Spring Data JDBC repositories, register persistence,
   `PostingService`, `NumberGenerator`, `SecretCipher`, callbacks (id generation,
   numbering, secret encryption, change-event publishing, `isNew` reset), background jobs, and the
   `Layout`/`Page`/`EntityView` UI model beans.
5. **Layer on optional starters.** `onno-ui-starter` adds the REST + DivKit + SPA surface;
   `onno-auth-starter` adds the security chain; the integration starters add their endpoints and
   beans, each gated by an `onno.<module>.enabled` flag (default on).

## Domain concepts → annotations

The modeling guidance lives in [AGENTS.md](../AGENTS.md); the full annotation reference with every
attribute and default lives in the skill cheat sheet
([`onno-plugin/skills/onno/reference/cheatsheet.md`](../onno-plugin/skills/onno/reference/cheatsheet.md)).
In brief:

- **`@Catalog`** — stable reference data (Products, Customers). `codeLength`, `codePrefix`,
  `autoNumber`, `hierarchical`, `previousNames`, `context`. Base class `CatalogObject`
  (`id`, `code`, `description`, `deletionMark`, `folder`, `parent`, `@Version version`, `isNew`).
- **`@Document`** — business events (Sales Order, Invoice). `numberPrefix`, `numberLength`,
  `autoNumber`, `previousNames`, `context`. Base class `DocumentObject`
  (`id`, `number`, `date`, `posted`, `deletionMark`, `version`, `isNew`).
- **`@TabularSection`** — line-item collections on a document; rows extend `TabularSectionRow`.
  Each section owns one distinct concrete row class because Spring Data JDBC maps that class to one
  child table; startup rejects reuse across documents or sections before scan order can select the
  wrong table. Common fields/behavior belong in a shared base row class.
- **`@AccumulationRegister`** — ledgers, `type = BALANCE | TURNOVER`; `BALANCE` rejects negative
  resource totals by default and `allowNegative = true` opts a debt/overdraft register out;
  `postingOrder = CHRONOLOGICAL` makes a backdated post/unpost restore and repost later affected
  documents in date order;
  `@Dimension` keys and `@Resource` numbers; rows extend `AccumulationRecord` (`period`, `active`,
  `documentRef`, `movementType = RECEIPT | EXPENSE`). Enum dimensions use the same deterministic
  UUID representation as enum attributes throughout movement insertion, totals, filters, and typed
  reads.
- **`@InformationRegister`** — facts by dimension over time, `periodicity = NONE|DAY|MONTH|QUARTER|YEAR`;
  rows extend `InformationRecord`.
- **`@Enumeration`** (on a Java `enum`; `title` for the type's display name, `@EnumLabel` on a
  constant for its localized value label — both fall back to the name — plus an optional
  `@EnumLabel(color="#…")` that renders the value as a colored status pill), **`@Constant`** (singleton
  setting), **`@ScheduledJob`**
  (`cron`) / `@Scheduled` background jobs, **`@DomainEvent`** (outbox), **`@AccessControl`**
  (`readRoles`/`writeRoles`), **`@Attribute`** (`required`, `length`, `precision`/`scale`, `secret`,
  validation `min`/`max`/`pattern`/`email`, `previousNames`).
- **`Ref<T>`** (`su.onno.types.Ref`) — a typed `(Class<T>, UUID)` reference, stored as a UUID
  column; resolved with `RefResolver`.
- **`PolyRef` + `@RefTargets`** — an explicitly allowlisted reference to one of several catalog or
  document types. It is stored as `fully.qualified.JavaType|UUID`; metadata and read responses retain
  the concrete target so generated forms can select and link the right entity type.

### Durable typed business processes

The `su.onno.process` package models long-running coordination that does not belong in a document
status field:

- `ProcessDefinition<P,S>` is an application Spring bean with a stable persisted key, a declared
  payload class, and one lazily built, validated route graph. `P` is the typed payload; `S` is an
  enum implementing `ProcessStepKey`.
- `ProcessGraph<P,S>` creates `HumanTaskNode<P,S,O>` and `EndNode<P,S>` handles. Routes connect those
  handles directly; no string node lookups or expression language are involved.
- `HumanTask<P,O>` declares an enum outcome type, a payload-dependent title, and a
  `TaskAssignment` of candidate stable identities/roles. `TaskAssignment.identities(Ref<?>...)`
  keeps employee routing typed and stable across login/email changes. Optional `subject(payload)`
  returns a typed catalog/document `Ref<T>` that becomes a durable task-to-record link;
  `subjectLabel(payload)` stores its human-readable snapshot for the inbox.
  Definition validation requires every enum outcome to
  have a transition and rejects duplicate keys, cross-graph connections, missing start targets,
  and unreachable nodes.
- `JdbcProcessEngine` persists payload JSON, instances, work items, transition history, and ordered
  task audit events in the framework-managed `onno_process_*` tables. Claim, delegate, and complete
  use row locks plus optimistic
  versions. A claimed item is private to its assignee; `ADMIN` bypasses candidate/assignee checks.
- `ProcessController` is the authenticated boundary: definitions/start/instance reads plus a
  role-scoped task inbox and claim/delegate/history/complete commands. The `tasks` page widget
  resolves delegation targets and their live avatar/photo through the layout identity catalog,
  opens typed task subjects in the shell's adjacent detail pane, and provides the same loop to
  humans. `JdbcProcessEngine` publishes `ProcessTasksChangedEvent` only after its JDBI
  transaction commits; the UI routes a payload-free `tasks-changed` SSE invalidation to affected
candidate users/roles and admins, so every open inbox refetches automatically.

`HumanTask.assignment(payload)` is the automatic-routing seam. It may use an injected application
service and return `TaskAssignment.identities(selectedEmployeeRef)`. The employee record UUID is
the stable task owner; login and display name are mutable snapshots only. The authenticated
principal is resolved through `Layout.identity(...)` to that same UUID.

The typed Java definition remains the source of truth. Stable definition/step keys are persisted;
HTTP outcomes are enum constant names validated against the active task's declared enum. Timers,
automatic/decision/fork/join/subprocess nodes, cancellation, and definition-version migration remain
future work. Documents still record business events and posting still writes register movements; a
process coordinates work across them.

```java
enum PurchaseStep implements ProcessStepKey {
    MANAGER_APPROVAL, FINANCE_APPROVAL, COMPLETED, REJECTED
}

enum ApprovalOutcome { APPROVED, REJECTED }

final class ApprovalTask implements HumanTask<PurchaseRequest, ApprovalOutcome> {
    public Class<ApprovalOutcome> outcomeType() { return ApprovalOutcome.class; }
    public TaskAssignment assignment(PurchaseRequest payload) {
        return TaskAssignment.roles("MANAGER");
    }
}

@Component
final class PurchaseApproval
        extends ProcessDefinition<PurchaseRequest, PurchaseStep> {
    PurchaseApproval() { super("purchase-approval", PurchaseRequest.class); }
    public TaskAssignment startAssignment(PurchaseRequest payload) {
        return TaskAssignment.roles("MANAGER");
    }

    protected void define(ProcessGraph<PurchaseRequest, PurchaseStep> graph) {
        var manager = graph.human(PurchaseStep.MANAGER_APPROVAL, new ApprovalTask());
        var finance = graph.human(PurchaseStep.FINANCE_APPROVAL, new ApprovalTask());
        var completed = graph.end(PurchaseStep.COMPLETED);
        var rejected = graph.end(PurchaseStep.REJECTED);

        graph.start().to(manager);
        manager.on(ApprovalOutcome.APPROVED).to(finance);
        manager.on(ApprovalOutcome.REJECTED).to(rejected);
        finance.on(ApprovalOutcome.APPROVED).to(completed);
        finance.on(ApprovalOutcome.REJECTED).to(rejected);
    }
}
```

**Soft delete.** `deletionMark` on `CatalogObject`/`DocumentObject` is a tombstone, not a hard
delete: "delete" sets it `true` and the row stays (the UI/REST read layer hides rows where
`_deletion_mark = true`). The inherited repository finders — `findAll()`, `findById()`,
`findByCode()`, `findByNumber()`, `findByDateBetween()` — **deliberately still return marked rows**,
because `RefResolver` must resolve a `Ref<T>` to a deleted target (an old document still shows
"Customer X") and restore/admin must reach them. **Business logic must not count deleted rows** —
auth/admission, posting, totals, validation, picker option lists. Use the soft-delete-aware finders
on `CatalogRepository`/`DocumentRepository` — `findAllActive()`, `findActiveById(UUID)`,
`findActiveByCode(String)` / `findActiveByNumber(String)`, `findActiveByDateBetween(from, to)`
(backed by derived `findByDeletionMarkFalse()`-style queries) — or filter `!isDeletionMark()`.

Because Spring Data JDBC has no global soft-delete filter (no JPA `@Where` equivalent), a
**boot-time guardrail** catches the cases people forget: at startup every
`CatalogRepository`/`DocumentRepository` is scanned, and any *consumer-declared* finder that returns
entities but isn't deletion-scoped (no `…AndDeletionMarkFalse` predicate, no `deletion_mark` in its
`@Query`, not delegating to `findActive*`) is flagged. Configure with `onno.repository.deletion-check`
= `warn` (default — logs), `strict` (fails startup; good for CI), or `off`. A finder that *must* see
tombstones (a `Ref`-resolution or restore/admin lookup) declares it with
`@su.onno.repository.IncludesDeleted` to opt out.

UI is authored with `Layout`, `Page`, and `EntityView` beans. Domain annotations do not carry UI
placement or field-display hints.

Authored Java uses the shared serializable `Field<E,V>` getter token wherever a value denotes a
model field. `ListSpec<E>`, `EntityConfigBuilder<E>`, widget field methods, related lists,
form-validation dependencies, `ActionRow`, register queries, and `Q` all accept method references
such as `Order::getStatus`. `Fields.name(...)` resolves the JavaBean property and boundary resolvers
map it to the physical API/storage column. This keeps Java source compiler-checked while preserving
string metadata on the wire. String overloads are unsafe compatibility/dynamic escape hatches.
Strings remain correct for semantic identifiers (routes, roles, action/process keys, labels) and
for intentionally parsed expression text such as a widget filter.

## Persistence & schema migration

The schema is derived from metadata and reconciled at boot — there are no hand-written migration
files for structural changes. The diff-based engine lives in
`onno-framework/src/main/java/su/onno/schema/`:

- **Modes** (`onno.schema.mode`): `apply` (default — safe changes run; destructive ones are logged
  and skipped unless `onno.schema.allow-destructive=true`), `plan` (log only), `validate` (fail on
  drift / unapplied migrations), `off`.
- **Diff inputs**: the desired `SchemaModel` (from the registry), the live DB
  (`INFORMATION_SCHEMA`), and the previous `SchemaSnapshot` (stored as JSON in
  `onno_schema_history`). The snapshot is how *type changes* and *removed entities* are detected on
  later boots.
- **Renames keep data**: declare the former name with `previousNames` on `@Catalog`/`@Document`/
  `@Attribute`; the engine emits a `RENAME_TABLE`/`RENAME_COLUMN` instead of drop+add.
- **Change kinds** (`SchemaChange.Type`): `CREATE_TABLE`, `RENAME_TABLE`, `RENAME_COLUMN`,
  `ADD_COLUMN`, `ALTER_COLUMN_TYPE`, `DROP_COLUMN`, `DROP_TABLE`. Drops are only ever proposed for
  objects present in the previous snapshot (never user-created tables).
- **Data migrations**: implement `AppMigration` (`version()` compared segment-wise, `migrate(MigrationContext)`)
  as a Spring bean. Each runs exactly once per database, in version order, inside a transaction,
  recorded in `onno_schema_history` (a unique constraint arbitrates concurrent starts).

Every applied change-set plus a fresh metadata snapshot is written to `onno_schema_history`.

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

`PostingEngine` (`onno-framework/src/main/java/su/onno/posting/PostingEngine.java`) runs
`beforeWrite` → `beforePost` → business-rule validation, then **inside its own JDBI transaction**
atomically claims an existing unposted document, inserts movements, updates register totals, rejects
negative `BALANCE` results unless that register declares `allowNegative = true`, and writes back
computed fields. Posting a missing or already-posted document is rejected before any movement is
persisted, so retries cannot double-count registers. After commit
it emits `@DomainEvent` outbox rows, calls `afterPost`, and publishes a Spring
`DocumentPostedEvent` (`DocumentUnpostedEvent` for unpost).

For a register declared with `postingOrder = PostingOrder.CHRONOLOGICAL`, posting or unposting a
backdated document discovers later active documents through that register (including documents
linked through other chronological registers), reverses them newest-first, applies the requested
change, and reposts them oldest-first. The restoration uses one serializable JDBI transaction, and
register balance reads made by `handlePosting` see that same transaction. Restored documents do not
emit duplicate external post events; only the requested command does.

Two semantics that bite every integration:

- **Posting is its own transaction**, not enlisted in an ambient `@Transactional`. Save the document
  (let it commit), *then* post. Wrapping save+post in one `@Transactional` silently leaves
  `_posted = false`.
- **Posting an already-posted document is rejected.** Unpost it before intentionally recalculating
  its movements; a repeated core `post(...)` call never duplicates register totals.
- **React to a post with a Spring `@EventListener` on `DocumentPostedEvent`** (full DI), not from
  inside `handlePosting`. The domain `AfterPostHandler.afterPost()` hook has no Spring access.

`GET /api/documents/{name}/{id}/posting-preview` (and the MCP `posting_preview` tool) dry-run the
movements without writing them.

## Query paths

There is no universal query DSL. Catalog and document API reads use the UI starter's dedicated
`CatalogQueryService` and `DocumentQueryService`, including keyset pagination and display-value
resolution. Typed business reads of accumulation registers use `RegisterRepository.query()`;
generic UI and MCP register projections use `RegisterQueryService`. The small
`su.onno.query` package contains only shared cursor/keyset and SQL-rendering utilities used by those
runtime paths.

## Generic REST API

Read rows use snake_case storage columns; writes use camelCase model field names and are partial
(full contract: [HEADLESS_READ_API.md](HEADLESS_READ_API.md)). Temporal values are normalized at the
read/write boundary: `LocalDate` is `yyyy-MM-dd`, and `LocalDateTime` is offset-free ISO wall time.
Offset-bearing inputs are accepted without shifting their local fields. This keeps PostgreSQL,
H2, generated forms, and headless clients on one round-trip-safe representation.

All endpoints are under `/api/**`, authenticated, and (for mutations) CSRF-protected. `{name}` is
the entity's **display/logical name** (e.g. `Properties`, not the class `Property`), matched
case-insensitively with spaces/underscores stripped. **There is no anonymous manifest endpoint** —
the only `/manifest` route is the desktop shell's `/api/desktop/manifest`; agents introspect the
model via the real generated endpoints below or the MCP `describe_metadata` tool. The read-response
contract (column-name keys, `{col}_display`/`{col}_ref` expansion, `__SECRET_SET__` redaction) is in
[HEADLESS_READ_API.md](HEADLESS_READ_API.md).

Durable process routes are `GET /api/process-definitions`,
`POST /api/processes/{definitionKey}`, `GET /api/processes/{id}` plus `/history`, and
`GET /api/tasks` with task claim, delegate, history, and complete endpoints. Start authorization comes from
`ProcessDefinition.startAssignment(payload)`; inbox/task authorization comes from each
`HumanTask.assignment(payload)`. Actors always come from the authenticated principal.

| Area | Endpoints (served by) |
| --- | --- |
| Catalogs | `GET /api/catalogs/{name}` (`?q=`/`?limit=` typeahead; `?filter=` narrows it with a WidgetFilter predicate — the cascading ref picker's resolved `refFilter`), `/{id}`, `/children?parent=`, `/tree`, `/{id}/related/{relatedName}`; `POST`/`PUT /{id}`/`POST /{id}/duplicate`/`DELETE /{id}`; `POST /validate` / `POST /{id}/validate` — dry-run the write lifecycle (constraints + hooks + business rules) without persisting, always 200 with `{valid, fieldErrors, formErrors}` — the form's live as-you-type validation (ui-starter) |
| Contextual forms | `POST /api/ref-options/search` — Ref search with live header/row/id context and application badges, disable reasons, or filtering; `POST /api/form-validation/{kind}/{name}/{key}` — dependency-aware advisory `ERROR`/`WARNING`/`INFO` feedback for new/edit forms (write-authorized; ui-starter) |
| Documents | `GET /api/documents/{name}` (`?from=&to=`; `?q=`/`?limit=` typeahead, `?filter=` narrowing it like catalogs), `/{id}`, `/{id}/posting-preview`; `POST`, `PUT /{id}`, `POST /{id}/duplicate`, `DELETE /{id}`, `POST /{id}/post`, `POST /{id}/unpost`; `POST /validate` / `POST /{id}/validate` — same dry-run validate as catalogs, tabular rows included (ui-starter) |
| Registers | `GET /api/registers/{name}/movements`, `/balance`, `/turnover?from=&to=` (ui-starter) |
| List feed | `GET /api/list/catalogs/{name}`, `/api/list/documents/{name}` — **keyset-paginated** grid data by default: pass `?cursor=&limit=` and read back `{rows, nextCursor, hasMore}` (constant-time at any depth, no skip/dup on shifting data); `?count=exact\|estimate` adds a total. `?offset=` selects the legacy `{total, offset, rows}` mode. `?ids=` returns just those rows (the grid's single-row live patch); `?filter=` applies a safe `WidgetFilter` predicate server-side (a dashboard widget's `config("filter", …)`, e.g. `status != 'DRAFT'`). `GET /api/list/registers/{name}/movements`, `/balance` — register data for the virtualized register surface, same cursor envelope (`?offset=` selects the legacy page) and same declarative filter params (`eq`/`in`/`like`/`prefix`/`ge`/`le`, validated against the register's columns); movement rows carry a localized `_movement_type_display` + `_movement_type_color` (ui-starter) |
| List grouping | `GET /api/list/{kind}/{name}/groups?groupBy=&granularity=&agg=fn,col&{q,filters}` — backend `GROUP BY`: `{groups: [{label, color?, count, values[], expand[]}], capped}`. One header per value (or per `day`/`month`/`year` bucket for a date column), each carrying an `expand` filter the grid replays on the list feed to load the group's rows. Same WHERE as the flat list; headers cap at 200 (ui-starter) |
| Widget aggregate | `GET /api/list/{kind}/{name}/aggregate?metric=&field=&groupBy=&groupByDate=&seriesBy=&filter=&dateField=&from=&to=` — server-side `GROUP BY` for the chart/stat/sparkline/gauge widgets: `{buckets: [{key, label?, series?, seriesLabel?, value, value2?}], truncated, span?}`, O(buckets) over the wire instead of the entity's whole table (#199). `groupByDate` (`minute…month`) buckets a timestamp via `DATE_TRUNC`; blank `groupBy` yields one grand-total bucket; `metric2`/`field2` add a combo chart's second measure; enum/Ref bucket values carry a resolved `label`; `span` is the windowed MIN/MAX of `dateField` (granularity auto-sizing). Date-bucketed axes always zero-fill empty periods with `{key, value: 0}` fillers over the window — or between the first/last data when unbounded (#246). Buckets (filled spine included) cap at 1000 (`truncated: true`) (ui-starter) |
| Settings | `GET`/`PUT /api/settings` — `@Constant` values, ADMIN (ui-starter) |
| Actions | `POST /api/actions/{kind}/{name}/{key}` — authored toolbar/row/detail actions; an action declaring `.form(...)` first opens the canonical dialog client-side and POSTs `inputs` (read via `ActionContext.input(key)`). Form metadata (`title`, description, labels, tone, icon, `SM`/`MD`/`LG`) rides in the action descriptor. `GET …/{key}/form?id=` returns server-computed opening values (`.formDefaults(ctx -> …)` → `{values, rows}`). `ActionRejectedException` maps to HTTP 422 `ActionFeedback` (`severity`, `presentation`, title/message/details, `fieldErrors`, `formErrors`, `dismissLabel`, `keepFormOpen`); forms retain input and render errors, while no-form rejection can use the accessible feedback dialog. Successful `ActionResult.toast(ActionToast…)` and `ActionResult.dialog(ActionDialog…)` provide fluent structured feedback; legacy `message/refresh/navigate/open/redirect` remains compatible. `POST …/{key}/batch` runs `{ids:[…]}` once (≤500, `{ok, failed, total, feedback?}`; the first typed per-row rejection is preserved); `POST /api/divkit/page-action?route=&key=` runs a page button. All POSTs honour `onno.ui.read-only` and declared `.roles(...)` (#227). `POST /api/{catalogs|documents}/{name}/batch-delete` is bulk DELETE (ui-starter) |
| Media | `POST /api/media`, `GET /api/media/{key}` — authenticated uploads; served with `nosniff`, safe raster images inline and other types as attachments ([MEDIA_UPLOADS.md](MEDIA_UPLOADS.md)) (ui-starter) |
| Comments | `GET`/`POST /api/comments/{kind}/{name}/{id}`, `POST /api/comments/{commentId}/reactions`, `DELETE /api/comments/{commentId}` — discussion threads with replies (`parentId`) and grouped reactions, opt-in per entity via `EntityView.comments()` (404 otherwise), gated on read access to the entity; `createdAt`/`editedAt` are zone-qualified instants (`…Z`) (ui-starter) |
| Mentions | `GET /api/mentions?q=[&kind=people\|catalogs\|documents]` — comment typeahead over readable records; the UI uses `@` for people mentions (`kind=people` narrows to the `Layout.identity(...)` catalog, falling back to all catalogs when no identity link is configured) and `#` to reference any record — no `kind` sweeps documents and catalogs alike, searchable by name or code. Suggestions carry a secondary `hint` (a person's `email` attribute, a catalog record's code, a document's `yyyy-MM-dd` date). `GET /api/mentions/resolve?kind&name&id` resolves one triple to its live display plus a `person` flag (same per-viewer read gate) — the compose box uses it to swap a pasted internal `/ui/...` record URL for an `@` (person) or `#` (anything else) mention. Bodies carry `@[Display](kind/name/id)` / `#[Display](kind/name/id)` tokens resolved live; readable `@` mentions publish `EntityMentionedEvent` (consumed by notifications; additive via `@EventListener`) (ui-starter) |
| Notifications | `GET /api/notifications[?unread&cursor]` — the caller's keyset-paginated timeline `{items, nextCursor, hasMore, unreadCount}`; `POST /api/notifications/{id}/read` and `POST /api/notifications/read-all` mark read. Every call is scoped to the caller's identity (no cross-user reads). Rows persist in the framework-owned `onno_notifications` table; new ones push over the `notification` SSE event (routed by recipient, relayed across nodes over the `ClusterEventBus`). Built-in producers: comment mentions and record assignment (`@AssigneeField`); apps add more by calling `NotificationService.notify`. Gated by `onno.notifications.*` (ui-starter) |
| DivKit UI | `GET /api/divkit/{shell,home,menu,account}` and `/api/divkit/{catalogs,documents}/{name}[/{id}|/new]`, `/api/divkit/registers/{name}` (ui-starter). `/{id}` is the combined record surface — the editable form (disabled for read-only viewers) with the record-level actions in its header; `/{id}/edit` is kept as a back-compat alias. `GET /api/divkit/{*route}` is the catch-all page endpoint: any route with a registered `Page` bean renders (a custom dashboard/report, **including `/settings`** — there is no built-in Settings surface), otherwise `404`. An authored `Page` at a default surface route (`/catalogs/{name}`, `/documents/{name}`, `/registers/{name}`) overrides that surface's default list/report |
| Theme/config | `GET /api/theme`, `GET /api/config`, `GET /api/branding` (ui-starter) |
| Events | `GET /api/events` — SSE stream of CRUD/posting changes, `tasks-changed` inbox invalidations, plus `presence` viewer-set updates (ui-starter); filtered per subscriber by entity read access or process-task audience (#190) |
| Presence | `POST /api/presence` (body `{path, action}`) — mark presence on any route (`enter`/`heartbeat`/`leave`); the server derives the identity from the path (a record, an entity list, or any page/dashboard), gating entity routes on read access while a page is visible to any signed-in user. Identity from the session, heartbeat-kept + TTL-expired, relayed across nodes over the `ClusterEventBus`. `GET /api/presence` — the ambient snapshot (routes the caller may see) that seeds the client store behind the tab/row/sidebar collaborator avatars (the viewer's photo, initials fallback); kept live by `presence` SSE deltas (ui-starter) |
| Auth | `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`, `GET /api/auth/csrf` (auth-starter) |
| Import | `POST /api/import/{catalogs,documents}/{name}/csv[/preview]` (import-starter) |
| Desktop | `GET /api/desktop/ready`, `GET /api/desktop/manifest` (desktop-starter) |
| MCP | `POST /mcp` — streamable-HTTP MCP transport (mcp-starter) |
| Mail (dev) | `GET /onno/mail/preview[/{name}]`, `POST /onno/mail/events` webhook (mail-starter) |

> **SPA fallback gotcha:** any non-`/api` path returns `index.html` with HTTP 200 (React Router
> deep-linking). A mistyped URL "succeeds" with the SPA shell. Only `/api/**` produces real
> `404`/`401`/`403`. When debugging, hit API URLs, not page URLs.

The catalog/document `POST`/`PUT` writes (`CatalogCommandService`/`DocumentCommandService`, shared by
the REST API, the generated UI, CSV import, and the MCP tools) reconstruct the typed entity and run
the same entity write lifecycle as `repository.save(...)` — `onFilling()` (create), `beforeWrite()`,
and `Validated` business rules — before the JDBI write, so a field a model derives in `beforeWrite()`
is persisted on every write path, not just on the repository path. Auto-numbering and secret
encryption stay in the command services; posting still runs its own lifecycle in `PostingEngine`.

The same pipeline backs **live form validation**: `POST /api/{catalogs,documents}/{name}[/{id}]/validate`
dry-runs it — constraints, hooks, `Validated` rules (a Java conflict check included) — against the
submitted values without persisting or consuming a number, and returns
`{valid, fieldErrors, formErrors}` with 200 either way. The generated form calls it debounced while
the user edits, painting a field-scoped rule (`BusinessRule.onField`) inline on its input and
cross-field messages as a form-level notice, before Save is ever pressed.

## UI layer

The UI is authored as Spring beans, never as annotations on domain classes:

- **`Layout`** — navigation, shell (`NavStyle`), branding, persona (`profile()`), `roles`, and an
  optional `viewport()` (DESKTOP/TABLET/MOBILE). The default layout (`profile() == null`) is the
  back-office shell. **The nav is curated:** `UiLayoutResolver` builds the sidebar only from the
  sections you declare (`spec.section(...).catalog(X.class)`), with no auto-list fallback — a
  catalog/document/register appears in the sidebar only if a section lists it. (Earlier versions
  auto-listed unclaimed catalogs under default `CATALOGS`/`REGISTERS` groups; that was removed.) A
  section can also link an authored `Page` at an arbitrary route with
  `section(...).page(route, label, icon)` — the nav peer of a catalog/document entry.
- **`Page`** — a route you compose (`compose(PageBuilder)`): `title`, `widget(...)` (count, metric,
  chart, calendar, list, kanban, or app-registered custom), `text`, `list`, `constants`, `custom`,
  and `bare()`/`header(false)` to drop the title row. A page is served at **any** route — the home
  dashboard (`/`), settings (`/settings`), a **default surface route** (`/catalogs/{name}`,
  `/documents/{name}`, or `/registers/{name}`, where an authored page *overrides* the default
  list/report surface), or an
  **arbitrary custom route** (`/ops`, `/reports`) reached through the catch-all page endpoint. So a
  dashboard, the settings screen, and a list page are all just pages: the framework serves a sensible
  default and any registered `Page` bean at that route replaces it. A custom route is surfaced in the
  sidebar with `spec.section(...).page("/ops", "Sales Ops", "activity")`.
- **`EntityView`** — per-entity `list(ListSpec)` columns/filters and `fields(EntityConfigBuilder)`
  hints (`order`, `group`, `width`, `widget`, `format`, `hint`, `label`, `hideInList/Form/Detail`,
  related lists, actions; `label` localizes a field's form/detail/list label, including the built-in
  system columns code/description/number/date/posted). A ref field's picker can show a secondary
  line (`refSecondary`) and **cascade** (`refFilter`): `f.field("lines.book").refFilter("supplier =
  ${supplier}")` narrows the picker server-side to records matching the form's current values —
  while a referenced field is empty the picker is unfiltered, and changing it clears the dependent
  field. `refOptions(Decorator.class)` adds live context-aware badges, disabled reasons, and filtering;
  `uniqueWithinSection()` prevents sibling duplicate picks. `EntityConfigBuilder.validation(...)`
  registers a Spring `FormValidator` with explicit dependency paths and debounce for advisory
  field/form errors, warnings, and info; save/post invariants remain authoritative business rules.
  **An entity surface is only *served* if it has an `EntityView` for the active profile —
  the view layer is the allowlist (no view → `404`).** This gates reachability, not nav presence: a
  view makes the entity reachable by its direct route, but it shows in the sidebar only once a
  `Layout` section also lists it (see `Layout` above). So an `EntityView` is necessary but not
  sufficient for nav presence.

Server-side rendering uses **DivKit**: the controllers emit DivKit card JSON resolved for the
caller's persona, roles, theme, and viewport. The same contract drives the bundled React/Vite SPA
today and is intended to drive a native client later. The frontend lives in
`onno-ui-starter/src/main/frontend` and is built by Gradle (`buildFrontend`, Node 20) into
`static/ui/`. See [onno-ui-starter/README.md](../onno-ui-starter/README.md) for the full widget DSL
and `config(key,value)` reference.

**Custom widgets.** For a widget type the framework has no built-in for, a consumer app authors a
React component in `src/main/widgets/*.tsx` and applies the `su.onno.widgets` Gradle plugin, which
compiles it (managed Node + esbuild, React aliased to the host SPA) into `onno-plugins/<name>.js` on
the classpath. The starter scans that location (`onno.ui.plugins.*`), serves the modules under
`{onno.ui.path}/plugins/**`, and advertises them as `pluginScripts` from `GET /api/config`; the SPA
dynamic-imports each at boot, where it self-registers via the `window.onno` host bridge. Authoring
uses `@onno/widget-sdk` (bundled in the Gradle plugin — no npm needed). See the README's
"Authoring a custom widget" section.

The same registry also serves **custom list renderers**: an `EntityView` may declare
`list.custom("type")` to delegate the list *body* (tiles/cards/gallery) to a component registered
with `registerListRenderer(type, C)`, while the framework keeps the toolbar (search, filters,
sorting), the feed (infinite/paged + pager), and live refresh — the component just receives the
current window of rows, the list descriptor, and an open-record callback. An unregistered type
degrades to the default grid. See the README's "Custom list renderers" section.

## Auth & RBAC

`onno-auth-starter` contributes the `SecurityFilterChain` and picks a mode from `onno.auth.mode`:

- **`in-memory`** (default) — users from `onno.auth.users[*]`, session cookie + optional remember-me,
  JSON `POST /api/auth/login`, CSRF via `XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header (browser SPAs read
  the cookie directly; native clients that can't read it fetch the token from `GET /api/auth/csrf`).
- **`oidc`** — server-side OpenID Connect (Keycloak/Zitadel/custom); realm/client role mapping from
  the token via `onno.auth.oidc.*`; RP-initiated logout.
- **`resource-server`** — stateless JWT bearer validation, no session/CSRF.

Public, non-sensitive demos can opt into server-side in-memory auto-login with
`onno.auth.demo.auto-login-username`; normal RBAC still sees that user's roles. Iframe embedding is
deny-by-default and requires an explicit `onno.auth.embedding.frame-ancestors` CSP allowlist. A
cross-site session iframe additionally needs `onno.auth.embedding.cross-site-cookies=true` and HTTPS
`SameSite=None; Secure` servlet-session cookies.

`/api/**` requires authentication (except the public allowlist: `/error`, `/api/theme`,
`/api/config`, `/api/branding`, `/api/auth/login`, `/api/auth/me`, `/api/auth/csrf`,
`/api/divkit/login`, `/api/desktop/**`). **Per-entity RBAC is deny-by-default**: a
catalog/document/register is invisible
and uneditable unless its `@AccessControl` read/write roles grant the caller; the `ADMIN` role is a
superuser. Override the whole thing by setting `onno.auth.enabled=false` and supplying your own
`SecurityFilterChain`.

## Integrations

- **MCP** (`onno-mcp-starter`) — a streamable-HTTP MCP server at `/mcp` (HTTP Basic, same users as
  the web UI), exposing tools generated from the registry and gated by RBAC: `describe_metadata`,
  `list_catalog`/`get_catalog`, `list_documents`/`get_document`, `register_balance`/`register_movements`,
  `create_*`/`update_*`/`delete_*` (gated by `onno.mcp.writes-enabled`), `posting_preview`,
  `post_document`/`unpost_document` (gated by `onno.mcp.posting-enabled`). Applications add
  authenticated, role-scoped tools with `@McpTool` methods; `McpToolProvider` is the lower-level
  SDK extension point. Custom tools compose with generated tools and duplicate names fail startup.
  This is the agent-readable model surface that replaced the old idea of an HTTP manifest.
- **Import** (`onno-import-starter`) — CSV preview + import for catalogs/documents through the same
  command services as the UI (so validation, numbering, posting, events all apply); modes
  `CREATE_ONLY` / `UPSERT_BY_CODE` (catalogs) / `UPSERT_BY_NUMBER` (documents), dotted mapping keys
  for tabular sections, optional `groupBy` + `postAfterImport` for documents.
- **Kafka** (`onno-kafka-starter`) — drains the `onno_outbox` to a Kafka topic as CloudEvents via
  `OutboxRelay.relayPending()` (call it from your own `@Scheduled`); optional de-duplicating inbox
  dispatches to `EventHandler` beans; `RemoteRefClient` resolves references against other services.
- **Desktop** (`onno-desktop-starter` + `onno-desktop-gradle-plugin`) — a `DesktopApp` bean
  declares the window (config-as-code); the starter serves `/api/desktop/{ready,manifest}` and
  relocates the H2 file + session store under the per-user home; the Gradle plugin
  (`id("su.onno.desktop")`, task `packageDesktop`) jlinks a runtime and runs `cargo tauri build`.

## Events & outbox

Every write — through the generic controllers **and** through `repository.save(...)` — publishes a
Spring `EntityChangedEvent(changeType, entityType, entityName, id, naturalKey)`
(`onno-framework/src/main/java/su/onno/events/EntityChangedEvent.java`). It drives the `/api/events`
SSE stream and lets server-side consumers (cache revalidation, search indexing) react to a specific
resource instead of polling. The browser stream is **filtered per subscriber by per-entity read
access** — an event (including `comment` and `presence` events) reaches a viewer only when their roles
may read the affected record, so the live channel honours the same deny-by-default RBAC as the
REST/UI/MCP surfaces; unknown event kinds are delivered only to `ADMIN` (fail closed) (#190).
Process-task invalidations use their own audience gate: only candidate users/roles and `ADMIN`
receive `tasks-changed`, and the candidate assignment itself never enters the browser payload.
`@DomainEvent` declarations append to the transactional `onno_outbox`;
`onno-kafka-starter` relays those rows when you want cross-service streaming.

The `entityType` vocabulary is open: the modelled kinds are `catalog`, `document`, and `register`,
but other modules emit their own. Comment-thread posts and deletes (`onno-ui-starter`; not a modelled
entity) publish the same event with `entityType = comment`, scoped to the commented record's
`(entityName, id)` — so the comments panel live-syncs across viewers, and across nodes via the same
cluster relay, without each panel opening its own stream. Listeners filter on `entityType`, so the
list/detail/dashboard surfaces (which react only to the modelled kinds) ignore it.

The SSE fan-out is in-JVM by default — fine for one node. Add `onno-cluster-starter` (see below) to
make `EntityChangedEvent`s reach browsers on **every** node of a scaled-out deployment.

On the browser side, all tabs of an origin share a **single** `/api/events` connection: one tab is
elected leader (via the Web Locks API) and holds the stream, rebroadcasting each event to the other
tabs over a `BroadcastChannel`; if the leader tab closes, another transparently takes over. This
keeps a handful of open tabs from exhausting the browser's per-origin connection limit (~6 over
HTTP/1.1, shared across all tabs), which would otherwise starve both the extra streams and ordinary
API calls. Browsers without Web Locks/`BroadcastChannel` fall back to one stream per tab.

## Scaling out (horizontal)

Running more than one instance behind a load balancer needs these, beyond a shared database:

- **Live-UI events across nodes** — add `onno-cluster-starter`. It relays each `EntityChangedEvent`
  over a pluggable `ClusterEventBus` (`su.onno.cluster`, an SPI swappable like `MediaStorage`);
  the default uses Postgres `LISTEN`/`NOTIFY` (no extra infrastructure) and is a no-op on H2. A
  received event is pushed straight to the local SSE stream and is **never** re-published as a Spring
  event, so business `@EventListener`s (cache, search, post-hooks, the kafka outbox) still run exactly
  once, on the node that made the change. `DocumentPostedEvent`/`DocumentUnpostedEvent` are node-local
  by design; their cross-node *visibility* rides on the `posted`/`unposted` `EntityChangedEvent`. To
  swap in Kafka/Redis, expose your own `ClusterEventBus` bean (`@ConditionalOnMissingBean`).
  `ClusterEvent` is a sealed family discriminated by `kind`: `EntityChanged`, `Presence`,
  `Notification`, and `ProcessTasksChanged` share the one channel. Task invalidations retain their
  user/role audience across nodes, so a process advanced on one node refreshes only eligible inboxes
  connected elsewhere.
- **Presence markers across nodes** — record-level presence ("who else is viewing this") is held in an
  in-memory per-node registry (`PresenceRegistry`, ui-starter) and relayed as a `Presence` `ClusterEvent`
  over the same bus, so a viewer on any node sees viewers on every node. It is deliberately best-effort
  and heartbeat-kept: a viewer expires by TTL once heartbeats stop, so a closed tab or a crashed node
  self-heals without an explicit leave and without cross-node clock agreement.
- **Schema apply** — every node runs the boot-time diff/migration. On Postgres it is serialized by a
  session-level advisory lock, so one node applies DDL while the others wait and then re-run against
  the now-current schema (idempotent via the diff + `onno_schema_history`). H2 is single-node and
  skips the lock.
- **Auth** — set a stable `onno.auth.session.remember-me.key`; a blank key fails fast (per-node random
  keys make cookies non-portable behind a load balancer). For single-node/dev, opt in with
  `onno.auth.session.remember-me.allow-ephemeral-key=true`. Sessions are in-memory servlet sessions,
  so use sticky routing or a shared session store.
- **Media** — `FilesystemMediaStorage` is per-node; supply a shared `MediaStorage` bean (object store)
  so uploads are reachable from any node.
- Already cluster-safe: **JobRunr** scheduled jobs (DB-backed leader election), `onno_schema_history`
  idempotency, and the "update available" notice (each node polls independently and converges).

## Build, versioning, publishing

- **Toolchain**: Java 21 (pinned via Gradle toolchain), Spring Boot 3.4.x, Gradle wrapper is the
  source of truth. Build everything with `./gradlew clean check`; verify consumable artifacts with
  `./gradlew publishToMavenLocal`.
- **Publishing**: the vanniktech maven-publish plugin publishes to the Maven Central Portal
  (`SONATYPE_HOST=CENTRAL_PORTAL`, `SONATYPE_AUTOMATIC_RELEASE=true`). Pushing a `vX.Y.Z` tag runs
  `clean check`, signs, uploads, auto-releases, and creates a GitHub release; `-rcN` tags publish
  pre-releases. Consumers need only `mavenCentral()` and the `su.onno:*` coordinates.
  Maven Central does not allow replacing a released version — a bad tag is permanent.

## Open-core boundary

The framework is open-core. Everything in this repo (Maven group `su.onno`) is
Apache-2.0, **including authentication and OIDC/SSO** (`onno-auth-starter`). Separately licensed
commercial connectors (Maven group `su.onno.enterprise`: Guesty, SES.HOSPEDAJES, Tochka) live in
the private [onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo under the onno
Commercial License and consume the core as published Maven artifacts. An enterprise connector is a
Spring Boot auto-configuration starter that wraps an external API; the framework metadata, posting,
and UI live in the *consuming application*, not in the connector. The boundary and extraction plan
are in [docs/licensing/MODULE-SPLIT-PLAN.md](licensing/MODULE-SPLIT-PLAN.md).

## Community extensions

The same starter mechanism is open to anyone — community extensions are first-class, not a fork.
There are five extension surfaces: **connectors** (auto-config starters wrapping an external
system), **SPI implementations** (`MediaStorage`, `MailDispatcher`, an additive
`AuthMethodsContributor` login button, custom `SecurityFilterChain`/`UserDetailsService`, Kafka
`EventHandler`), **UI** (`Page`/`Layout`/`EntityView` beans and custom widgets/actions), **MCP**
(`@McpTool` methods and `McpToolProvider` beans), and Claude **skills/plugins** (via
[.claude-plugin/marketplace.json](../.claude-plugin/marketplace.json)).

The contributor-facing how-to — the starter shape, the conventions that keep `su.onno`
and the `su.onno.*` packages reserved, and a definition of done — is in
[EXTENDING.md](EXTENDING.md). Community-built integrations are cataloged in
[INTEGRATIONS.md](../INTEGRATIONS.md), generated from the machine-readable
[`community/registry.json`](../community/registry.json) by the `generateIntegrationsDoc` Gradle task.
