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
generic REST API, a default React UI, an MCP tool surface for AI agents,
and migration history. You do not hand-write tables, DTOs, or CRUD controllers. Behaviour that *is*
code — posting rules, validation, lifecycle hooks, commands — is plain, refactorable,
compiler-checked Java, never string-mapped configuration.

The `su.onno.process` model applies the same typed-Java principle to durable business-process routes;
the starter persists instances/work and the web starter exposes an authenticated human task inbox.

Java packages are always `su.onno.*`. The published Maven group is `su.onno` (core,
Apache-2.0) and `su.onno.enterprise` (commercial connectors). The UI Gradle plugin id is
`su.onno.ui`.

## Modules

| Module | Group | Role |
| --- | --- | --- |
| `onno-framework` | `su.onno` | Core: annotations, metadata scanners + registry, schema diff/migration, JDBI persistence, posting engine, complete typed process graph/runtime/schema, repository contracts, events, outbox, and the behaviour beans the web layer serves: commands (`su.onno.command`), live checks (`su.onno.validation`), picker options (`su.onno.picker`) and the login identity link (`su.onno.identity`). |
| `onno-framework-starter` | `su.onno` | Spring Boot auto-configuration that wires the core: metadata registry, repositories, schema initializer, posting and process services, timer/subprocess polling, number generation, secret cipher, background jobs, the transport-neutral service layer (`su.onno.service`: generic catalog/document/register query and command services, `CommandService`, `AccessService`, record-policy enforcement — `RecordAccess`, `RecordScopeCompiler`, `RecordWriteGuard` — and `AccessSubjectResolver`; refusals are a `ServiceException` carrying a status), and the cross-node `ClusterEventBus` (Postgres `LISTEN`/`NOTIFY` on a Postgres datasource, no-op on H2) that keeps SSE live UI, task inboxes, presence and notifications in sync across instances. |
| `onno-web-starter` | `su.onno` (package `su.onno.web`) | The web layer over the service layer: generic REST controllers under `/api/**` (mapping `ServiceException` to HTTP statuses), commands/checks/picker endpoints, media uploads, SSE event stream, comment threads, per-user notifications; serves the bundled default UI and the `/api/ui` model. |
| `onno-ui` | (npm `@onno/ui`) | The UI: the React shell, default screens, page building blocks and typed data hooks apps import. `onno-web-starter` builds it into the default shell. |
| `onno-crm-starter` | `su.onno` | Composable messaging: inboxes, conversations/messages, channel identities, and the `@onno/crm` React components. Explicit host customer bindings enable it; customers, employees, sales models and navigation remain host-owned. |
| `onno-auth-starter` | `su.onno` | Spring Security: database (runtime-managed accounts), in-memory, OIDC/SSO, and resource-server (JWT) modes; JSON login/logout; CSRF; per-request principal. |
| `onno-mcp-starter` | `su.onno` | Model Context Protocol server exposing the model + CRUD + register reads + posting as AI-agent tools, generated from the registry. |
| `onno-import-starter` | `su.onno` | CSV import (preview, mapping, upsert, dry-run, document grouping) through the same command path as the UI. |
| `onno-print-starter` | `su.onno` | Print forms: `PrintForm<T>` beans fill Word (`.docx`) / Excel (`.xlsx`) templates via Apache POI; record-menu actions, list batch printing (zip), admin-uploaded template overrides (`onno_print_templates`), optional PDF via a `PdfConverter` (Gotenberg / LibreOffice). |
| `onno-ui-gradle-plugin` | (`su.onno.ui` plugin) | Builds an app's own React UI (`src/main/ui`, a Vite app on `@onno/ui`) into its jar and generates `model.gen.ts` from the Java model. |
| `onno-archunit` | `su.onno` | Test-scope ArchUnit rules encoding onno's invariants (`OnnoRules`): posting runs outside Spring transactions, request handlers use the record-scoped service layer, domain classes stay plain Java and reference records by `Ref<T>`, `List` methods drain keyset windows. The framework's modules check themselves with the same rules plus core-specific ones (core uses only Spring Data's mapping and repository contracts; service-layer operations take an `AccessSubject`). |
| `example` | (not published) | The Onno Books retailer app: a compact end-to-end consumer and smoke-test fixture. |
| `onno-tochka-starter`, `onno-yookassa-starter`, `onno-ozon-starter`, … | `su.onno.enterprise` | Commercial connectors in the separate [onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo. |

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
   `Commands`/`Checks`/`PickerOptions` registries.
5. **Layer on optional starters.** `onno-web-starter` adds the REST API, the `/api/ui` model and serves the UI;
   `onno-auth-starter` adds the security chain; the integration starters add their endpoints and
   beans. Model-bearing modules such as `onno-crm-starter` additionally register their own metadata
   package and repositories (their screens ship as npm packages beside the Java code) without expanding the
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
- **`@TabularSection`** — owned line-item collections on a catalog or document; rows extend `TabularSectionRow`. Catalog child tables use
  `catalog_<owner>_<section>`. Repositories, REST/MCP and the common form grid (including `rowField`
  hints) support both owners; generic catalog writes save the header and rows atomically.
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
  (`readRoles`/`writeRoles`, plus `postRoles`/`unpostRoles` on documents), **`@Attribute`** (`required`, `length`, `precision`/`scale`, `secret`,
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
  `@onno/ui` `TaskInbox` component
  resolves delegation targets and their live avatar/photo through the `IdentityLink` catalog,
  opens typed task subjects in the shell's adjacent detail pane, and provides the same loop to
  humans. `JdbcProcessEngine` publishes `ProcessTasksChangedEvent` only after its JDBI
  transaction commits; the UI routes a payload-free `tasks-changed` SSE invalidation to affected
candidate users/roles and admins, so every open inbox refetches automatically.

`HumanTask.assignment(payload)` is the automatic-routing seam. It may use an injected application
service and return `TaskAssignment.identities(selectedEmployeeRef)`. The employee record UUID is
the stable task owner; login and display name are mutable snapshots only. The authenticated
principal is resolved through the `IdentityLink` bean to that same UUID.

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
transform the payload; missing mappings roll the transaction back. `migrate()` resets every live token
of the instance before re-entering any, so a parallel join only counts branches that arrived under
the new version. A migration must map a branch token to a step inside its own branch or to the
join; the engine does not yet enforce this. The token, join, cancellation and migration semantics
are model-checked in [docs/formal](formal/README.md). Cancellation similarly closes
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

Domain annotations carry no UI placement or field-display hints; presentation lives in the React
app (see [UI layer](#ui-layer)).

Authored Java uses the shared serializable `Field<E,V>` getter token wherever a value denotes a
model field. Command predicates and titles (`RecordRow<E>`), check dependencies
(`CheckBuilder.dependsOn`), register queries and `Q` accept method references such as
`Order::getStatus`; getters from unrelated entities are compile errors, inherited getters remain
valid. `Fields.name(...)` resolves the JavaBean property, boundary resolvers map it to the logical
API field, and query services translate it to the physical storage column — compiler-checked
source, validated SQL identifiers. String overloads are dynamic escape hatches. Strings remain
correct for semantic identifiers (roles, command/process keys, labels) and dot paths
(`"participants.employee"`).

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
  (`INFORMATION_SCHEMA` is filtered to `CURRENT_SCHEMA`, and covers tables, columns and `UNIQUE`
  constraints), and the previous `SchemaSnapshot` (stored as JSON in `onno_schema_history`).
  Same-named tables in other PostgreSQL schemas are ignored. The snapshot is how *type changes* and
  *removed entities* are detected on later boots.
- **Register keys are reconciled**: an information register's key — `(_period, dimensions…)`, the
  same tuple its upserts name in `ON CONFLICT` — is diffed against the live `UNIQUE` constraints, so
  changing `periodicity` or a `@Dimension` on an existing database moves the constraint instead of
  leaving the old one enforcing a key the code no longer uses. Widening the key (a finer periodicity,
  an added dimension) is safe by construction and applies by default; narrowing it can meet rows that
  duplicate under the new key, so it is destructive-gated. A table that declares a key owns every
  `UNIQUE` constraint on it — use an index, not a constraint, for your own uniqueness on one.
- **Renames keep data**: declare the former name with `previousNames` on `@Catalog`/`@Document`/
  `@Attribute`; the engine emits a `RENAME_TABLE`/`RENAME_COLUMN` instead of drop+add.
- **SQL identifiers fail fast**: metadata-derived table/column names and rename aliases must match
  `[A-Za-z_][A-Za-z0-9_]*`; the scanner rejects unsafe identifiers before DDL or query rendering.
- **Change kinds** (`SchemaChange.Type`): `CREATE_TABLE`, `RENAME_TABLE`, `RENAME_COLUMN`,
  `ADD_COLUMN`, `ALTER_COLUMN_TYPE`, `ALTER_COLUMN_NULLABILITY`, `ALTER_UNIQUE_KEY`, `DROP_COLUMN`,
  `DROP_TABLE`. Drops are only ever proposed for objects present in the previous snapshot (never
  user-created tables).
- **Data migrations**: implement `AppMigration` (`version()` compared segment-wise, `migrate(MigrationContext)`)
  as a Spring bean. Each runs exactly once per database, in version order, inside a transaction,
  recorded in `onno_schema_history` (a unique constraint arbitrates concurrent starts).
- **Search indexes** (PostgreSQL only): btree indexes on framework-queried columns are re-asserted
  with `IF NOT EXISTS`. Trigram (`pg_trgm` GIN) indexes for the list/ref-picker `q=` substring
  search are **opt-in per attribute** with `@Attribute(searchIndex = true)`; the system columns
  (`_code`/`_description`, `_number`) are always indexed unless `onno.schema.search-indexes=off`.
  `SearchIndexReconciler` converges them on a background thread after `apply`: framework-owned
  trigram indexes (identified by definition — GIN + `gin_trgm_ops` on a catalog/document table — not
  by name) that are no longer declared, or left invalid by an interrupted build, are dropped; missing
  ones are built with `CREATE INDEX CONCURRENTLY` under a `pg_try_advisory_lock`. Before building it
  also waits, holding no connection, for the advisory lock it shares with the record-scope index
  reconciliation (see [Record-level access policies](#record-level-access-policies)), so the two
  never build at once: two concurrent builds on one table deadlock. After 30 minutes of waiting it
  leaves the search indexes for the next start. Search covers every String
  attribute regardless — unindexed ones fall back to a `LIKE` scan.

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

Unposting runs `BeforeUnpostHandler.beforeUnpost()` first (when the document implements it); a throw
vetoes the unpost and leaves the document posted. It does not run on a re-post or on the internal
reverse/replay of later documents during chronological restoration.

`PostingEngine` (`onno-framework/src/main/java/su/onno/posting/PostingEngine.java`) runs
`beforeWrite` → `beforePost` → business-rule validation, then **inside its own JDBI transaction**
atomically claims an existing unposted document, inserts movements, updates register totals, rejects
negative `BALANCE` results on dimension tuples touched by that post unless the register declares
`allowNegative = true`, and writes back computed fields. Posting a missing or already-posted document
is rejected before any movement is persisted, so retries cannot double-count registers. Inside that
same transaction it appends `@DomainEvent` outbox rows; after commit it calls `afterPost` and
publishes a Spring
`DocumentPostedEvent` (`DocumentUnpostedEvent` for unpost). These post-commit callbacks are isolated:
a throwing `afterPost` or listener is logged at ERROR and neither fails the committed post nor skips
the remaining callbacks.

`PostingService.repost(...)` is the explicit recalculation operation for a posted document: it
reverses the old movements and writes the recalculated movements atomically, so a failed recalculation
leaves the original posting intact. The generic REST `.../{id}/post` command selects this operation
when the document is already posted. Core `post(...)` remains a strict first-post operation, and
unposting a draft or deleted document is rejected.

For a register declared with `postingOrder = PostingOrder.CHRONOLOGICAL`, posting, reposting, or unposting a
backdated document discovers later active documents through that register (including documents
linked through other chronological registers), reverses them newest-first, applies the requested
change, and reposts them oldest-first. The restoration uses one serializable JDBI transaction, and
register balance reads made by `handlePosting` see that same transaction. The requested document's own
movements are computed after the later documents are reversed (its `handlePosting` runs again
inside the restoring transaction), so a backdated issue is costed at the balance as of its own
date. Documents with identical timestamps are not re-sequenced among themselves. Restored documents do not
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

### Period close

`PeriodCloseService` (`su.onno.period`) holds change-prohibition dates per scope — global, bounded
context, accumulation register, or organization (`@PeriodCloseOrganization` field) — soft or hard,
in the framework-owned `onno_period_close` table. A document
dated on or before the latest applicable close date cannot be created, changed, delete-marked,
posted, reposted or unposted. The check is authoritative below controllers: `PostingEngine` runs it
inside the posting transaction (and for every later document a chronological restoration would
repost, so a backdated change cannot cascade into a closed period), `OnnoBeforeConvertCallback`
runs it on every catalog/document save — repository and generic UI/REST/import/MCP writes alike —
and `DocumentCommandService` checks a delete before unposting a posted document.
Rejections throw `PeriodClosedException` (HTTP 409 `period-closed`, including from
`posting-preview`). Close/reopen (`ADMIN` + `onno.period-close.manage-roles`; reopen needs a
reason) and overrides (`onno.period-close.override-roles`, or an explicit
`PeriodCloseService.override(...)` block) are audited in `onno_period_close_audit`. The web starter
serves `/api/period-close` (and reports `features.periodClose` in the UI model); `@onno/ui`'s
`<PeriodClose />` is the admin screen an app registers as a page; the MCP starter adds the read-only
`period_close_status` tool. `@PeriodCloseExempt` opts a document type out; `@PeriodAdjustment`
document types may still change a soft-closed period (audited as adjustments). Full contract: [PERIOD_CLOSE.md](PERIOD_CLOSE.md).

## Query paths

There is no universal query DSL. Catalog and document API reads use the web starter's dedicated
`CatalogQueryService` and `DocumentQueryService`, including keyset pagination and display-value
resolution. Typed business reads of accumulation registers use `RegisterRepository.query()`;
generic UI and MCP register projections use `RegisterQueryService`. The small
`su.onno.query` package contains only shared cursor/keyset and SQL-rendering utilities used by those
runtime paths. Every query service method (`su.onno.service`) takes the `AccessSubject` it reads for and
applies that subject's record scope inside the query (see
[Record-level access policies](#record-level-access-policies)); typed repositories are trusted code
and unscoped.


The public `su.onno.ui.KeysetWindow` helper executes the shared catalog/document seek windows,
including backward and focus navigation. `su.onno.query.Cursor` owns encoding and decoding of
both plain and access-scope-bound tokens; the UI helper maps scope mismatch to HTTP 400.
The pagination contract and migration status are specified in
[HEADLESS_READ_API.md](HEADLESS_READ_API.md#collection-pagination).


Forward collection pagination is shared by `su.onno.query.KeysetWindow` (typed SQL tuples,
NULLS LAST, natural composite keys; `Order.notNull(...)` columns sorted one way seek with a single
row-value comparison over a bare ORDER BY, so the feed indexes on processes, tasks, comments and
notifications serve the window) and `su.onno.query.Cursor` (context-bound v2 positions).
The UI `KeysetWindow` retains catalog/document focus/backward navigation with the same v2 codec and adapts
collection scope errors to HTTP 400. Accumulation movements and balances, comments, notification
feeds, related lists, hierarchy nodes, tasks, processes and record history now use bounded seek
windows. Only an explicit register `count=exact` invokes a total count. Java convenience methods
that return a plain `List` keep complete-result semantics by draining the cursor
(`KeysetWindow.drain`), so trusted callers never receive a silently truncated first window. See HEADLESS_READ_API.md for wire contracts.

Process assignment JSON remains authoritative. `onno_process_work_candidates` is its transactional
SQL projection; `_candidates_indexed` supports resumable 500-row backfill of old work items before
paged reads. Notification startup similarly backfills normalized `_target_kind`, `_target_name`,
`_target_id` and `_target_indexed`, enabling record visibility in SQL before LIMIT. Process mutations
and posting semantics otherwise retain their existing contracts.

## Generic REST API

Catalog/document reads default to logical camelCase names, matching the partial-write vocabulary;
the prior storage-shaped response remains available explicitly with `?representation=storage`
(full contract: [HEADLESS_READ_API.md](HEADLESS_READ_API.md)). Writes accept both logical and storage
aliases and reject unequal duplicates. Temporal values are normalized at the read/write boundary:
`LocalDate` is `yyyy-MM-dd`, and `LocalDateTime` is offset-free ISO wall time. Offset- or
zone-bearing `LocalDateTime` writes are rejected with a field-specific `400` rather than silently
discarding the offset. `LocalTime` is `HH:mm[:ss[.fraction]]`. `Instant` is an absolute moment:
it is stored as `TIMESTAMP WITH TIME ZONE` (UTC), written with an explicit `Z`/offset (offset-free
writes are a `400`), and read back as ISO UTC (`…Z`); the web client renders and edits it in
`onno.ui.time-zone`, else the viewer's browser zone. JDBC timestamp variants remain normalized on reads. This keeps PostgreSQL,
H2, the bundled forms, and headless clients on one round-trip-safe representation.

All endpoints are under `/api/**`, authenticated, and (for mutations) CSRF-protected. `{name}` is
the entity's annotation **logical name** (e.g. `Properties`, not the class `Property` or a localized title), matched
case-insensitively with spaces/underscores stripped. **There is no anonymous manifest endpoint** —
agents introspect the
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
| Catalogs | `GET /api/catalogs/{name}/{id}`, `/children?parent=` (`folders=true`: folders only with `hasChildFolders`), `/tree` (`ids=`: only the paths to those records, record-scoped); `POST`/`PUT /{id}`/`POST /{id}/duplicate`/`DELETE /{id}` (duplicate includes tabular rows with fresh IDs and omits secrets; `POST`/`PUT` accept `@relations` — join-entity rows replaced in the same transaction, see HEADLESS_READ_API.md); `POST /validate` / `POST /{id}/validate` — dry-run the write lifecycle (constraints + hooks + business rules) without persisting, always 200 with `{valid, fieldErrors, formErrors}` — the form's live as-you-type validation (web-starter) |
| Contextual forms | `POST /api/ref-options/search` — Ref search with live header/row/id context; the field's `PickerOptions` bean adds badges, disable reasons, or filtering; an empty query leads with up to 5 of the caller's recent picks (`_recent: true`, opt out with `recent: false`), re-read through the same search so deletion mark, record scope and the request's `where` still apply; `POST /api/ref-options/recent` - remember a pick `{targetKind, targetName, id}` per user and target in the framework-owned `onno_ref_recent` table (20 newest kept; `204` whether or not the record is visible, so it is no existence oracle; #409); `POST /api/ref-options/decorate` - the "Show all..." picker dialog's window check: the field context plus `ids`, re-read through the same search and the field's `PickerOptions`, answering `{offered, decorations}` and never naming an id outside the caller's scope; `GET /api/ui/pickers/{kind}/{name}` - the dialog's list descriptor (default columns and filters, no write affordances, `hierarchical`; #411); `POST /api/checks/{kind}/{name}/{key}` — a `Checks` bean's advisory `ERROR`/`WARNING`/`INFO` feedback on unsaved form values (write-authorized) (web-starter) |
| Documents | `GET /api/documents/{name}/{id}`, `/{id}/posting-preview`; `POST`, `PUT /{id}`, `POST /{id}/duplicate`, `DELETE /{id}`, `POST /{id}/post`, `POST /{id}/unpost`; `POST /validate` / `POST /{id}/validate` — same dry-run validate as catalogs, tabular rows included (web-starter) |
| Registers | `GET /api/registers/{name}/movements?from=&to=&where=`, `/balance?asOf=&where=`, `/turnover?from=&to=&where=` — logical rows (field names, `period`, `recorder`, `movementType`) as a keyset window `{rows, nextCursor, hasMore}` (`cursor`, `limit` default 100, max 500; balance and turnover also take dimension equality parameters such as `?book=<uuid>`; see [Collection pagination](HEADLESS_READ_API.md#collection-pagination)); `where` is the structured JSON predicate (`Where`) over movement rows (dimensions, resources, `period`, `recorder`, `movementType`) for movements/turnover and over balance rows (dimensions, resource balances) for a balance. `GET /api/list/registers/{name}/aggregate` — the catalog/document `/aggregate` read over the movements (resources summed signed; `period` buckets) (web-starter) |
| Information registers | `GET /api/information-registers/{name}/records?from=&to=&where=&cursor=&limit=` — stored history as a keyset feed `{rows, nextCursor, hasMore}`, inclusive ISO date-time bounds, a structured `where` over dimensions/resources/attributes/`id`/`period` (unknown parameters and fields are `400`), newest period then ID descending; nonperiodic registers order by ID and reject period bounds. Limit defaults to 100, clamped to 1..500; the cursor is sealed to the caller's record access. Entity/record read policies apply before paging; logical fields include `id`, `period` (periodic only), and reference `*Display` sidecars; secrets are redacted. `GET /api/information-registers/{name}/slice-last?at=&where=` — the slice of last values: per dimension tuple the record in force `at` (default now; non-periodic registers take no `at`), `where` selecting among the slice, at most 5,000 rows. (web-starter) |
| List feed | `GET /api/list/catalogs/{name}`, `/api/list/documents/{name}` — the only catalog/document collection read: pass `?cursor=&limit=` and read `{rows, nextCursor, hasMore, prevCursor, hasPrevious}` (keyset pagination, constant-time at any depth, no skip/dup on shifting data); `?focus=<id>` returns the window containing that record (echoed as `focus`; a record the query cannot see falls back to the first window) and `?before=<prevCursor>` the window above one; `?count=exact\|estimate` adds a total. `?ids=` returns just those rows (the grid's single-row live patch); `?where=` applies a structured JSON predicate server-side (`{"posted":true,"total":{"gte":100}}`, compiled by `Where`; see [HEADLESS_READ_API](HEADLESS_READ_API.md)) — also accepted by `/groups`, `/aggregate` and the ref-option bodies; the toolbar's facet params (`eq`/`in`/`like`/`prefix`/`ge`/`le`) are validated against the entity's columns. `GET /api/list/registers/{name}/movements`, `/balance` — register data for the register report, same cursor envelope and facet params; movement rows carry a localized `_movement_type_display` + `_movement_type_color` (web-starter) |
| List grouping | `GET /api/list/{kind}/{name}/groups?groupBy=&granularity=&agg=fn,col&q=&where=` — backend `GROUP BY`: `{groups: [{label, color?, count, values[], expand[]}], capped}`. One header per value (or per `day`/`month`/`year` bucket for a date column), each carrying an `expand` predicate the list replays on the feed to load the group's rows. Same WHERE as the flat list; headers cap at 200 (web-starter) |
| Aggregate | `GET /api/list/{kind}/{name}/aggregate?metric=&field=&groupBy=&groupByDate=&seriesBy=&where=&dateField=&from=&to=` — server-side `GROUP BY` behind `@onno/ui`'s measures (`Stat`, `Chart`, `Sparkline`, `Gauge`, `useAggregate`): `{buckets: [{key, label?, series?, seriesLabel?, value, value2?}], truncated, span?}`, O(buckets) over the wire instead of the entity's whole table (#199). `groupByDate` (`minute…month`) buckets a timestamp via `DATE_TRUNC`; blank `groupBy` yields one grand-total bucket; enum/Ref bucket values carry a resolved `label`; `span` is the windowed MIN/MAX of `dateField` (granularity auto-sizing). Date-bucketed axes always zero-fill empty periods with `{key, value: 0}` fillers over the window — or between the first/last data when unbounded (#246). Buckets cap at 1000 (`truncated: true`) (web-starter) |
| Settings | `GET`/`PUT /api/settings` — `@Constant` values as `{name, type, value}`, ADMIN (web-starter) |
| Commands | `GET /api/commands/{kind}/{name}[?id=]` — the entity's `Commands` descriptors (title, description, group, inputs, target) plus per-record state `{visible, enabled, title?}`; `POST …/{key}` with `{id?, inputs}` runs one and answers `{refresh, feedback, navigateTo}` (feedback is meaning only: `severity`, `title`, `message`, `details`; `navigateTo` is an app path or `onno://` link from `CommandResult.open(ref)`/`navigate(route)`, applied after feedback and refresh — command and external URLs are rejected; batch summaries omit per-record destinations); `CommandRejectedException` maps to HTTP 422 with `fieldErrors`/`formErrors`; `GET …/{key}/defaults[?id=]` returns opening input values `{values, rows}`; `POST …/{key}/batch` runs `{ids:[…]}` (≤500, `{ok, failed, total, feedback?}`, each id in its own transaction). `c.dynamic(...)` commands are resolved again on every listing and run. Every call honours entity write access, the command's `roles` (#227), `onno.web.read-only`, record scope and `availableWhen`/`enabledWhen`. `POST /api/{catalogs\|documents}/{name}/batch-delete` is bulk DELETE (web-starter) |
| Media | `POST /api/media`, `GET /api/media/{key}`, `GET /api/media/limits` — authenticated uploads; served with `nosniff`, safe raster images inline and other types as attachments ([MEDIA_UPLOADS.md](MEDIA_UPLOADS.md)) (web-starter) |
| Comments | `GET`/`POST /api/comments/{kind}/{name}/{id}`, `POST /api/comments/{commentId}/reactions`, `DELETE /api/comments/{commentId}` — discussion threads with replies (`parentId`) and grouped reactions, gated on read access to the record and on `onno.comments.enabled`; the thread `GET` is a keyset window `{rows, nextCursor, hasMore}`; the UI shows a thread where the entity's customization sets `comments: true`; `createdAt`/`editedAt` are zone-qualified instants (`…Z`) (web-starter) |
| Record history | `GET /api/history/{kind}/{name}/{id}[?cursor=&limit=]` — field-level change history of one catalog item or document, newest first, as a keyset window `{rows, nextCursor, hasMore}`, for entities that opted in with `@Catalog/@Document(history = true)` (404 otherwise); gated exactly like a read of the record (entity grant + record scope), restricted references masked, secrets never included. The UI model marks opted-in entities `history: true`, and `@onno/ui`'s `<RecordHistory />` renders it (web-starter) |
| Period close | `GET /api/period-close` (locks), `GET /api/period-close/audit`, `POST /api/period-close/close`, `POST /api/period-close/reopen` — see [Period close](#period-close); the UI model reports `features.periodClose` when `PeriodCloseService` is wired, and `@onno/ui`'s `<PeriodClose />` is the admin screen (web-starter) |
| Mentions | `GET /api/mentions?q=[&kind=people\|catalogs\|documents]` — comment typeahead over readable records; the UI uses `@` for people mentions (`kind=people` narrows to the `IdentityLink` catalog, falling back to all catalogs when no identity link is configured) and `#` to reference any record — no `kind` sweeps documents and catalogs alike, searchable by name or code. Suggestions carry a secondary `hint` (a person's `email` attribute, a catalog record's code, a document's `yyyy-MM-dd` date). `GET /api/mentions/resolve?kind&name&id` resolves one triple to its live display plus a `person` flag (same per-viewer read gate) — the compose box uses it to swap a pasted internal `/ui/...` record URL for an `@` (person) or `#` (anything else) mention. Bodies carry `@[Display](kind/name/id)` / `#[Display](kind/name/id)` tokens resolved live; readable `@` mentions publish `EntityMentionedEvent` (consumed by notifications; additive via `@EventListener`) (web-starter) |
| Notifications | `GET /api/notifications[?unread&cursor]` — the caller's keyset-paginated timeline `{rows, nextCursor, hasMore, unreadCount}` (for a record-scoped viewer, rows about unreadable records are withheld and not counted); `POST /api/notifications/{id}/read` and `POST /api/notifications/read-all` mark read. Every call is scoped to the caller's identity (no cross-user reads). Rows persist in the framework-owned `onno_notifications` table; new ones push over the `notification` SSE event (routed by recipient, relayed across nodes over the `ClusterEventBus`). Built-in producers: comment mentions and record assignment (`@AssigneeField`); apps add more by calling `NotificationService.notify`. Gated by `onno.notifications.*` (web-starter) |
| UI model | `GET /api/ui/model` — the readable entities (`kind`, `logicalName`, `title`, `context`, `route`, rights), information registers, `@Constant` settings (administrators) and the signed-in user; `GET /api/ui/view/{catalogs,documents}/{name}[/{id}\|/new\|/{id}/duplicate]` and `/api/ui/view/registers/{name}` — the default list, record form or register descriptor as data (columns, filters, field metadata, commands and record actions with their state; no icons, widths or titles). The UI `{name}` is the snake_case route name (`Stock Receipts` → `stock_receipts`), while REST accepts any spelling of the logical name. A record form's actions are `{key, url[, title][, danger]}`: the built-in `openInList` (read access only; opens the list at `?focus=<id>`), `duplicate`, `delete`, for documents `unpost`, and module record actions such as `print-invoice` (web-starter) |
| Record refresh | `GET /api/ui/record-refresh/{catalogs,documents}/{name}/{id}` returns `{record, actions, commands, readOnly[, canPost]}`. It uses the record view's read and write scope checks and computes the record actions (`{key, url[, title][, danger]}`, presentation-free) from the same saved row as the logical record. The SPA updates the mounted form and action cluster in place. |
| Theme/config | `GET /api/theme`, `GET /api/config`, `GET /api/branding`, `GET /api/ui/login` (web-starter) |
| Events | `GET /api/events` — SSE stream of CRUD/posting changes, `tasks-changed` inbox invalidations, plus `presence` viewer-set updates (web-starter); filtered per subscriber by entity read access or process-task audience (#190) |
| Presence | `POST /api/presence` (body `{path, action}`) — mark presence on any route (`enter`/`heartbeat`/`leave`); the server derives the identity from the path (a record, an entity list, or any page/dashboard), gating entity routes on read access while a page is visible to any signed-in user. Identity from the session, heartbeat-kept + TTL-expired, relayed across nodes over the `ClusterEventBus`. `GET /api/presence` — the ambient snapshot (routes the caller may see) that seeds the client store behind the tab/row/sidebar collaborator avatars (the viewer's photo, initials fallback); kept live by `presence` SSE deltas (web-starter) |
| Auth | `POST /api/auth/login`, `POST /api/auth/login/{method}[/challenge]` (application `LoginMethod` beans), `POST /api/auth/logout`, `GET /api/auth/me`, `GET /api/auth/csrf` (auth-starter) |
| Import | `POST /api/import/{catalogs,documents}/{name}/csv[/preview]` (import-starter) |
| MCP | `POST /mcp` — streamable-HTTP MCP transport (mcp-starter) |

Catalog/document attributes declared with `@Attribute(secret = true)` are write-only and are excluded
from list filter, sort, grouping, and aggregate allowlists; list-query shape and counts therefore
cannot be used as a blind oracle for encrypted values.

> **SPA routing:** the server returns `index.html` only for `GET` navigation under `onno.ui.path`
> whose `Accept` header includes `text/html`. Missing assets, unknown `/api/**` routes, non-GET
> requests, and paths outside the configured UI mount return their normal `404`/`405` responses.

The catalog/document `POST`/`PUT` writes (`CatalogCommandService`/`DocumentCommandService`, shared by
the REST API, the generated UI, CSV import, and the MCP tools) map the request body onto the typed
entity (`EntityBodyMapper`) and save it through `EntityStore` — Spring Data JDBC's aggregate
template, the same persistence path and entity callbacks as `repository.save(...)`. Numbering,
`onFilling()` (create), `beforeWrite()`, attribute and `Validated` rule validation, secret
encryption, period close, record history, `afterWrite()`, domain events and the commit-deferred
`EntityChangedEvent` are therefore one implementation for every write path. The command services
add only the caller-facing checks around the save (read-only mode, entity and record access, the
post-write scope check, all in one transaction). Validation previews run only the pre-write phase
and never call `afterWrite()`; posting still runs its own lifecycle in `PostingEngine`.
For hierarchical catalogs the save callback also validates the persisted parent chain after the
hooks: parents are live folders in the same catalog, cycles are rejected, and a folder with active
children cannot be demoted or soft-deleted. The written row and ancestors are locked through the
transaction-bound JDBI connection so concurrent moves cannot commit a cycle. Generic live
validation repeats these rules without locks or writes. Failures are field-scoped on `parent`.
Tabular-section `lineNumber` in read payloads is the 1-based position in stored order.

The same pipeline backs **live form validation**: `POST /api/{catalogs,documents}/{name}[/{id}]/validate`
dry-runs it — constraints, hooks, `Validated` rules (a Java conflict check included) — against the
submitted values without persisting or consuming a number, and returns
`{valid, fieldErrors, formErrors}` with 200 either way. The generated form calls it debounced while
the user edits, painting a field-scoped rule (`BusinessRule.onField`) inline on its input and
cross-field messages as a form-level notice, before Save is ever pressed.

## UI layer

The server describes the business model and enforces every rule; it never describes screens. The UI
is the npm package **`@onno/ui`** (top-level `onno-ui/`): a React shell that renders a default list,
record form and register report for every readable entity, with navigation grouped by bounded
context. Its full API is [onno-ui/README.md](../onno-ui/README.md); the code is walked through in
[UI_TOUR.md](UI_TOUR.md).

- **Two ways to get a UI.** With no UI code, `onno-web-starter` serves the default shell it bundles
  (`buildFrontend`, Node 22.22, into `classpath:/static/ui/`). An application that wants its own
  pages applies the `su.onno.ui` Gradle plugin (`onno-ui-gradle-plugin`) and writes an ordinary Vite
  app in `src/main/ui`: `buildUi` packs it into `classpath:/onno-app/`, which the starter serves in
  its place; `generateUiModel` writes `model.gen.ts`, the TypeScript types of the Java entities,
  enumerations, information registers, constants and roles; `onnoUiInit` seeds the app.
- **The contract.** `GET /api/ui/model` lists what the caller may read and do;
  `GET /api/ui/view/...` returns an entity's default list, form or register descriptor as data —
  columns, filters, field metadata (names, types, references, constraints), commands and record
  actions with their per-record state — never icons, widths, widgets or English titles. The data
  itself flows through the generic REST API; live changes through `/api/events`.
- **`defineApp`.** `main.tsx` registers `entities`, `pages` (`path` for ordinary pages, typed
  logical `list` or `record` for entity screens), navigation and extensions. Entity presentation is
  one `EntityCustomization<E>` per entity (`entities/Orders.ts`), applied wherever the entity shows:
  its default list and form, embedded lists and custom pages. Which fields show is always an
  explicit array (`list.columns`, `form.fields`); `fields` is presentation only. An entity page is
  registered only when its layout differs: `ListPageProps<E>` injects the list descriptor;
  `RecordPageProps<E>` injects the live form. `RecordPage`, `RecordFields` (`fields` picks and
  orders) and `RecordLines` compose the standard form with arbitrary React controls, preserving
  validation, save/post, picker cascades, dirty guards and live refresh; `EntityList`,
  `RecordFields` and `RecordLines` take page-only `overrides`. The same record page handles saved,
  new and duplicate records. `roles` and `when` gate pages and navigation; backend authorization
  still applies.
  `shell.navigation.actions` is the desktop rail outlet for custom React controls.

Navigation sections and nested groups accept React `actions` and recursive `items`; `roles`
filters branches and empty groups disappear. Desktop active routes reveal ancestor groups; the
mobile More menu uses the same tree, while bottom tabs derive from its leaf destinations.
Register `navigationComponent: AppNavigation` for a custom desktop/menu layout. Compose
`Navigation`, `NavigationSection`, `NavigationGroup`, `NavigationLink`, and `DefaultNavigation`;
`NavigationProps` supplies the resolved tree, active path, mobile flag and optional desktop
header/footer. Custom links share model/page gates and the shell dispatcher. See
[the UI package](../onno-ui/README.md) for examples.


- **Replacement SDK.** The default record host and custom hosts share `useRecordForm`; injected
  pages already receive its controller. `useRecordField`/`RecordField` expose typed editors with
  errors, write policy, metadata and the standard reference-picker context. Custom list renderers
  receive shared pagination and selection/select-all callbacks. Control prop types, metadata,
  `ListCell`, `RecordActions`, and picker blocks are public. See [UI_SDK.md](UI_SDK.md).
- **Typed values.** Everything an app hands the UI is a typed value checked against the generated
  model: entities by logical name, which records as a `where` object sent to the server as JSON,
  measures (`sum("Orders", "total", { posted: true })`), formats (`{ money: "USD" }`), inputs,
  icons (imported Lucide components from `@onno/ui/icons`, including command icons).
- **State.** Server data lives in TanStack Query's cache; each SSE change invalidates the entity's
  queries, so every list, form, chart and `useRecords` refreshes. The app's declaration flows down
  the React tree in context; there is no name → component registry.
- **Links.** `onno://documents/orders/<id>`, `onno://documents/orders/new?customer=<id>` and
  `onno://documents/orders?where=<json>` name what to open; the web shell and future mobile clients
  share them. A New form seeds from the Java class's field initializers, then from the link's
  prefill.
  `<RouteLink to>` and `openRoute(route, { newTab: true })` open app paths or `onno://` links in the
  workspace; a command's `navigateTo` goes through the same dispatcher.
- **Feature panels.** Record pages show `<RecordHistory />` for entities the model marks
  `history: true`; an entity's `list` customization (or `EntityList` props) takes `calendar`/`timeline` for date-ranged records (read from
  the list feed, drag saved as an ordinary record update); `<PeriodClose />` is the period-close
  admin screen. `LocalTime` fields edit with `TimePicker`, `Instant` fields with `InstantPicker`, and
  instants display in the zone `/api/config` reports (`onno.ui.time-zone`, else the browser's).
- **Behaviour stays in Java.** What can be done with records is a `Commands<E>` bean
  (`su.onno.command`) — exposed on REST, in the UI and as MCP tools, with roles, availability and
  inputs enforced by the server; the UI only decides placement and icon. Advisory live form checks are
  `Checks<E>` beans (`su.onno.validation`), picker decorations `PickerOptions<E>` beans
  (`su.onno.picker`), the login → catalog record link an `IdentityLink` bean (`su.onno.identity`).
- **The record form** is one editable surface (disabled for read-only viewers) with the record's
  actions in its header. **Open in list** (`?focus=<id>`, #410) opens the entity's list at the
  feed's focus window, scrolled to and highlighting the row.
- **Reference pickers** search the target server-side (every text column), lead with the user's
  recent picks, show an optional secondary line, cascade on other form fields (`filterBy`), apply the
  field's `PickerOptions`, and end with "Show all...", a dialog over the target's full list (a folder
  tree for a hierarchical catalog) under the same constraints (#411). A required reference is never
  clearable.

## Auth & RBAC

`onno-auth-starter` contributes the `SecurityFilterChain` and picks a mode from `onno.auth.mode`:

In every mode, framework bootstrap paths (including `/api/config` and `/api/auth/login`) remain
public. `onno.auth.additional-public-paths` adds public Ant patterns; legacy `onno.auth.public-paths`
entries are combined with them and can no longer remove mandatory framework paths.

- **`database`** (recommended) — accounts in the framework-managed table `onno_auth_user`, managed at
  runtime through `UserAccounts`, `/api/auth/users` (ADMIN) and `@onno/ui`'s `UserAccounts` /
  `ChangePassword` components. `onno.auth.users[*]` only seeds an empty store once, flagged for a forced password change.
  Each security-relevant change bumps the account's security stamp; `AccountSessionGuardFilter`
  re-reads the account per request, revokes stale sessions (`401 session_revoked`, remember-me cookies
  are stamp-bound too) and confines a pending one-time-password session to `/api/auth/**`
  (`403 password_change_required`). Otherwise identical to `in-memory` (session cookie, CSRF, JSON login).
- **`in-memory`** (default) — users from `onno.auth.users[*]`, session cookie + optional remember-me,
  JSON `POST /api/auth/login`, CSRF via `XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header (browser SPAs read
  the cookie directly; native clients that can't read it fetch the token from `GET /api/auth/csrf`).
- **`oidc`** — server-side OpenID Connect (Keycloak/Zitadel/custom); realm/client role mapping from
  the token via `onno.auth.oidc.*`; RP-initiated logout.
- **`resource-server`** — stateless JWT bearer validation, no session/CSRF.

In-memory users last as long as the configuration does; use it for development and demos.

Public, non-sensitive demos can opt into server-side password-mode auto-login with
`onno.auth.demo.auto-login-username`; normal RBAC still sees that user's roles. Iframe embedding is
deny-by-default and requires an explicit `onno.auth.embedding.frame-ancestors` CSP allowlist. A
cross-site session iframe additionally needs `onno.auth.embedding.cross-site-cookies=true` and HTTPS
`SameSite=None; Secure` servlet-session cookies.

`/api/**` requires authentication (except the public allowlist: `/error`, `/api/theme`,
`/api/config`, `/api/branding`, `/api/auth/login`, `/api/auth/login/{method}[/challenge]`, `/api/auth/me`, `/api/auth/csrf`,
`/api/ui/login`). **Per-entity RBAC is deny-by-default**: a
catalog/document/register is invisible
and uneditable unless its `@AccessControl` read/write roles grant the caller; the `ADMIN` role is a
superuser. Override the whole thing by setting `onno.auth.enabled=false` and supplying your own
`SecurityFilterChain`.

**Posting rights** are separate from write access. On a document, `@AccessControl(postRoles = …)`
and `unpostRoles = …` narrow who may post / re-post or unpost: the caller needs write access *and*
one of the listed roles (an empty list leaves the right with every writer; `ADMIN` always passes).
`DocumentCommandService` enforces them — so `POST /api/documents/{name}/{id}/post` and `/unpost`,
the MCP `post_document`/`unpost_document` tools and the built-in UI actions all answer `403` — and
`DELETE` of a *posted* document requires the unpost right because it unposts first. The record
surface hides the Post button, the Unpost action and (for a posted record) Delete from callers who
lack the right. `PostingService` itself is not gated: like typed repositories it is trusted
application code.

### Record-level access policies

`@AccessControl` decides which *entities* a role may use; a `RecordAccessPolicy` bean decides which
*records*. A policy names the roles it `appliesTo`, a read `RecordScope` (and optionally a write scope
and create `defaults`); `ADMIN` and `exemptRoles(...)` are exempt, several applicable policies are
OR'ed, and an entity without an applicable policy is unscoped.

```java
@Bean
RecordAccessPolicy tenantsByOwner() {
    return RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
            .read(RecordScope.eq("owner", Subject.recordId()))
            .defaults(d -> d.set("owner", Subject.recordId()));
}
```

- **Subject.** Every read and write runs for an `AccessSubject` — a `User` (login, roles,
  `IdentityLink` record id, `AccessSubjectContributor` attributes), resolved once per request
  by `AccessSubjectResolver`, or the trusted `AccessSubject.system()`. Controllers declare an
  `AccessSubject` parameter; the subject is captured explicitly into aggregate pools, batches, SSE
  subscriptions and MCP exchanges.
- **Compilation.** `RecordPolicies` (core) validates every policy at boot and resolves the scope for
  a subject; `RecordScopeCompiler` (service layer) turns it into a `ScopeClause` (SQL + bindings,
  three-valued logic, `via` as a correlated `EXISTS`) that the query services AND into every
  `WHERE` — lists, counts, groups, aggregates, search (whose ref `EXISTS` carries the
  target's scope), trees and `get` (404 outside the scope).
- **Writes.** `RecordWriteGuard` (shared by the command services) requires the write scope for
  existing records, checks the post-image inside the write transaction, fills defaults, rejects
  unreadable ref values (422) and preserves restricted refs echoed back as `null`.
- **Ref display.** `RefResolver` resolves per viewer: a ref into an entity the viewer can't read, or a
  record outside its scope, renders restricted (`{field}Restricted`, display `—`, id withheld).
- **Everywhere else.** Commands (write scope + server-side `availableWhen`/`enabledWhen`), batch, import, ref options,
  mentions, comments, tags, presence, notifications, task labels, SSE (`EventPublisher` checks
  each record event against each distinct subscriber scope), MCP tools and the CRM inbox.
- **Not scoped:** typed repositories, posting, process internals, jobs, migrations, connectors —
  trusted code. App code serving external users from repositories checks through `RecordAccess`.
- **Indexes.** `RecordScopeIndexes` (web starter) indexes every scoped column when
  `onno.schema.mode` resolves to `apply` (the default; blank counts as `apply`) and
  `onno.access.records.create-indexes` is on. On PostgreSQL it reconciles them on a background
  thread, off the startup path, as `SearchIndexReconciler` does for search indexes: missing ones
  are built with `CREATE INDEX CONCURRENTLY`, ones left invalid by an interrupted build are dropped
  and rebuilt, one node at a time under a `pg_try_advisory_lock`. Two concurrent builds on one table
  deadlock, so it takes turns with the search-index reconciliation under a shared advisory lock,
  each waiting for the other, without holding a connection, rather than skipping, and before it
  builds or repairs an index it waits while another session holds that table's
  `SHARE UPDATE EXCLUSIVE` lock (not for autovacuum, which the build cancels). A schema change to
  that table waits for a running build and blocks the table meanwhile. Other databases run the
  plain statements at startup (see
  [Record access policies](RECORD_ACCESS_POLICIES.md#startup-validation)).

`RecordPolicyConformanceTest` (leak test + route coverage guard) and `RecordScopeArchitectureTest`
keep new endpoints and data paths honest. See [Record access policies](RECORD_ACCESS_POLICIES.md).

## Record change history

`@Catalog(history = true)` / `@Document(history = true)` opts an entity into a field-level audit
log. The flag lives on the model annotation (not in the React UI) because it is a domain guarantee
that must hold on every write path, including ones with no UI. `su.onno.history.RecordHistory`
(core) owns the framework-managed, append-only `onno_record_history` table (created with
`CREATE TABLE IF NOT EXISTS`, outside the metadata diff, like `onno_schema_history`).

Recording is storage-level, so both write paths behave the same:

1. Before the mutation the writer captures a `RecordImage` — the stored row plus its tabular-section
   rows as canonical text (`RecordHistory.capture`).
2. After it, on the same transaction-bound JDBI handle, `RecordHistory.record` re-reads the row,
   diffs the images and inserts one row per changed value under a shared change id. A write that
   changed nothing records nothing.

| Path | Hook |
| --- | --- |
| Catalog/document saves — typed repositories (`save`, soft `delete`) and the generic command path (`CatalogCommandService`/`DocumentCommandService` save through `EntityStore`) | `RecordHistoryCallback` (`BeforeSaveCallback` + `AfterSaveCallback`), on the handle bound to the repository's Spring transaction, so history rolls back with the save |
| Post / unpost / repost (any caller of `PostingService`) | `PostingEngine` writes the `POSTED` / `UNPOSTED` entry right after it flips `_posted`, inside the posting transaction (a repost records `POSTED` again) |

Events are `CREATED`, `UPDATED`, `DELETED` (deletion mark set), `RESTORED` (cleared), `POSTED`,
`UNPOSTED`. Header fields use logical names (`code`, `description`, `parent`, `folder`, `number`,
`date`, attribute field names); tabular-section rows are compared by line position and reported as
`ADDED` / `REMOVED` / `CHANGED`. Values are stored as text (reference and enumeration ids, ISO
dates, plain decimals). `secret` attributes are compared in memory after decryption and stored as
"changed" with no values, so the table never holds a secret.

The actor is the thread's `HistoryActors` binding (the command services bind the caller's
`AccessSubject` for the whole command), else the `HistoryActorResolver` bean (the UI starter resolves
the request's authenticated user and identity record), else `system`.

`GET /api/history/{kind}/{name}/{id}` (web-starter, `RecordHistoryController`) serves the entries
under the record's own read rules: entity read grant (403), history opted in (404), record inside
the caller's `RecordAccessPolicy` read scope (404). Reference and enumeration values are resolved
through `RefResolver` for the caller, so a reference they may not read is masked. The UI model marks
opted-in entities `history: true`, and the default record page then shows `@onno/ui`'s
`<RecordHistory />` panel (an authored `<RecordPage history={false}>` hides it). `onno.history.enabled=false` swaps in an
inert recorder: no table, no panel, endpoint 404.

Not covered yet: retention/purge, restoring an earlier version, a user-action journal (logins,
reads), an MCP read tool, and history for registers and constants. Hand-written SQL against an
entity table bypasses the recorder.

## Integrations

- **MCP** (`onno-mcp-starter`) — a streamable-HTTP MCP server at `/mcp` (HTTP Basic, same users as
  the web UI), exposing tools generated from the registry and gated by RBAC: `describe_metadata`,
  `list_catalog`/`get_catalog`, `list_documents`/`get_document`, `register_balance`/`register_movements`,
  `create_*`/`update_*`/`delete_*` (gated by `onno.mcp.writes-enabled`), `posting_preview`,
  `post_document`/`unpost_document` (gated by `onno.mcp.posting-enabled`), and one `<entity>_<key>`
  tool per static `Commands` command, run through the same `CommandService` as REST. Applications add
  authenticated, role-scoped tools with `@McpTool` methods; `McpToolProvider` is the lower-level
  SDK extension point. Custom tools compose with generated tools and duplicate names fail startup.
  Catalog/document lists are keyset-paged. `register_movements` returns a list window through the
  shared `RegisterQueryService.movementsPage`: typed keyset windows (`cursor`, `limit` default 100
  and maximum 500, `sort`, and `where`, the structured JSON predicate, see below), returned as
  `{rows,nextCursor,hasMore}`. `register_balance` uses `balancePage` with dimension tuple keys. Only
  cursor-based continuation is supported; numeric offset arguments, `filters` and legacy array
  aliases are removed. The exact contract is [Collection pagination](HEADLESS_READ_API.md#collection-pagination).
  Historical register balances: `register_balance` accepts optional `asOf`: an inclusive
  ISO-8601 local date-time (`2024-03-31T18:00`) or a date (`2024-03-31`, the whole day); offsets are rejected with `400`. REST: `GET /api/registers/{name}/balance?asOf=2024-03-31T23:59:59`.
  With `asOf`, BALANCE registers sum active movements through the cutoff (receipts minus expenses),
  grouped by dimensions; record access policies apply before aggregation and `where` selects the
  resulting balance rows. Without `asOf`, reads keep the fast materialized totals path.
- **Import** (`onno-import-starter`) — CSV preview + import for catalogs/documents through the same
  command services as the REST API (so validation, numbering, posting, events all apply); modes
  `CREATE_ONLY` / `UPSERT_BY_CODE` (catalogs) / `UPSERT_BY_NUMBER` (documents), dotted mapping keys
  for tabular sections, optional `groupBy` + `postAfterImport` for documents.
- **Print** (`onno-print-starter`) — `PrintForm<T>` beans (one per catalog/document form) bind typed
  values into a `.docx`/`.xlsx` template's `{{key}}` placeholders; a row with `{{table.column}}`
  repeats per item (Excel ranges ending on it stretch, formulas recalculate). Each form the viewer
  may use appears in the record ⋯ menu through the web starter's `RecordActionContributor` SPI and
  downloads from `GET /api/print/{kind}/{name}/{id}/{form}`; `GET /api/print/{kind}/{name}/{id}`
  lists the available forms. Both enforce entity read access, record scope (out of scope = 404) and
  the form's optional roles. A list's selection toolbar batch-prints through the web starter's
  `SelectionDownloadContributor` SPI (`POST /api/print/batch/{kind}/{name}/{form}` → zip; out-of-scope
  ids skipped). Admins (`onno.print.templates.manage-roles`) replace templates at runtime via
  `/api/print/templates/**` and `@onno/print`'s `<PrintTemplates />` component; uploads live in the
  framework-managed `onno_print_templates` table (keyed by entity scope + form key, so every node sees
  them) and win over the built-in file until reset. PDF output (`?format=pdf`, `spec.pdf()`) goes
  through one `PdfConverter` bean — `GotenbergPdfConverter` or `LibreOfficePdfConverter` from
  `onno.print.pdf.*`, or the application's own.

## Events & outbox

The starter's repository entity-change publisher defers events until `TransactionSynchronization.afterCommit`
when a Spring transaction is active. Rollback discards these events; without a transaction they are
published immediately. Event data is captured when the write completes.

Posting notifications are published by `PostingEngine` after its own JDBI
transaction commits. Successful `post` and `repost` operations publish a document
`EntityChangedEvent.POSTED`; successful `unpost` operations publish
`EntityChangedEvent.UNPOSTED`. Each also publishes a wildcard register
`EntityChangedEvent(CHANGED, REGISTER, "*", documentId, null)` so open register views refresh.
This applies to direct Java calls and UI, REST,
and MCP entry points. Rolled-back operations publish neither notification.
`DocumentCommandService` does not publish duplicate posting notifications.

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
The framework ships no broker transport: a relay of your own (for example a scheduled job publishing to your broker) reads those rows when you want
cross-service streaming.

The `entityType` vocabulary is open: the modelled kinds are `catalog`, `document`, and `register`,
but other modules emit their own. Comment-thread posts and deletes (`onno-web-starter`; not a modelled
entity) publish the same event with `entityType = comment`, scoped to the commented record's
`(entityName, id)` — so the comments panel live-syncs across viewers, and across nodes via the same
cluster relay, without each panel opening its own stream. Listeners filter on `entityType`, so the
list/detail/dashboard surfaces (which react only to the modelled kinds) ignore it.

Local SSE entity notifications are dispatched after the publishing Spring transaction commits;
rolled-back writes produce no notification. Publishers outside a transaction still notify immediately.
This prevents a browser from refetching a newly created conversation before it exists in committed data.

On a Postgres datasource the framework starter's cluster bus (see below) makes `EntityChangedEvent`s
reach browsers on **every** node of a scaled-out deployment; on H2 the fan-out stays in-JVM.

On the browser side, all tabs of an origin share a **single** `/api/events` connection: one tab is
elected leader (via the Web Locks API) and holds the stream, rebroadcasting each event to the other
tabs over a `BroadcastChannel`; if the leader tab closes, another transparently takes over. This
keeps a handful of open tabs from exhausting the browser's per-origin connection limit (~6 over
HTTP/1.1, shared across all tabs), which would otherwise starve both the extra streams and ordinary
API calls. Browsers without Web Locks/`BroadcastChannel` fall back to one stream per tab.
`@onno/ui` turns each entity event into a query-cache invalidation, so its data hooks
(`useRecords`, `useAggregate`, …) and every list and form refresh on their own; app components use
`useUiEvents` for other events (comments, presence, tasks). A component must not open its own
`EventSource`, which would bypass cross-tab sharing, reconnect/session handling, and listener cleanup.

## Scaling out (horizontal)

Running more than one instance behind a load balancer needs these, beyond a shared database:

- **Live-UI events across nodes** — automatic on Postgres (`onno-framework-starter`, `onno.cluster.*`). It relays each `EntityChangedEvent`
  over a pluggable `ClusterEventBus` (`su.onno.cluster`, an SPI swappable like `MediaStorage`);
  the default uses Postgres `LISTEN`/`NOTIFY` (no extra infrastructure) and is a no-op on H2. A
  received event is pushed straight to the local SSE stream and is **never** re-published as a Spring
  event, so business `@EventListener`s (cache, search, post-hooks) still run exactly
  once, on the node that made the change. `DocumentPostedEvent`/`DocumentUnpostedEvent` are node-local
  by design; their cross-node *visibility* rides on the `posted`/`unposted` `EntityChangedEvent`. To
  swap in Kafka/Redis, expose your own `ClusterEventBus` bean (`@ConditionalOnMissingBean`).
  `ClusterEvent` is a sealed family discriminated by `kind`: `EntityChanged`, `Presence`,
  `Notification`, and `ProcessTasksChanged` share the one channel. Task invalidations retain their
  user/role audience across nodes, so a process advanced on one node refreshes only eligible inboxes
  connected elsewhere.
- **Presence markers across nodes** — record-level presence ("who else is viewing this") is held in an
  in-memory per-node registry (`PresenceRegistry`, web-starter) and relayed as a `Presence` `ClusterEvent`
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
commercial connectors (Maven group `su.onno.enterprise`: Tochka, YooKassa, Ozon, Wildberries, …) live in
the private [onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo under the onno
Commercial License and consume the core as published Maven artifacts. An enterprise connector is a
Spring Boot auto-configuration starter that wraps an external API; the framework metadata, posting,
and UI live in the *consuming application*, not in the connector. The boundary and extraction plan
are in [docs/licensing/MODULE-SPLIT-PLAN.md](licensing/MODULE-SPLIT-PLAN.md).

## Community extensions

The same starter mechanism is open to anyone — community extensions are first-class, not a fork.
There are six extension surfaces: **connectors** (auto-config starters wrapping an external
system), **SPI implementations** (`MediaStorage`, `MailDispatcher`, an additive
`AuthMethodsContributor` login button, custom `SecurityFilterChain`/`UserDetailsService`), **UI** (npm packages of React components on `@onno/ui`, `ExtensionSlot` contributions, and
`Commands` beans),
**MCP**
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
HTTP work after commit. `GET /api/crm/conversations/{id}/delivery` returns
connected/label/maxTextLength plus `maxAttachments` and `attachmentReason`;
`POST /api/crm/conversations/{id}/messages/{messageId}/retry` requeues a failed reply after checking
conversation membership and host customer permissions. See [the read/API contract](HEADLESS_READ_API.md).

An email reply may also be formatted: `html` beside `body` is sanitized to formatting tags
(`ReplyHtml`), kept as the message's `bodyHtml`, and sent by Gmail as the HTML part, `body` as the plain one.

Outbound replies may carry files. `POST /api/crm/conversations/{id}/messages` accepts
`{body,attachments}`, where `attachments` are media URLs previously returned by `POST /api/media`
([media uploads](MEDIA_UPLOADS.md)) — references, never bytes, and only ones this application issued,
so no caller can aim a delivery worker at an arbitrary URL. They are stored newline-joined on
`ConversationMessage.attachments`, at most ten per message and no more than the channel's own
`maxAttachments`; a reply may be files alone. Message reads return
`attachments:[{url,filename,contentType,size,image}]`. Telegram, Gmail and WhatsApp upload the bytes
to the provider; Instagram declares zero, because it fetches attachments from a public URL. With
`onno.media.enabled=false` the capability is reported as unavailable rather than failing on send.
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

The host composes pages and navigation explicitly, rendering `@onno/crm`'s `CrmInbox`, `CrmConnections`
and optional `CrmContactDetails` components. Channel management uses `CrmChannelAccess` (ADMIN by default).
Only enabled connectors appear. Provider imports invoke the host's optional inbound contact policy;
they never instantiate a built-in customer. Telegram avatars belong to channel identities.

The host owns business merging and any auditing or undo. `CrmContactService.transferLinks` joins a
host transaction and moves CRM conversations/identities plus a canonical redirect, preserving
provider routing. It has no HTTP endpoint or automatic undo and never changes host business fields,
comments or opportunities. The binding identity, redirects and transaction guard use `onno_crm_*`
infrastructure tables. Personal-group storage is created only with `CrmFeatures(true)`. Personal provider accounts are opt-in via `CrmFeatures.withPersonalAccounts(true)`; enabling them closes the generic CRM catalogs to ADMIN because generic surfaces cannot filter personal inboxes. Sales models and metrics remain host-owned.

The list customization `selectionCheckboxes: true` opts a catalog/document table into Onno
row-selection controls (default false). Flat and grouped grids share the existing
selection state and batch command path. The header checkbox and ⌘A/Ctrl+A select all matching
records through the scoped, cursor-paginated feed, including unloaded rows and collapsed groups.
The header has a mixed state for partial selections. Failed requests preserve the previous selection,
and clearing or changing the query cancels pending selection. A visible selection-actions trigger reuses the row menu with explicit batch mode, including
single-row selections, dynamic action discovery, form collection and server authorization. Query,
sort, grouping or view changes clear selection. Custom renderers and register rows are unaffected.

CRM conversation folders are optional `CrmWorkspaceService.Folder` definitions supplied through
`CrmWorkspaceCustomizer` / `Config.withFolders`. Their typed channel/status/priority criteria and
unread predicate or explicit conversation IDs group the current authorized list. First-match
precedence prevents duplicates. The inbox mixes folders with ungrouped chats, rendering counts,
sliding drill-in lists and a back control (with reduced-motion and keyboard focus support). No records are moved or copied;
folder definitions do not alter access control or persist new business entities.

App components report outcomes with `@onno/ui`'s `toast.success(message)` (or `error`, `info`,
`warning`), the shell's one notification stack.

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
Replies may be sent from another mailbox (`Connection.senders`/`sender`, request `from`, stored as
`ConversationMessage.senderAccount`); such a reply starts a thread in that mailbox, routed to the same
conversation. `CrmMessageTransport.connection(conversation, principal)` gives the per-user view and
`markedRead(conversation)` lets Gmail clear its `UNREAD` label after commit (grants with
`gmail.modify` only). `GET /api/crm/gmail/accounts`, `GET /api/crm/gmail/search` and
`POST /api/crm/gmail/restore` find mail in a mailbox, archived included, and import its thread through
the sync's own ingest. The worker parses Gmail answers with a private high-limit mapper, retries an
unreadable message three times before skipping it (`onno_crm_gmail_failed`), and pauses only the
affected mailbox on 429/rate-limit 403 until Retry-After or an exponential backoff.

`history-import` brings each mailbox's older mail in once, after the first import, a page of
threads per round. It reads `format=full` (text plus file references) and stores emails through the
same `save` as live mail in `HISTORY` mode: read, new conversations closed, inside
`CrmHistoryImport`, a thread-bound mark that `CrmWorkspaceEvents` and application listeners check to
stay quiet. Files are recorded in `onno_crm_gmail_file` as media keys `gmail/<id>/<name>`;
`GmailMediaFiles` wraps the application's `MediaStorage` so `GET /api/media/...` fetches such a key
from Gmail by message part when opened. A new thread joins the sender's conversation through
`findByCustomerAndDeletionMarkFalse` over the contact and its merge redirects, never a scan of the
mailbox's conversations.

Inbox workspaces are application-authored `CrmInboxWorkspace` beans, separate from the persisted
`Inbox` channel-account catalog. Each supplies stable identity, read/write roles, a typed
`Predicate<Conversation>` and a per-workspace presentation customizer. `CrmInboxWorkspaceService`
rechecks membership and access for scoped feeds, messages, delivery, notes and mutations. It selects
views, never changes provider routing. The bookstore crm profile declares Support. The inbox uses
scoped reads and content-free page SSE invalidations; shared contacts keep their catalog policy.

`EntityAccessPolicy` is a deny-only extension on `AccessService` for generic entity surfaces;
policies receive roles, kind, normalized logical name and read/write intent. Existing annotation
RBAC still applies and ADMIN remains the superuser. The CRM registers a gate that blocks generic
conversation/message/identity surfaces for non-admin users, closing REST/UI/MCP/comment/event
alternatives to scoped endpoints. Comment deletion now checks current target read access as well as
authorship. Generic services and repositories remain trusted application APIs, not row-security
boundaries. Scoped folder membership affects display only; workspace predicates and roles authorize.

A page binds `<CrmInbox workspace="support" />` to one workspace.
The scoped feed accepts `status` UUIDs and `channel`/`priority` enum names (`all` by default);
filters apply after workspace membership and before pagination.

The local Gmail adapter groups email threads by canonical customer within its own mailbox.
Startup consolidates earlier thread-per-chat imports transactionally: messages and comments move,
thread routes remain intact, superseded conversations are soft-deleted, and source/target IDs are
recorded in `onno_crm_gmail_grouping`. Replies retain the thread selected when queued.

Inbox filters accept comma-separated status UUIDs or channel/priority enum names (OR within a field,
AND between fields); omitted values default to `all`. The inbox reuses `@onno/ui`'s `OptionsFacet`
multi-select chips.

The scoped inbox renders the standard `EntityListView` header and body-view controls. Its feed
accepts repeated `in=field,value`, `sort`, `dir` (without `sort` it orders by `lastMessageAt`
descending; a `sort` without `dir` is ascending), `limit` (default 100, maximum 500), and an opaque
`cursor`. Responses contain `{rows,nextCursor,hasMore}` plus workspace/list metadata; `nextCursor`
is null at the end. `total` is included only with `count=exact`. Numeric `offset` pagination is no longer supported.

Host workspace selectors and facet resolvers are Java callbacks. `CrmFeedQuery` scans source
conversations in batches of at most 500, applies workspace/customer/personal-account access and
filters, and projects eligible keys plus typed sort values into a request-local temporary SQL table.
The shared `su.onno.query.KeysetWindow` sorts and seeks that projection; `su.onno.query.Cursor`
issues the context-bound token. Temporary tables are dropped in finally. This bounds Java memory
and removes full-list slicing, but candidate evaluation still scans the source on each request;
it is not a constant-time indexed workspace query. Facet resolvers must be batch-independent.
Cursors are bound to the workspace, principal/roles, effective filters and sort. A valid foreign
cursor is 400; a malformed token restarts. NULL values sort last, with UUID as unique tiebreaker.


The channels starter also provides an opt-in Instagram Login adapter (`onno.crm.channels.instagram.enabled`).
It uses `Channel.INSTAGRAM`, verifies its account from a private token file, polls provider-visible
messages into Support, and routes text replies through a durable outbox. Account/peer identity and
provider-message deduplication are owned by `onno_crm_ig_*` adapter tables. Only messages sent at or
after the account's persisted UTC activation cutoff (`onno_crm_ig_sync`, seeded once from
`onno.crm.channels.instagram.activation-cutoff` or the moment sync is first enabled, never reset) are
imported, filtered per message. Polling is incremental and head-first: it stops at the checkpoint
minus an overlap, downloads only conversations whose `updated_time` moved (`onno_crm_ig_thread`),
bounds pages and downloads per poll, and resumes a backlog from a saved cursor after rechecking the
newest conversations. Watermarks, cursors and the checkpoint advance only after the messages they
cover commit; rate limits back off. Uncertain sends require explicit retry and replies outside the
24-hour window are rejected. It does not configure public webhooks or waive
Meta test-mode restrictions. Tokens and provider response bodies are never returned by CRM APIs.

### CRM channel distribution

The optional published module `onno-crm-channels-starter` now owns the Telegram, Gmail, Instagram and WhatsApp adapters previously under the example. It is auto-configured before the CRM fallback transport and contributes typed `onno.crm.channels.*` configuration metadata. The example owns only sample data, auth and workspace selection. WhatsApp adds signed GET/POST `/api/crm/whatsapp/webhook`, account/number-scoped ingestion, transactionally queued text replies, and delivery/read status handling (`DeliveryStatus.READ`). Private credentials never enter UI responses. Public access/CSRF exemptions remain explicit host configuration; only the webhook route is exempted.

Selection toolbar extensions: an entity's `list.selection` component (React) is mounted when rows are
selected and the viewer has write access. It receives `ids` and `complete()` (clear selection and
reload after success). Commands must enforce their own server-side authorization.

App components can import `DatePicker` (date only) and `DateTimePicker` (local date and
24-hour time) from `@onno/ui`. They share the host calendar and emit ISO strings
at day and minute precision, respectively. Generated `LocalDateTime` fields use
`DateTimePicker`.

CRM message bodies can be drawn by the app: `<CrmInbox messageRenderers={[…]} />` takes
`ChatMessageRenderer`s with predicate/priority selection and a plain-text fallback on error.

The example app owns reply templates as a `CrmReplyTemplates` catalog (name, category, plain-text body,
active flag). CRM managers maintain them in Configuration → Reply templates; agents can read
and insert active templates from the chat composer. The searchable picker previews the text,
appends it to an existing draft, and rejects insertion beyond the channel text limit without
truncation. Agents review/edit the draft and send explicitly. These are saved replies, not
provider-approved WhatsApp message templates. Inactive or deleted templates are excluded.

### Unified contact activity

The CRM Inbox presents one row per contact and a chronological timeline across its channel conversations, internal comments and system events. Underlying conversations retain provider routing and email thread identifiers. The reply selector changes only the destination, and defaults to the latest incoming message's conversation; an unsent draft keeps its selected destination.

`GET /api/crm/contacts/{customer}/activity?cursor=&limit=100` returns `{rows,nextCursor,hasMore}` (newest first, default 100, maximum 500). Only the `rows` key and cursor continuation are supported. No total is calculated. Messages and internal notes are combined by SQL UNION ALL, restricted to authorized conversation keys before seeking on `(time DESC, source DESC, UUID DESC)`. Files, HTML, author photos and account labels are resolved only for the selected page. Each entry includes `id`, `conversationId`, `subject`, `channel`, `kind`, `direction`, `authorName`, `body`, `at`, and `deliveryStatus`; message entries also retain `senderAccount`, the account used to send the reply, or null. Only readable conversations are included, with canonical contact resolution after merging. The UI loads older pages on demand and updates through existing CRM SSE invalidations.

`POST /api/crm/contacts/{customer}/activity` accepts `conversationId`, `type` (`QUOTED`, `CALL_PLANNED`, `CALL_COMPLETED`, `MEETING_PLANNED`, `OTHER`), nonblank `details` up to 7000 characters, and optional local `scheduledFor`. It requires write access to the matching contact's conversation. It stores an internal `SYSTEM_EVENT` without sending a channel message or changing the sales stage. Planned dates are descriptive event text, not reminders or calendar invitations. Domain workflows can also append system events through the existing conversation-message repository.

### Record tag API

`GET /api/tags/{kind}/{name}` lists selectable tag definitions. Definitions are managed through
the owning tag catalog’s ordinary CRUD API; `POST /api/tags/{kind}/{name}` (`{name,color}`, write access
to the tagged entity) creates one inline from the tag picker, reusing an existing case-insensitive name. `GET /api/tags/{kind}/{name}/{id}` lists a record's tags.
`POST /api/tags/{kind}/{name}/{id}/{tagId}` adds an assignment; `DELETE` removes it.
Kinds are `catalogs` and `documents`; names normalize to the owning entity. Responses contain
`{id,name,color}`. Definitions have stable UUIDs; assignment mutations are idempotent and reject
tags from another entity library. All reads require entity read access; mutations require write
access, existing live records, and applicable `TagAccessPolicy` checks. Invalid input returns 400;
missing/deleted records return 404; denied access returns 403. Tag assignment changes emit an
`updated` SSE event with entity type `tag` and the record ID.

Framework-owned tables `onno_tags`, `onno_tag_links`, and `onno_tag_imports` are created additively.
CRM installs no tag catalog or tag importer. Applications may use the web starter's standard
record-tag capability on their own catalogs.

Internal CRM notes keep the inbox composer and use a compact inline reference picker: `@` selects people, `#` selects catalog/document records, and pasted local record links become references. The shared `CommentService` checks mention permissions, enforces `onno.comments.max-length` (4000 characters by default), and publishes comment events. The CRM endpoint also caps the request body at 8000 characters. The host `CommentBody` renderer accepts a stored `body` and resolves links for the current viewer. CRM reply drafts and internal-note drafts stay separate.

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

`ExtensionSlot` outlets render the app's `defineApp({ extensions })` contributions: list/form
controls, entity and CRM context menus, composer tools, and right-panel sections. Contributions
receive scoped context and supported host callbacks, have deterministic ordering and isolated
render failures. Backend authorization continues to govern commands. See the UI contributions
section of [EXTENDING.md](EXTENDING.md) for outlet names, context, and examples.

### Opening a workspace conversation

In workspace mode, the app renders `@onno/crm`'s `CrmConversationPage` at
`/catalogs/crm_conversations/:id` (a page with `view: false`). The server verifies host customer
permissions and workspace membership before the chat loads, using an accessible inbox workspace. Its feed is
`GET /api/crm/inbox-workspaces/{key}/conversation/{id}`; it returns only that contact's conversations
within the authorized workspace and retains normal filtering/pagination. Foreign records return
403 and missing records return 404. Generic catalog/message read and export restrictions remain
unchanged. Without workspace definitions, the normal default catalog form remains in use.

Applications own their status, Assign, Details, Close/Reopen, and Log activity controls
through application-authored extension contributions. The contact panel has no built-in edit, merge, or identity-linking forms.
The CRM starter supplies slots and authorized commands, without registering these buttons.
Application code may omit, replace, reorder, or add its own contributions.

The example application owns Templates as a `crm.chat.composer` contribution
(`example.crm.composer.templates`), including its catalog and picker. The CRM
starter supplies the empty composer slot and controls draft insertion and limits.

Conversation statuses are optionally application-authored through `CrmStateConfiguration`.
Customer stages remain ordinary host catalog fields. No stage projection or contact editor is
installed. The host also owns sales models, templates and their action contributions.

Components can anchor a shared `PopoverContent` to an existing field using `PopoverAnchor` with `asChild`. Use this for input-driven suggestion popovers without adding a separate trigger button; preserve input focus via the popover autofocus callbacks. CRM controls use `@onno/ui`'s buttons, labels, inputs, selects, and popovers.

Inbox toolbar filters are the `filters` of `<CrmInbox list={…} />`, with the standard
options/text/date controls; workspace beans add server-resolved facets (`CrmInboxWorkspace.facet`).
Scoped feeds accept `eq/in/like/prefix/ge/le`; membership and contact access are checked before
filtering. No toolbar filters are supplied implicitly.

CRM channels use extensible string keys with connector-owned `CrmChannelDefinition` metadata
(`GET /api/crm/channels/types`); no fixed channel enumeration or Channels page is installed.
`<CrmConnections setups={[channelSetups]} />` takes provider setup forms as components; bundled provider
setup and branding live in the channels starter (`@onno/crm-channels`). The inbox's columns, sort and
filters are `<CrmInbox list={…} />` props. Status bindings read an existing host
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
Host `Commands<Conversation>` beans declare ordinary record commands; extension buttons receive
their descriptors and call `context.execute(key, inputs)`. The scoped API is
`/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions` (GET descriptors, POST `/{key}`
with `{inputs:{...}}`). It checks workspace write access, application read-only mode, command roles
and the commands' `availableWhen`/`enabledWhen` rules. CRM adds no business-field mutations or automatic history
messages for these actions. Hosts own the handler and any desired history records. Connector code
can still use the low-level identity-link service for provider routing.


CRM's packaged inbox composes a channel/account bar with the ordinary entity list through the
list's scoped query parameters. Constraints use the existing validated filter protocol; account
choices are derived only from readable workspace conversations. Manual call/meeting summaries use
existing CRM internal events, while folder taxonomy and classification remain host-owned.
See [unified inbox controls](../onno-crm-starter/README.md#unified-inbox-controls-and-manual-activity).

### Mobile navigation presentation

On phones the shell shows the app's navigation as a bottom bar: the first four destinations appear
beside More, where the full navigation remains available. Bottom-bar labels use one line; screen
readers receive the full destination label.

On bottom-bar shells, declared navigation destinations are top-level screens. Opening another route shows a Back header and hides the bottom bar. Parent surfaces stay mounted until returning or switching top-level destinations, retaining their filters and scroll state. Direct entity links fall back to their catalog/document list. Back asks before discarding a dirty form. Components with in-page child states can call `@onno/ui`'s `useMobileDetail(active)` when they provide their own Back control; the CRM inbox uses this for an open compact chat. Requests are scoped to the mounted route and released on cleanup.

### CRM scoped connection accounts

`GET /api/crm/connection-accounts` lists installed provider capabilities and the caller's visible
shared/personal accounts; `POST` creates an account from `{provider,scope,label}`.
`POST /api/crm/connection-accounts/{id}` handles `{action,label?}` (rename, removeDraft, provider
commands). Ownership comes from the authenticated principal, never the request. Shared management
uses `CrmChannelAccess`; personal management requires ownership, personal policy, and inbox write
access. Other users' personal accounts return 404. Gmail OAuth accepts optional `accountId` and
rechecks ownership on its one-use callback. Each mailbox has separate credentials and inbox routing.

Personal inbox ownership restricts scoped conversation reads/writes and contact projections.
Generic CRM catalogs/exports/events are unavailable to all roles once personal accounts exist,
preventing ADMIN from bypassing per-account privacy. Host contacts retain host catalog permissions.
`EntityAccessPolicy.includesAdministrators()` defaults false; privacy policies explicitly opt in.
See `onno-crm-starter/README.md` and `onno-crm-channels-starter/README.md` for response fields,
component props, lifecycle behavior and remaining shared-only provider limits.

### CRM screens

The CRM's screens are React components of `@onno/crm` with typed props: `<CrmInbox workspace="support"
list={…} toolbar={…} layouts={…} contactFields={…} folders={…} />`, `<CrmConnections setups={…} />`,
`CrmContactDetails`, `CrmConversationPage`. Workspace beans still own the authorized feed, actions,
folders and facets; the server never names a component.

Telegram business onboarding uses `POST /api/crm/telegram/business/{account UUID}/authorize`
(account ownership, workspace write access and CSRF required). The returned one-use Telegram link
proves the provider user before attaching a business connection. `telegram-business` managed accounts
support PERSONAL/SHARED independently of sender mode. Routing, deduplication and delivery are isolated
from the ordinary shared bot inbox, with reply permissions checked before sends. See the channels
starter README for lifecycle, skipped paused updates, attachment and history limits.

CRM attachment ingestion: Gmail MIME files and Telegram ordinary/business files are stored through
CrmAttachments and MediaStorage, then rendered with local previews/downloads. Incoming files are
bounded to 20 MB. ConversationMessage Body is optional; its business rule requires text or files.
Filesystem media keeps private filename/content-type sidecars across restarts.

CRM audio playback lives in the inbox component; common audio formats are served inline by `MediaController`. Audio files use the existing media upload and message attachment flow. Channel delivery remains attachment-based.
