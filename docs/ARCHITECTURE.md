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
| `onno-framework` | `su.onno` | Core: annotations, metadata scanners + registry, schema diff/migration, JDBI persistence, posting engine, complete typed process graph/runtime/schema, repository contracts, events, outbox, UI model (`Layout`/`Page`/`EntityView`). |
| `onno-framework-starter` | `su.onno` | Spring Boot auto-configuration that wires the core: metadata registry, repositories, schema initializer, posting and process services, timer/subprocess polling, number generation, secret cipher, background jobs. |
| `onno-ui-starter` | `su.onno` | Generic REST controllers under `/api/**`, the DivKit server-driven UI layer, the bundled React/Vite SPA, media uploads, SSE event stream, comment threads, per-user notifications. |
| `onno-observability-starter` | `su.onno` | Opt-in privacy-safe business and UX telemetry, a bounded non-blocking exporter, and deployment-version context. Included transitively by the UI starter but disabled by default. |
| `onno-crm-starter` | `su.onno` | Composable messaging: inboxes, conversations/messages, channel identities and widgets. Explicit host customer bindings enable it; customers, employees, sales models and navigation remain host-owned. |
| `onno-auth-starter` | `su.onno` | Spring Security: in-memory, OIDC/SSO, and resource-server (JWT) modes; JSON login/logout; CSRF; per-request principal. |
| `onno-mcp-starter` | `su.onno` | Model Context Protocol server exposing the model + CRUD + register reads + posting as AI-agent tools, generated from the registry. |
| `onno-import-starter` | `su.onno` | CSV import (preview, mapping, upsert, dry-run, document grouping) through the same command path as the UI. |
| `onno-cluster-starter` | `su.onno` | Cross-node delivery of `ClusterEvent`s (entity changes, process-task invalidations, presence, and notifications) for horizontal scale-out via a pluggable `ClusterEventBus` SPI (default Postgres `LISTEN`/`NOTIFY`; no-op on H2). Keeps the SSE live UI, task inboxes, collaboration markers, and notification delivery in sync across instances. |
| `onno-kafka-starter` | `su.onno` | Transactional outbox → Kafka relay as CloudEvents, de-duplicating inbox, service registry, remote `Ref` client. |
| `onno-desktop-starter` | `su.onno` | Runs the app as a native desktop window (Tauri shell), config-as-code window manifest, H2/session relocation. |
| `onno-desktop-gradle-plugin` | (`su.onno.desktop` plugin) | Packages a Spring Boot app into a native `.dmg`/`.msi`/`.AppImage` via jlink + Tauri. |
| `onno-widgets-gradle-plugin` | (`su.onno.widgets` plugin) | Compiles consumer-authored React widgets (`src/main/widgets/*.tsx`) into onno UI plugin modules via managed Node + esbuild; bundles the `@onno/widget-sdk` authoring package. |
| `onno-widget-sdk` | (npm `@onno/widget-sdk`) | The authoring surface for custom widgets — types, hooks, UI primitives, a read-only data client, and shared live-event subscriptions that resolve to the host SPA at runtime. |
| `example` | (not published) | The Onno Books retailer app: a compact end-to-end consumer and smoke-test fixture. |
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
   beans. Model-bearing modules such as `onno-crm-starter` additionally register their own metadata
   package, repositories, additive UI beans, and packaged widget assets without expanding the
   consuming application's component scan.

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
  resource totals on dimension tuples touched by the post and `allowNegative = true` opts a
  debt/overdraft register out; unrelated stale negative tuples do not block otherwise valid posts;
  `postingOrder = CHRONOLOGICAL` makes a backdated post/unpost restore and repost later affected
  documents in date order;
  `@Dimension` keys and `@Resource` numbers; rows extend `AccumulationRecord` (`period`, `active`,
  `documentRef`, `movementType = RECEIPT | EXPENSE`). Enum dimensions use the same deterministic
  UUID representation as enum attributes throughout movement insertion, totals, filters, and typed
  reads.
- **`@InformationRegister`** — facts by dimension over time (`title` for the display label, falling
  back to `name`, like every other kind), `periodicity =
  NONE|SECOND|MINUTE|HOUR|DAY|MONTH|QUARTER|YEAR`; rows extend `InformationRecord`. A write is
  floored to its periodicity bucket and upserted on `(_period, dimensions…)`, so the bucket is also
  the finest interval at which two facts for one dimension tuple can coexist — the sub-day values
  exist for event logs, audit trails, and intraday series, while `NONE` drops `_period` and keeps
  one current row per dimension tuple.
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

- `ProcessDefinition<P,S>` is an application Spring bean with a stable persisted key, positive
  version, declared payload class, and one lazily built, validated route graph. `P` is the typed
  payload; `S` is an enum implementing `ProcessStepKey`. Instances pin the exact version they
  started on.
- `ProcessGraph<P,S>` creates typed human, automatic, decision, timer, structured parallel
  fork/join, subprocess, and end-node handles. Routes connect those handles directly; no string
  node lookups or expression language are involved. `descriptor()` exposes a safe structural view
  for generic clients.
- `HumanTask<P,O>` declares an enum outcome type, a payload-dependent title, and a
  `TaskAssignment` of candidate stable identities/roles. `TaskAssignment.identities(Ref<?>...)`
  keeps employee routing typed and stable across login/email changes. Optional `subject(payload)`
  returns a typed catalog/document `Ref<T>` that becomes a durable task-to-record link;
  `subjectLabel(payload)` stores its human-readable snapshot for the inbox.
  Definition validation requires every enum outcome to have a transition and rejects duplicate
  keys, cross-graph connections, missing start targets, unreachable nodes, incomplete typed
  branches, unstructured joins, and immediate-only cycles.
- Core `JdbcProcessEngine` persists payload JSON, version-pinned instances, execution tokens,
  timers, parent/child links, work items, transition history, and ordered task audit events in the
  framework-managed `onno_process_*` tables. Parent-token ancestry makes nested fork/join durable.
  Claim, delegate, complete, cancel, migrate, and token advancement use row locks plus optimistic
  versions. A claimed item is private to its assignee; `ADMIN` bypasses candidate/assignee checks.
- `ProcessController` is the authenticated boundary: definitions/start/instance reads plus a
  role-scoped task inbox and claim/delegate/history/complete commands. It also exposes versioned
  graph metadata, the caller's instances, execution tokens, cancellation, and migration. The
  `tasks` page widget
  resolves delegation targets and their live avatar/photo through the layout identity catalog,
  opens typed task subjects in the shell's adjacent detail pane, and provides the same loop to
  humans. `JdbcProcessEngine` publishes `ProcessTasksChangedEvent` only after its JDBI
  transaction commits; the UI routes a payload-free `tasks-changed` SSE invalidation to affected
candidate users/roles and admins, so every open inbox refetches automatically.

`HumanTask.assignment(payload)` is the automatic-routing seam. It may use an injected application
service and return `TaskAssignment.identities(selectedEmployeeRef)`. The employee record UUID is
the stable task owner; login and display name are mutable snapshots only. The authenticated
principal is resolved through `Layout.identity(...)` to that same UUID.

The typed Java definition remains the source of truth. Stable definition/step keys and the exact
definition version are persisted; HTTP outcomes are enum constant names validated against the
active task's declared enum. `AutomaticStep` returns the payload to persist and receives a stable
execution idempotency key; external side effects belong in an outbox. `ProcessTimer` stores an
absolute due time. A paired typed parallel fork/join creates one branch token per enum value.
`SubprocessCall` maps parent payload to a version-pinned child and merges a completed child payload
back into the parent. JobRunr only wakes the durable timer/subprocess poller; database state decides
what can advance.

Definition evolution is explicit. Keep old and new `ProcessDefinition` beans registered under the
same key with different versions, register a forward `ProcessDefinitionMigration`, then invoke
`ProcessEngine.migrate`. Each migration maps every active token id to a typed target step and may
transform the payload; missing mappings roll the transaction back. Cancellation similarly closes
all live tokens, work items, timers, and descendants in one audited operation. Documents still
record business events and posting still writes register movements; a process coordinates work
across them. Authenticated MCP `describe_metadata` includes the latest safe process graph
descriptors alongside entity metadata; it never exposes payload-dependent candidate assignments.

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

The framework's Spring Data repository base class also turns `delete(...)`, `deleteById(...)`, and
bulk deletes of catalog/document aggregates into deletion-mark saves. It runs
`BeforeDeleteHandler.beforeDelete()` first, publishes delete events only after the mark is stored,
and refuses to delete a posted document; explicitly unpost it first. Repositories for ordinary,
non-onno aggregates keep Spring Data JDBC's physical-delete behavior.

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
map it to the logical API field used by catalog/document widgets, then query services translate it
to the physical storage column. This keeps Java source compiler-checked while preserving validated
SQL identifiers internally. String overloads are unsafe compatibility/dynamic escape hatches.
Strings remain correct for semantic identifiers (routes, roles, action/process keys, labels) and
for intentionally parsed expression text such as a widget filter.

## Persistence & schema migration

The schema is derived from metadata and reconciled at boot — there are no hand-written migration
files for structural changes. The diff-based engine lives in
`onno-framework/src/main/java/su/onno/schema/`:

- **Ownership**: `SchemaGenerator` bootstraps an empty database; `SchemaUpgrader` is the only
  metadata-driven path that changes an existing runtime schema.
- **Modes** (`onno.schema.mode`): `apply` (default — safe changes run; destructive ones are logged
  and skipped unless `onno.schema.allow-destructive=true`), `plan` (log only), `validate` (fail on
  drift / unapplied migrations), `off`.
- **Diff inputs**: the desired `SchemaModel` (from the registry), the live DB's current schema
  (`INFORMATION_SCHEMA` is filtered to `CURRENT_SCHEMA`), and the previous `SchemaSnapshot` (stored
  as JSON in `onno_schema_history`). Same-named tables in other PostgreSQL schemas are ignored. The
  snapshot is how *type changes* and *removed entities* are detected on later boots.
- **Renames keep data**: declare the former name with `previousNames` on `@Catalog`/`@Document`/
  `@Attribute`; the engine emits a `RENAME_TABLE`/`RENAME_COLUMN` instead of drop+add.
- **SQL identifiers fail fast**: metadata-derived table/column names and rename aliases must match
  `[A-Za-z_][A-Za-z0-9_]*`; the scanner rejects unsafe identifiers before DDL or query rendering.
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
negative `BALANCE` results on dimension tuples touched by that post unless the register declares
`allowNegative = true`, and writes back computed fields. Posting a missing or already-posted document
is rejected before any movement is persisted, so retries cannot double-count registers. Inside that
same transaction it appends `@DomainEvent` outbox rows; after commit it calls `afterPost` and
publishes a Spring
`DocumentPostedEvent` (`DocumentUnpostedEvent` for unpost).

`PostingService.repost(...)` is the explicit recalculation operation for a posted document: it
reverses the old movements and writes the recalculated movements atomically, so a failed recalculation
leaves the original posting intact. The generic REST `.../{id}/post` command selects this operation
when the document is already posted. Core `post(...)` remains a strict first-post operation, and
unposting a draft or deleted document is rejected.

For a register declared with `postingOrder = PostingOrder.CHRONOLOGICAL`, posting, reposting, or unposting a
backdated document discovers later active documents through that register (including documents
linked through other chronological registers), reverses them newest-first, applies the requested
change, and reposts them oldest-first. The restoration uses one serializable JDBI transaction, and
register balance reads made by `handlePosting` see that same transaction. Restored documents do not
emit duplicate external post events; only the requested command does.

Two semantics that bite every integration:

- **Posting is its own transaction**, not enlisted in an ambient `@Transactional`. Save the document
  (let it commit), *then* post. Wrapping save+post in one `@Transactional` silently leaves
  `_posted = false`.
- **Core posting an already-posted document is rejected.** Use `repost(...)` to intentionally
  recalculate its movements; a repeated core `post(...)` call never duplicates register totals.
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

Catalog/document reads default to logical camelCase names, matching the partial-write vocabulary;
the prior storage-shaped response remains available explicitly with `?representation=storage`
(full contract: [HEADLESS_READ_API.md](HEADLESS_READ_API.md)). Writes accept both logical and storage
aliases and reject unequal duplicates. Temporal values are normalized at the read/write boundary:
`LocalDate` is `yyyy-MM-dd`, and `LocalDateTime` is offset-free ISO wall time. Offset- or
zone-bearing `LocalDateTime` writes are rejected with a field-specific `400` rather than silently
discarding the offset. JDBC timestamp variants remain normalized on reads. This keeps PostgreSQL,
H2, the bundled forms, and headless clients on one round-trip-safe representation.

All endpoints are under `/api/**`, authenticated, and (for mutations) CSRF-protected. `{name}` is
the entity's annotation **logical name** (e.g. `Properties`, not the class `Property` or a localized title), matched
case-insensitively with spaces/underscores stripped. **There is no anonymous manifest endpoint** —
the only `/manifest` route is the desktop shell's `/api/desktop/manifest`; agents introspect the
model via the real generated endpoints below or the MCP `describe_metadata` tool. The read-response
contract (logical keys, `FieldDisplay`/`FieldRef` expansion, storage compatibility, and
`__SECRET_SET__` redaction) is in
[HEADLESS_READ_API.md](HEADLESS_READ_API.md).

Durable process routes are `GET /api/process-definitions`, `GET /api/processes`,
`POST /api/processes/{definitionKey}`, `GET /api/processes/{id}` plus `/history` and `/executions`,
`POST /api/processes/{id}/cancel|migrate`, and `GET /api/tasks` with task claim, delegate, history,
and complete endpoints. Start authorization comes from `ProcessDefinition.startAssignment(payload)`;
cancellation comes from `cancellationAssignment(payload)`; inbox/task authorization comes from each
`HumanTask.assignment(payload)`. Actors always come from the authenticated principal.

| Area | Endpoints (served by) |
| --- | --- |
| Catalogs | `GET /api/catalogs/{name}/{id}`, `/children?parent=`, `/tree`, `/{id}/related/{relatedName}`; `POST`/`PUT /{id}`/`POST /{id}/duplicate`/`DELETE /{id}`; `POST /validate` / `POST /{id}/validate` — dry-run the write lifecycle (constraints + hooks + business rules) without persisting, always 200 with `{valid, fieldErrors, formErrors}` — the form's live as-you-type validation (ui-starter) |
| Contextual forms | `POST /api/ref-options/search` — Ref search with live header/row/id context and application badges, disable reasons, or filtering; `POST /api/form-validation/{kind}/{name}/{key}` — dependency-aware advisory `ERROR`/`WARNING`/`INFO` feedback for new/edit forms (write-authorized; ui-starter) |
| Documents | `GET /api/documents/{name}/{id}`, `/{id}/posting-preview`; `POST`, `PUT /{id}`, `POST /{id}/duplicate`, `DELETE /{id}`, `POST /{id}/post`, `POST /{id}/unpost`; `POST /validate` / `POST /{id}/validate` — same dry-run validate as catalogs, tabular rows included (ui-starter) |
| Registers | `GET /api/registers/{name}/movements`, `/balance`, `/turnover?from=&to=` (ui-starter) |
| List feed | `GET /api/list/catalogs/{name}`, `/api/list/documents/{name}` — the only catalog/document collection read: pass `?cursor=&limit=` and read `{rows, nextCursor, hasMore}` (keyset pagination, constant-time at any depth, no skip/dup on shifting data); `?count=exact\|estimate` adds a total. `?ids=` returns just those rows (the grid's single-row live patch); `?filter=` applies a safe `WidgetFilter` predicate server-side (a dashboard widget's `config("filter", …)`, e.g. `status != 'DRAFT'`). `GET /api/list/registers/{name}/movements`, `/balance` — register data for the virtualized register surface, same cursor envelope and declarative filter params (`eq`/`in`/`like`/`prefix`/`ge`/`le`, validated against the register's columns); movement rows carry a localized `_movement_type_display` + `_movement_type_color` (ui-starter) |
| List grouping | `GET /api/list/{kind}/{name}/groups?groupBy=&granularity=&agg=fn,col&{q,filters}` — backend `GROUP BY`: `{groups: [{label, color?, count, values[], expand[]}], capped}`. One header per value (or per `day`/`month`/`year` bucket for a date column), each carrying an `expand` filter the grid replays on the list feed to load the group's rows. Same WHERE as the flat list; headers cap at 200 (ui-starter) |
| Widget aggregate | `GET /api/list/{kind}/{name}/aggregate?metric=&field=&groupBy=&groupByDate=&seriesBy=&filter=&dateField=&from=&to=` — server-side `GROUP BY` for the chart/stat/sparkline/gauge widgets: `{buckets: [{key, label?, series?, seriesLabel?, value, value2?}], truncated, span?}`, O(buckets) over the wire instead of the entity's whole table (#199). `groupByDate` (`minute…month`) buckets a timestamp via `DATE_TRUNC`; blank `groupBy` yields one grand-total bucket; `metric2`/`field2` add a combo chart's second measure; enum/Ref bucket values carry a resolved `label`; `span` is the windowed MIN/MAX of `dateField` (granularity auto-sizing). Date-bucketed axes always zero-fill empty periods with `{key, value: 0}` fillers over the window — or between the first/last data when unbounded (#246). Buckets (filled spine included) cap at 1000 (`truncated: true`) (ui-starter) |
| Settings | `GET`/`PUT /api/settings` — `@Constant` values, ADMIN (ui-starter) |
| Actions | `POST /api/actions/{kind}/{name}/{key}` — authored toolbar/row/detail actions; an action declaring `.form(...)` first opens the canonical dialog client-side and POSTs `inputs` (read via `ActionContext.input(key)`). `ActionSpec.dynamic(...)` retains a late-bound provider instead of freezing catalog-backed actions at startup; dynamic list descriptors carry `dynamicActions: true`, and `GET /api/actions/{kind}/{name}?id=` resolves current toolbar/row descriptors plus per-row state on each context-menu open. Static-only lists make no extra request; the SPA retains the last successful dynamic snapshot on a transient failure. Execution/form/batch resolve the provider again, so a removed key returns the normal `404`. Form metadata (`title`, description, labels, tone, icon, `SM`/`MD`/`LG`) rides in the action descriptor. `GET …/{key}/form?id=` returns server-computed opening values (`.formDefaults(ctx -> …)` → `{values, rows}`). `ActionRejectedException` maps to HTTP 422 `ActionFeedback` (`severity`, `presentation`, title/message/details, `fieldErrors`, `formErrors`, `dismissLabel`, `keepFormOpen`); forms retain input and render errors, while no-form rejection can use the accessible feedback dialog. Successful results are `{refresh, feedback}` from `ActionResult.toast(...)`, `.dialog(...)`, `.feedback(...)`, `.reload()`, or `.refresh(...)`; action navigation is static declaration metadata. `POST …/{key}/batch` runs `{ids:[…]}` once (≤500, `{ok, failed, total, feedback?}`; the first typed per-row rejection is preserved); `POST /api/divkit/page-action?route=&key=` runs a page button. All POSTs honour `onno.ui.read-only` and declared `.roles(...)` (#227). `POST /api/{catalogs|documents}/{name}/batch-delete` is bulk DELETE (ui-starter) |
| Media | `POST /api/media`, `GET /api/media/{key}` — authenticated uploads; served with `nosniff`, safe raster images inline and other types as attachments ([MEDIA_UPLOADS.md](MEDIA_UPLOADS.md)) (ui-starter) |
| Comments | `GET`/`POST /api/comments/{kind}/{name}/{id}`, `POST /api/comments/{commentId}/reactions`, `DELETE /api/comments/{commentId}` — discussion threads with replies (`parentId`) and grouped reactions, opt-in per entity via `EntityView.comments()` (404 otherwise), gated on read access to the entity; `createdAt`/`editedAt` are zone-qualified instants (`…Z`) (ui-starter) |
| Mentions | `GET /api/mentions?q=[&kind=people\|catalogs\|documents]` — comment typeahead over readable records; the UI uses `@` for people mentions (`kind=people` narrows to the `Layout.identity(...)` catalog, falling back to all catalogs when no identity link is configured) and `#` to reference any record — no `kind` sweeps documents and catalogs alike, searchable by name or code. Suggestions carry a secondary `hint` (a person's `email` attribute, a catalog record's code, a document's `yyyy-MM-dd` date). `GET /api/mentions/resolve?kind&name&id` resolves one triple to its live display plus a `person` flag (same per-viewer read gate) — the compose box uses it to swap a pasted internal `/ui/...` record URL for an `@` (person) or `#` (anything else) mention. Bodies carry `@[Display](kind/name/id)` / `#[Display](kind/name/id)` tokens resolved live; readable `@` mentions publish `EntityMentionedEvent` (consumed by notifications; additive via `@EventListener`) (ui-starter) |
| Notifications | `GET /api/notifications[?unread&cursor]` — the caller's keyset-paginated timeline `{items, nextCursor, hasMore, unreadCount}`; `POST /api/notifications/{id}/read` and `POST /api/notifications/read-all` mark read. Every call is scoped to the caller's identity (no cross-user reads). Rows persist in the framework-owned `onno_notifications` table; new ones push over the `notification` SSE event (routed by recipient, relayed across nodes over the `ClusterEventBus`). Built-in producers: comment mentions and record assignment (`@AssigneeField`); apps add more by calling `NotificationService.notify`. Gated by `onno.notifications.*` (ui-starter) |

Catalog/document attributes declared with `@Attribute(secret = true)` are write-only and are excluded
from list filter, sort, grouping, and aggregate allowlists; list-query shape and counts therefore
cannot be used as a blind oracle for encrypted values.
| DivKit UI | `GET /api/divkit/{shell,home,menu,account}` and `/api/divkit/{catalogs,documents}/{name}[/{id}|/new]`, `/api/divkit/registers/{name}` (ui-starter). `/{id}` is the combined record surface — the editable form (disabled for read-only viewers) with the record-level actions in its header. `GET /api/divkit/{*route}` is the catch-all page endpoint: any route with a registered `Page` bean renders (a custom dashboard/report, **including `/settings`** — there is no built-in Settings surface), otherwise `404`. An authored `Page` at a default surface route (`/catalogs/{name}`, `/documents/{name}`, `/registers/{name}`) overrides that surface's default list/report |
| Theme/config | `GET /api/theme`, `GET /api/config`, `GET /api/branding` (ui-starter) |
| Events | `GET /api/events` — SSE stream of CRUD/posting changes, `tasks-changed` inbox invalidations, plus `presence` viewer-set updates (ui-starter); filtered per subscriber by entity read access or process-task audience (#190) |
| Presence | `POST /api/presence` (body `{path, action}`) — mark presence on any route (`enter`/`heartbeat`/`leave`); the server derives the identity from the path (a record, an entity list, or any page/dashboard), gating entity routes on read access while a page is visible to any signed-in user. Identity from the session, heartbeat-kept + TTL-expired, relayed across nodes over the `ClusterEventBus`. `GET /api/presence` — the ambient snapshot (routes the caller may see) that seeds the client store behind the tab/row/sidebar collaborator avatars (the viewer's photo, initials fallback); kept live by `presence` SSE deltas (ui-starter) |
| Auth | `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`, `GET /api/auth/csrf` (auth-starter) |
| Import | `POST /api/import/{catalogs,documents}/{name}/csv[/preview]` (import-starter) |
| Desktop | `GET /api/desktop/ready`, `GET /api/desktop/manifest` (desktop-starter) |
| MCP | `POST /mcp` — streamable-HTTP MCP transport (mcp-starter) |

> **SPA routing:** the server returns `index.html` only for `GET` navigation under `onno.ui.path`
> whose `Accept` header includes `text/html`. Missing assets, unknown `/api/**` routes, non-GET
> requests, and paths outside the configured UI mount return their normal `404`/`405` responses.

The catalog/document `POST`/`PUT` writes (`CatalogCommandService`/`DocumentCommandService`, shared by
the REST API, the generated UI, CSV import, and the MCP tools) reconstruct the typed entity and run
the same entity write lifecycle as `repository.save(...)` — `onFilling()` (create), `beforeWrite()`,
and `Validated` business rules — before the JDBI write, then `afterWrite()` after a successful
persist. A field a model derives in `beforeWrite()` is therefore persisted on every write path, not
just on the repository path. Validation previews run only the pre-write phase and never call
`afterWrite()`. Auto-numbering and secret
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
  back-office shell. Shell, branding, and `identity(...)` are application-wide and therefore belong
  on that default layout; startup rejects them on a named profile instead of silently dropping them.
  Default-layout beans compose additively: reusable modules may contribute sections while a host
  layout owns the non-default shell, branding, theme, and identity settings. A module that only adds
  navigation leaves its shell at `ShellConfig.defaults()`, which is treated as no shell contribution.
  Viewport-specific beans augment universal contributions for that viewport.
  Branding distinguishes the full theme-aware `logo(...)` from the compact theme-aware
  `mark(...)` used by the two-tier desktop app rail; an unconfigured mark falls back to the favicon,
  then the logo, so existing applications remain usable in the new shell. The rail frames marks by
  default for compatibility; `markFrame(false)` removes that shell border when the artwork already
  supplies its own enclosing shape.
  **The nav is curated:** `UiLayoutResolver` builds the sidebar only from the
  sections you declare (`spec.section(...).catalog(X.class)`), with no auto-list fallback — a
  catalog/document/register appears in the sidebar only if a section lists it. (Earlier versions
  auto-listed unclaimed catalogs under default `CATALOGS`/`REGISTERS` groups; that was removed.) A
  section can also link an authored `Page` at an arbitrary route with
  `section(...).page(route, label, icon)` — the nav peer of a catalog/document entry. On desktop,
  `SIDEBAR` uses each section as an app-rail workspace whose items open in a collapsible nested
  drawer; `/api/divkit/shell` returns that RBAC-filtered structure as `navigation` and the signed-in
  identity/profile metadata as `accountInfo` for the native desktop account controls, while retaining
  the portable DivKit nav/account cards for non-web clients and other nav styles. Entity and page entries
  share one authored sequence, so a mixed chain such as `.page(...).catalog(...).page(...)` renders
  in exactly that order.
- **`Page`** — a route you compose (`compose(PageBuilder)`): `title`, `widget(...)` (count, metric,
  chart, calendar, list, kanban, or app-registered custom), `text`, `list`, `actions`, `custom`,
  and `bare()`/`header(false)` to drop the title row. A page is served at **any** route — the home
  dashboard (`/`), settings (`/settings`), a **default surface route** (`/catalogs/{name}`,
  `/documents/{name}`, or `/registers/{name}`, where an authored page *overrides* the default
  list/report surface), or an
  **arbitrary custom route** (`/ops`, `/reports`) reached through the catch-all page endpoint. So a
  dashboard, the settings screen, and a list page are all just pages: the framework serves a sensible
  default and any registered `Page` bean at that route replaces it. A custom route is surfaced in the
  sidebar with `spec.section(...).page("/ops", "Sales Ops", "activity")`. An embedded operational
  list may opt into the host's remaining height with `list(Entity.class, view -> view.fill())`; its
  toolbar stays fixed and its list/custom body scrolls internally instead of growing the page.
- **`EntityView`** — per-entity `list(ListSpec)` columns/filters, `fields(EntityConfigBuilder)`
  hints, and record-aware custom widgets through `detail(DetailSpec)`
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
`onno-ui-starter/src/main/frontend` and is built by Gradle (`buildFrontend`, Node 22.22) into
`static/ui/`. See [onno-ui-starter/README.md](../onno-ui-starter/README.md) for the full widget DSL
and `config(key,value)` reference.

**Custom widgets.** For a widget type the framework has no built-in for, a consumer app authors a
React component in `src/main/widgets/*.tsx` and applies the `su.onno.widgets` Gradle plugin, which
compiles it (managed Node + esbuild, React aliased to the host SPA) into `onno-plugins/<name>.js` on
the classpath. The starter scans that location (`onno.ui.plugins.*`), serves the modules under
`{onno.ui.path}/plugins/**`, and advertises them as `pluginScripts` from `GET /api/config`; the SPA
dynamic-imports each at boot, where it self-registers via the `window.onno` host bridge. Authoring
uses `@onno/widget-sdk` (bundled in the Gradle plugin — no npm needed). See the README's
"Authoring a custom widget" section. Discovered plugin assets are copied to a stable temporary serve
directory at startup so continuous builds cannot corrupt an in-flight classpath JAR read.

Consumer widgets may opt into additional browser packages with
`onnoWidgets.npmDependencies.put(name, version)`. The plugin writes them into its isolated managed
npm workspace and esbuild bundles them into the consumer's widget module. React, React DOM, the
widget SDK, and build-tool packages remain framework-managed so plugins share the host runtime
rather than loading incompatible duplicates.

The same registry also serves **custom list renderers**: an `EntityView` may declare
`list.custom("type")` to delegate the list *body* (tiles/cards/gallery) to a component registered
with `registerListRenderer(type, C)`, while the framework keeps the toolbar (search, filters,
sorting), the feed (infinite/paged + pager), and live refresh — the component just receives the
current window of rows, the list descriptor, and an open-record callback. An unregistered type
degrades to the default grid. See the README's "Custom list renderers" section.

Custom list renderers receive optional `hasMore`, `loadingMore`, `loadMoreFailed` and `loadMore()`
props. A renderer with its own scrolling pane must call `loadMore()` near that pane's bottom and
provide an accessible load-more/retry button (disabled while loading). Stop automatic retries after
`loadMoreFailed`; an explicit retry uses the same cursor. The host retains the scoped feed, filters,
query generation, row deduplication and loading guard. Do not fetch the entire catalog in a renderer.


It also serves **record detail widgets**. `EntityView.detail(DetailSpec)` declares widgets below the
fields on the combined catalog/document record form. They render only for saved records and receive
`widget.record` with `kind`, logical `name`, record `id`, already-loaded `data`, and `readOnly`.
New and Duplicate forms do not render them because they have no saved record identity yet.

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
  Catalog/document lists are keyset-paged. Register reads retain bounded one-call results
  (`register_movements`: 1,000; `register_balance`: 5,000) and return an MCP error when more rows
  match, so an agent cannot mistake a capped slice for the complete register.
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
`@DomainEvent` declarations append to `onno_outbox` in the same transaction as the
repository save/delete or posting mutation; a rollback cannot leave a phantom event, and an outbox
insert failure rolls back the business mutation. Direct application calls to
`OutboxWriter.append(...)` join an active Spring JDBC transaction; posting internals use the
transaction-bound `append(Handle, ...)` overload.
`onno-kafka-starter` relays those rows when you want cross-service streaming.

The `entityType` vocabulary is open: the modelled kinds are `catalog`, `document`, and `register`,
but other modules emit their own. Comment-thread posts and deletes (`onno-ui-starter`; not a modelled
entity) publish the same event with `entityType = comment`, scoped to the commented record's
`(entityName, id)` — so the comments panel live-syncs across viewers, and across nodes via the same
cluster relay, without each panel opening its own stream. Listeners filter on `entityType`, so the
list/detail/dashboard surfaces (which react only to the modelled kinds) ignore it.

Local SSE entity notifications are dispatched after the publishing Spring transaction commits;
rolled-back writes produce no notification. Publishers outside a transaction still notify immediately.
This prevents a browser from refetching a newly created conversation before it exists in committed data.

The SSE fan-out is in-JVM by default — fine for one node. Add `onno-cluster-starter` (see below) to
make `EntityChangedEvent`s reach browsers on **every** node of a scaled-out deployment.

On the browser side, all tabs of an origin share a **single** `/api/events` connection: one tab is
elected leader (via the Web Locks API) and holds the stream, rebroadcasting each event to the other
tabs over a `BroadcastChannel`; if the leader tab closes, another transparently takes over. This
keeps a handful of open tabs from exhausting the browser's per-origin connection limit (~6 over
HTTP/1.1, shared across all tabs), which would otherwise starve both the extra streams and ordinary
API calls. Browsers without Web Locks/`BroadcastChannel` fall back to one stream per tab.
Custom widgets join this same transport through `@onno/widget-sdk`: `useWidgetUpdates(widget, load)`
handles the normal entity-bound refresh with filtering and burst coalescing, while
`events.subscribe`/`useUiEvents` cover unusual matching. A widget must not open its own
`EventSource`, which would bypass cross-tab sharing, reconnect/session handling, and listener cleanup.

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
There are six extension surfaces: **connectors** (auto-config starters wrapping an external
system), **SPI implementations** (`MediaStorage`, `MailDispatcher`, an additive
`AuthMethodsContributor` login button, custom `SecurityFilterChain`/`UserDetailsService`, Kafka
`EventHandler`), **UI** (`Page`/`Layout`/`EntityView` beans and custom widgets/actions),
**observability** (`TelemetryRecorder` semantic outcomes and timings), **MCP**
(`@McpTool` methods and `McpToolProvider` beans), and Claude **skills/plugins** (via
[.claude-plugin/marketplace.json](../.claude-plugin/marketplace.json)).

The contributor-facing how-to — the starter shape, the conventions that keep `su.onno`
and the `su.onno.*` packages reserved, and a definition of done — is in
[EXTENDING.md](EXTENDING.md). Community-built integrations are cataloged in
[INTEGRATIONS.md](../INTEGRATIONS.md), generated from the machine-readable
[`community/registry.json`](../community/registry.json) by the `generateIntegrationsDoc` Gradle task.
Both `check` and `generateIntegrationsDoc` validate registry structure, allowed values, URLs,
coordinates, and duplicate ids before accepting it.


The CRM workspace feed accepts `ids` (comma-separated or repeated UUIDs, at most 500) for live row refreshes. It intersects these IDs with workspace membership and read authorization before returning rows or counts. Reading a conversation therefore refreshes that conversation without replacing it with the first inbox row.

### CRM delivery adapters

`CrmMessageTransport` is the CRM's provider-neutral message boundary. The default is disconnected;
a host supplies connection status and transactionally enqueues outbound agent replies, with external
HTTP work after commit. `GET /api/crm/conversations/{id}/delivery` returns connected/label/maxTextLength;
`POST /api/crm/conversations/{id}/messages/{messageId}/retry` requeues a failed reply after checking
conversation membership and host customer permissions. See [the read/API contract](HEADLESS_READ_API.md).
`onno-crm-channels-starter` includes an opt-in Telegram client and CRM bridge for private text chats, with durable
checkpoint/contact/outbox tables and manual retry after ambiguous send failures. It never maps demo
contacts to real recipients or replaces a configured webhook.

### Developer CRM layout, channel connections and customer identities

The optional CRM starter activates only when a host `CrmCustomerBinding` bean exists. Its typed
`CrmCatalogBinding` maps selected getters from an existing host catalog into a read-only contact
projection; ordinary host catalog forms own all field editing and validation. Customer UUIDs in
conversations and channel identities are scoped to that binding. Inbox reads enforce both workspace
roles and the host catalog's permissions, plus optional `readableWhen` record policy. No customer,
agent, stage, tag, opportunity or custom-value business model is installed. `CrmAgentBinding` adds
assignment using an existing employee/identity catalog. `CrmStateConfiguration` optionally defines
conversation statuses; without it, incoming messages and replies carry no status.

The host composes pages and navigation explicitly, using `crmInboxWorkspaces`, `crmSettings`, and
optional `crmContactDetails` widgets. Channel management uses `CrmChannelAccess` (ADMIN by default).
Only enabled connectors appear. Provider imports invoke the host's optional inbound contact policy;
they never instantiate a built-in customer. Telegram avatars belong to channel identities.

The host owns business merging and any auditing or undo. `CrmContactService.transferLinks` joins a
host transaction and moves CRM conversations/identities plus a canonical redirect, preserving
provider routing. It has no HTTP endpoint or automatic undo and never changes host business fields,
comments or opportunities. The binding identity, redirects and transaction guard use `onno_crm_*`
infrastructure tables. Personal-group storage is created only with `CrmFeatures(true)`. Sales remains
ordinary optional example code (`example/src/sales/java`, compiled with `-PsalesExample`).

`ListSpec.selectionCheckboxes(true)` opts a catalog/document table into Onno row-selection controls.
`ResolvedListView` and the `onno-list` descriptor carry `selectionCheckboxes` (default false; the
previous resolved-view constructor remains compatible). Flat and grouped grids share the existing
selection state and batch command path. Header selection covers loaded rows only and has a mixed
state. A visible selection-actions trigger reuses the row menu with explicit batch mode, including
single-row selections, dynamic action discovery, form collection and server authorization. Query,
sort, grouping or view changes clear selection. Custom renderers and register rows are unaffected.

CRM conversation folders are optional `CrmWorkspaceService.Folder` definitions supplied through
`CrmWorkspaceCustomizer` / `Config.withFolders`. Their typed channel/status/priority criteria and
unread predicate or explicit conversation IDs group the current authorized list. First-match
precedence prevents duplicates. The widget mixes folders with ungrouped chats, rendering counts,
sliding drill-in lists and a back control (with reduced-motion and keyboard focus support). No records are moved or copied;
folder definitions do not alter access control or persist new business entities.

Widget action feedback can use `toast.success(message)` (or `error`, `info`, `warning`)
from `@onno/widget-sdk`. It delegates to the shared host notification stack through
`window.onno.toast`; this additive capability requires an updated UI host.

CRM outbound messaging supports multiple `CrmMessageTransport` beans. `ConversationService`
uses `CrmMessageRouter` to select exactly one connected provider per conversation, rejecting
ambiguous claims before enqueueing. Provider account/inbox mapping determines ownership;
workers deliver only after the enqueue transaction commits.

The packaged Gmail adapter in `onno-crm-channels-starter` implements `CrmChannelConnection` and `CrmMessageTransport`.
It owns OAuth token files, Gmail account/thread/checkpoint/dedup/outbox tables, MIME conversion,
and a single-instance polling worker. It publishes CRM changes via the standard repositories.
`CrmChannelAccess`-protected `POST /api/crm/gmail/authorize` starts session-bound PKCE authorization;
`GET /api/crm/gmail/callback` consumes the state and returns to Channels. It is an opt-in development
connector in `onno-crm-channels-starter`; see that module’s README.

Inbox workspaces are application-authored `CrmInboxWorkspace` beans, separate from the persisted
`Inbox` channel-account catalog. Each supplies stable identity, read/write roles, a typed
`Predicate<Conversation>` and a per-workspace presentation customizer. `CrmInboxWorkspaceService`
rechecks membership and access for scoped feeds, messages, delivery, notes and mutations. It selects
views, never changes provider routing. The bookstore crm profile declares Support. The widget uses
scoped reads and content-free page SSE invalidations; shared contacts keep their catalog policy.

`UiEntityAccessPolicy` is a deny-only extension on `UiAccessService` for generic entity surfaces;
policies receive roles, kind, normalized logical name and read/write intent. Existing annotation
RBAC still applies and ADMIN remains the superuser. The CRM registers a gate that blocks generic
conversation/message/identity surfaces for non-admin users, closing REST/UI/MCP/comment/event
alternatives to scoped endpoints. Comment deletion now checks current target read access as well as
authorship. Generic services and repositories remain trusted application APIs, not row-security
boundaries. Scoped folder membership affects display only; workspace predicates and roles authorize.

Inbox workspace pages accept `widget.config("workspace", "support")` to bind a page to one workspace.
The scoped feed accepts `status` UUIDs and `channel`/`priority` enum names (`all` by default);
filters apply after workspace membership and before pagination.

The local Gmail adapter groups email threads by canonical customer within its own mailbox.
Startup consolidates earlier thread-per-chat imports transactionally: messages and comments move,
thread routes remain intact, superseded conversations are soft-deleted, and source/target IDs are
recorded in `onno_crm_gmail_grouping`. Replies retain the thread selected when queued.

Inbox filters accept comma-separated status UUIDs or channel/priority enum names (OR within a field,
AND between fields); omitted values default to `all`. The inbox reuses the host `OptionsFacet`
multi-select chips through the widget SDK.

The scoped inbox renders the standard `EntityListWidget` header and body-view controls. Its feed
also accepts host list parameters: repeated `in=field,value`, `sort`, `dir`, `limit` (1–500, default
100), and an offset cursor returned as `nextCursor`. Membership is checked before filtering, sorting
and pagination. This cursor is an offset, not a stable snapshot under concurrent inserts.


The channels starter also provides an opt-in Instagram Login adapter (`onno.crm.channels.instagram.enabled`).
It uses `Channel.INSTAGRAM`, verifies its account from a private token file, polls provider-visible
messages into Support, and routes text replies through a durable outbox. Account/peer identity and
provider-message deduplication are owned by `onno_crm_ig_*` adapter tables. The worker commits each
message before advancing a conversation-list cursor; uncertain sends require explicit retry and
replies outside the 24-hour window are rejected. It does not configure public webhooks or waive
Meta test-mode restrictions. Tokens and provider response bodies are never returned by CRM APIs.

### CRM channel distribution

The optional published module `onno-crm-channels-starter` now owns the Telegram, Gmail, Instagram and WhatsApp adapters previously under the example. It is auto-configured before the CRM fallback transport and contributes typed `onno.crm.channels.*` configuration metadata. The example owns only sample data, auth and workspace selection. WhatsApp adds signed GET/POST `/api/crm/whatsapp/webhook`, account/number-scoped ingestion, transactionally queued text replies, and delivery/read status handling (`DeliveryStatus.READ`). Private credentials never enter UI responses. Public access/CSRF exemptions remain explicit host configuration; only the webhook route is exempted.

Selection toolbar extensions: `ListSpec.selectionWidget("type")` mounts an SDK
`registerListSelection("type", Component)` component when rows are selected and the viewer has
write access. It receives `ids` and `complete()` (clear selection and reload after success).
Commands must enforce their own server-side authorization; unknown widget types are omitted.

Custom widgets can import `DatePicker` (date only) and `DateTimePicker` (local date and
24-hour time) from `@onno/widget-sdk`. They share the host calendar and emit ISO strings
at day and minute precision, respectively. Generated `LocalDateTime` fields use
`DateTimePicker`. See the [SDK date/time guide](../onno-widget-sdk/README.md#date-and-time-fields).

CRM message bodies support `registerChatMessageRenderer` from `@onno/widget-sdk` (UI host v4+), with predicate/priority selection, live registration, and plain-text error fallback. See [the SDK guide](../onno-widget-sdk/README.md#custom-crm-chat-message-bodies).

The example app owns reply templates as a `CrmReplyTemplates` catalog (name, category, plain-text body,
active flag). CRM managers maintain them in Configuration → Reply templates; agents can read
and insert active templates from the chat composer. The searchable picker previews the text,
appends it to an existing draft, and rejects insertion beyond the channel text limit without
truncation. Agents review/edit the draft and send explicitly. These are saved replies, not
provider-approved WhatsApp message templates. Inactive or deleted templates are excluded.

### Unified contact activity

The CRM Inbox presents one row per contact and a chronological timeline across its channel conversations, internal comments and system events. Underlying conversations retain provider routing and email thread identifiers. The reply selector changes only the destination, and defaults to the latest incoming message's conversation; an unsent draft keeps its selected destination.

`GET /api/crm/contacts/{customer}/activity?offset=0&limit=100` returns `{entries,total,hasMore}` (newest first, limit 1–500). Each entry includes `id`, `conversationId`, `subject`, `channel`, `kind`, `direction`, `authorName`, `body`, `at`, and `deliveryStatus`. Only readable conversations are included, with canonical contact resolution after merging. The UI loads older pages on demand and updates through existing CRM SSE invalidations.

`POST /api/crm/contacts/{customer}/activity` accepts `conversationId`, `type` (`QUOTED`, `CALL_PLANNED`, `CALL_COMPLETED`, `MEETING_PLANNED`, `OTHER`), nonblank `details` up to 7000 characters, and optional local `scheduledFor`. It requires write access to the matching contact's conversation. It stores an internal `SYSTEM_EVENT` without sending a channel message or changing the sales stage. Planned dates are descriptive event text, not reminders or calendar invitations. Domain workflows can also append system events through the existing conversation-message repository.

### Record tag API

`GET /api/tags/{kind}/{name}` lists selectable tag definitions. Definitions are managed through
the owning tag catalog’s ordinary CRUD API; the tag picker does not create definitions. `GET /api/tags/{kind}/{name}/{id}` lists a record's tags.
`POST /api/tags/{kind}/{name}/{id}/{tagId}` adds an assignment; `DELETE` removes it.
Kinds are `catalogs` and `documents`; names normalize to the owning entity. Responses contain
`{id,name,color}`. Definitions have stable UUIDs; assignment mutations are idempotent and reject
tags from another entity library. All reads require entity read access; mutations require write
access, existing live records, and applicable `TagAccessPolicy` checks. Invalid input returns 400;
missing/deleted records return 404; denied access returns 403. Tag assignment changes emit an
`updated` SSE event with entity type `tag` and the record ID.

Framework-owned tables `onno_tags`, `onno_tag_links`, and `onno_tag_imports` are created additively.
CRM installs no tag catalog or tag importer. Applications may use the UI starter's standard
record-tag capability on their own catalogs.

Internal CRM notes keep the inbox composer and use a compact inline reference picker: `@` selects people, `#` selects catalog/document records, and pasted local record links become references. Notes remain ordinary Onno comments, including permission checks and mention notifications. The host `CommentBody` renderer accepts a stored `body` and resolves links for the current viewer. CRM reply drafts and internal-note drafts stay separate.

Unified contact activity entries include `authorAvatarUrl` and `mine` for internal notes. Ownership matches the signed-in identity record ID, not the display name; photos resolve from the live identity catalog using the standard comment avatar resolver. The inbox preserves both fields when rendering notes.

Opening a unified chat selects the channel of its latest incoming or outgoing message, ignoring internal activity. While it remains open, only a newly received client message changes that selection automatically, and unsent drafts suppress automatic changes.

### Personal CRM chat groups

The inbox right-click menu offers **Move to group**, **New group…**, and **Remove from group**.
Groups organize unified contact chats and persist per authenticated username and inbox workspace,
without changing shared folder configuration or conversation access. Group headers offer rename
and delete; deleting a group keeps its chats. Personal groups take precedence over configured
folder rules, and an empty personal group matches no chats.

`GET /api/crm/chat-groups?workspace=<key>` returns `{key,label,customerIds}[]` for the current user.
`POST` to the same route accepts `{operation,key?,label?,conversationId?}` with operations
`create`, `move`, `rename`, or `delete`; create/move require an accessible conversation and move
with a null key removes membership. The server resolves its contact; clients cannot submit an
owner or arbitrary contact IDs. Names must be unique ignoring case within a workspace and contain
1–80 characters; each user can create up to 30 groups per workspace. Mutations return the updated
list and serialize read-modify-write operations transactionally. Host customer and workspace
read access checks apply, and the ordinary CSRF protection covers writes. Grouping does not alter
contact data or send messages. The UI refreshes groups after changes and when the window regains focus.

### UI extension outlets

Host contract v5 adds `registerExtension` and `ExtensionSlot`: opt-in contributions to page/list/form
controls, entity and CRM context menus, composer tools, and right-panel sections. Contributions
receive scoped context and supported host callbacks, have deterministic ordering and isolated
render failures, and can be replaced/unregistered without modifying the host. Backend authorization
continues to govern commands. Timeline bodies retain `registerChatMessageRenderer`.
See the UI contributions section of `docs/EXTENDING.md` for outlet names, context, and examples.

### Opening a workspace conversation

In workspace mode, `/api/divkit/catalogs/crm_conversations/{id}` (also the logical-name aliases)
is a CRM-owned conversation view. It verifies host customer permissions and workspace membership, then renders the
chat using an accessible inbox workspace. Its feed is
`GET /api/crm/inbox-workspaces/{key}/conversation/{id}`; it returns only that contact's conversations
within the authorized workspace and retains normal filtering/pagination. Foreign records return
403 and missing records return 404. Generic catalog/message read and export restrictions remain
unchanged. Without workspace definitions, the normal framework catalog view remains in use.

Applications own their status, Assign, Details, Close/Reopen, and Log activity controls
through application-authored widget contributions. The contact panel has no built-in edit, merge, or identity-linking forms.
The CRM starter supplies slots and authorized commands, without registering these buttons.
Application code may omit, replace, reorder, or add its own contributions.

The example application owns Templates as a `crm.chat.composer` contribution
(`example.crm.composer.templates`), including its catalog and picker. The CRM
starter supplies the empty composer slot and controls draft insertion and limits.

Conversation statuses are optionally application-authored through `CrmStateConfiguration`.
Customer stages remain ordinary host catalog fields. No stage projection or contact editor is
installed. The host also owns sales models, templates and their action contributions.

Widgets can anchor a shared `PopoverContent` to an existing field using SDK `PopoverAnchor` with `asChild`. Use this for input-driven suggestion popovers without adding a separate trigger button; preserve input focus via the popover autofocus callbacks. CRM controls use SDK buttons, labels, inputs, selects, and popovers.

Inbox workspaces declare optional toolbar filters with `CrmInboxWorkspace.list(list -> ...)`,
using `ListSpec.filter` and its standard options/text/date controls. Scoped feeds return `filters`
and accept `eq/in/like/prefix/ge/le` only for declared filters; membership and contact access are
checked before filtering. No toolbar filters are supplied implicitly.

CRM channels use extensible string keys with connector-owned `CrmChannelDefinition` metadata
(`GET /api/crm/channels/types`); no fixed channel enumeration or Channels page is installed.
The optional settings widget uses `crm.channel.settings` extension contributions; bundled provider
setup and branding live in the channels starter. Workspace `.list(...)`/`.view(...)` uses ordinary
`ListSpec` resolution for both the inbox renderer and table. Status bindings read an existing host
catalog or enumeration through `CrmStateConfiguration.catalog(...)`/`.enumeration(...)`, with no
CRM status table or mirrored records. Channel keys and status UUIDs are breaking storage changes.

Reply capability is independent of connectivity. The delivery response carries `connected`,
`replyCapability` (`AVAILABLE`, `READ_ONLY`, `WINDOW_CLOSED`) and `replyReason`.
For example, `new Connection(true, "Archive", 8000, ReplyCapability.READ_ONLY,
"This archive accepts incoming messages only.")` represents a healthy read-only channel.
A disconnected provider reports `connected=false`; workspace reply permission is checked separately.
The composer distinguishes these states, and reply/retry commands enforce `canSend()` on the server.

Priority is optional: bind a host catalog/enum with `CrmPriorityBinding.catalog(...)` or
`.enumeration(...)`; use `.options()` in an ordinary priority filter. No priority enum, default or
implicit column/filter is installed. The priority UUID is scoped to that host source.
Assignment, close/reopen, status-change and manual identity-linking HTTP actions are not bundled.
Host `EntityView<Conversation>` beans declare ordinary `ActionSpec` ROW/DETAIL handlers; extension
buttons receive their descriptors and call `context.execute(key, inputs)`. The scoped action API is
`/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions` (GET descriptors, POST `/{key}`
with `{inputs:{...}}`). It checks workspace write access, application read-only mode, action roles
and record visibility/enabled rules. CRM adds no business-field mutations or automatic history
messages for these actions. Hosts own the handler and any desired history records. Connector code
can still use the low-level identity-link service for provider routing.
