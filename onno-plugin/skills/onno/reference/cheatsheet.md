# onno API cheat sheet

Dense lookup for the framework's public surface. Verify against `onno-framework/src/main/java/su/onno`
if something looks off — the code wins, and you should fix this file if it has drifted.

## Annotations (package `su.onno.annotations`)

### `@Catalog` (on a class extending `CatalogObject`)
| Element | Type | Default | Notes |
| --- | --- | --- | --- |
| `name` | String | — (required) | Stable logical key used by routes/schema; ASCII and URL-safe. |
| `title` | String | `""` | UI label; falls back to `name`. |
| `tableName` | String | `""` | Stable DB table; derived from `name` if empty. |
| `previousNames` | String[] | `{}` | Former name/tableName for rename detection. |
| `codeLength` | int | `9` | Max code length. |
| `hierarchical` | boolean | `false` | Enables parent/child folders. |
| `autoNumber` | boolean | `true` | Auto-generate codes. |
| `codePrefix` | String | `""` | Fixed code prefix. |
| `context` | String | `""` | Bounded-context grouping / future service split point. |
| `history` | boolean | `false` | Record a field-level change history (who/when/old → new) in `onno_record_history` on every write path; shows the History panel and serves `GET /api/history/catalogs/{name}/{id}`. |

### `@Document` (on a class extending `DocumentObject`)
`name` (required), `title=""`, `tableName=""`, `previousNames={}` (covers doc + tabular section
table renames), `numberLength=11`, `autoNumber=true`, `numberPrefix=""`, `context=""`, `history=false` (field-level
change history incl. tabular rows and post/unpost events; attribute typed saves from trusted code
with `HistoryActors.runAs(HistoryActor.named("…"), …)`; global switch `onno.history.enabled`).

### `@TabularSection` (on a `List<Row>` field of a catalog or document; `Row extends TabularSectionRow`)
`name=""` (child table name; derived from the field name if empty). Each section must use a
distinct concrete row class because Spring Data maps the class to one child table; startup rejects
reuse across documents/sections. Share implementation through a base row class.

### `@Attribute` (on catalog/document scalar fields)
| Element | Type | Default | Notes |
| --- | --- | --- | --- |
| `name` | String | `""` | Column name; derived from field. |
| `displayName` | String | `""` | UI label. |
| `previousNames` | String[] | `{}` | Former field/column names for rename. |
| `length` | int | `255` | Max string length. |
| `required` | boolean | `false` | NOT NULL. |
| `precision` / `scale` | int | `15` / `2` | Decimal precision/scale. |
| `min` / `max` | double | `NaN` | Numeric bounds. |
| `minLength` | int | `0` | String min length. |
| `pattern` | String | `""` | Regex validation. |
| `email` | boolean | `false` | Email-format validator. |
| `secret` | boolean | `false` | Encrypted at rest (needs `onno.security.secret-key`), masked as `__SECRET_SET__` on read. |
| `searchIndex` | boolean | `false` | PostgreSQL trigram index for substring search on this String attribute. Opt in only for fields users search by on large tables. |

**Supported attribute field types:** `String`, `int`/`Integer`, `long`/`Long`, `boolean`/`Boolean`,
`double`/`Double`, `float`/`Float`, `BigDecimal`, `UUID`, `LocalDate`, `LocalDateTime`, `LocalTime`,
`Instant`, Java `enum`s (`@Enumeration`), and `Ref<T>`. There is no `OffsetDateTime` / `ZonedDateTime`
— schema generation throws `IllegalArgumentException` at startup for any other type.

**Picking a temporal type:** `LocalDate` = a calendar day; `LocalDateTime` = a zone-less wall time
(document dates, "the 10:00 slot" in one site); `LocalTime` = a time of day (opening hours, shift
start/end) → `TIME`; `Instant` = an absolute moment (appointment start, "sent at") → stored in UTC
(`TIMESTAMP WITH TIME ZONE`), sent as ISO with `Z`/offset, shown in `onno.ui.time-zone` or the
viewer's browser zone.

**Naming rule — entity/attribute `name` must be ASCII / URL-safe.** It becomes the REST route segment
(`/api/catalogs/{name}`), the derived table name, and the access-check key. Derived table/column
identifiers and rename aliases are validated against `[A-Za-z_][A-Za-z0-9_]*` at scan time, so
unsafe punctuation fails startup before reaching generated SQL. Keep logical route names ASCII and
put the localized label in `title`; route compatibility still depends on that stable naming rule.

### `@AccumulationRegister` (on a class extending `AccumulationRecord`)
`name` (required), `title=""`, `tableName=""`, `type = AccumulationType.BALANCE|TURNOVER` (default
`BALANCE`), `allowNegative=false`, `postingOrder=PostingOrder.INDEPENDENT`, `context=""`. Fields are `@Dimension` (grouping keys) and
`@Resource` (numbers). Posting rejects a negative resource total in a `BALANCE` register by default;
set `allowNegative=true` only for a balance whose domain permits debt or overdrafts. The policy is
ignored for `TURNOVER` registers. `postingOrder=CHRONOLOGICAL` reverses and reposts later affected
documents after a backdated post/repost/unpost; use it for order-dependent calculations such as average cost.

### `@InformationRegister` (on a class extending `InformationRecord`)
`name` (required), `title=""` (display label; falls back to `name`), `tableName=""`,
`periodicity = Periodicity.NONE|SECOND|MINUTE|HOUR|DAY|MONTH|QUARTER|YEAR` (default `NONE`),
`context=""`. Fields are `@Dimension`, `@Resource`, and plain `@Attribute`. Rows are keyed on
`(_period, dimensions…)` after the period is floored to the bucket, so a second write in the same
bucket with the same dimensions **replaces** the first — pick a periodicity at least as fine as the
rate the fact actually changes. `SECOND`/`MINUTE`/`HOUR` exist for event logs, audit trails, and
status histories; `NONE` drops `_period` entirely and keeps one current row per dimension tuple.

### `@Dimension` / `@Resource` (register fields)
`@Dimension`: `name=""`, `displayName=""`. `@Resource`: `name=""`, `displayName=""`, `precision=15`,
`scale=2`. An `@Enumeration` enum is supported as an accumulation-register dimension: movement and
totals storage use its stable UUID, while typed filters/reads use the enum constant.

### `@Enumeration` (on a Java `enum`)
`name` (required), `title=""` (display label for the type; falls back to `name`), `tableName=""`.
Persisted as a reference table; each value gets a stable UUID. Annotate a constant with
`@EnumLabel("…")` to give it a human/localized display label (surfaced in `FieldDisplay`, the
`enumValues[].label` metadata, and the dropdown) without renaming the constant; unlabelled constants
display as their name. `@EnumLabel(value="…", color="#RRGGBB")` additionally paints the value as a
colored status pill in list cells and the form dropdown — the colour rides the read API
as `FieldColor` and `enumValues[].color`; an uncoloured value renders as plain text. Storage
compatibility reads retain `{col}_display` and `{col}_color`.

### `@Constant` (on a plain class with one value field)
`name` (required).

### `@ScheduledJob` / `@Scheduled`
`@ScheduledJob`: `name` (required), `cron` (required). Plain Spring `@Scheduled` jobs also work
(the example uses `@Scheduled(initialDelay=…, fixedDelay=…)`).

## Durable typed business processes (package `su.onno.process`)

| Type | Purpose |
| --- | --- |
| `ProcessStepKey` | Implement on the step enum. `key()` defaults to the enum name and may be overridden with a stable persistence key. |
| `HumanTask<P,O extends Enum<O>>` | Typed human work; declares `outcomeType()`, `assignment(payload)`, and optional `title(payload)`. |
| `ProcessActorId` / `ProcessIdentity` | Stable task-owner id plus mutable login/display snapshots. A linked Employee record UUID is the normal id. |
| `TaskAssignment` | Candidate `identities(Ref<?>...)`, `actors(ProcessActorId...)`, `roles(...)`, or both; `ADMIN` bypasses assignment checks. |
| `ProcessDefinition<P,S>` | Call `super(stableKey, Payload.class)` for v1 or `super(stableKey, version, Payload.class)`, declare start/cancellation assignment, and override `define`; instances pin the exact version. |
| `ProcessGraph<P,S>` | `start`, `human`, `automatic`, `decision`, `timer`, typed `parallel(fork, Branch.class, join)`, `subprocess`, `end`, and inspectable `descriptor()`. |
| `HumanTaskNode<P,S,O>` | `on(outcome).to(node)`; the outcome and target types are compiler-checked. |
| `ProcessDefinitionMigration` | Explicit forward payload migration plus a typed target step for every active token id. |
| `ProcessEngine` | Durable `start`, visible `instances`, `find`/`tokens`/history, inbox, claim/delegate/complete, `cancel`, `migrate`, and background `runPending`. |
| `ProcessActor` | Authenticated username + normalized role set for a process command. |
| `ProcessSnapshot` | Definition version, compatibility `currentStep`, authoritative `activeSteps`, root/parent links, status, starter, lifecycle timestamps, cancellation reason, optimistic version. |
| `ProcessTokenSnapshot` | Durable branch/wait execution with parent token, node type/status, due time, and child instance link. |
| `ProcessWorkItem` | Durable task, token/definition version, stable assignee id/display snapshot, optional domain `subject`, timestamps, and allowed enum outcome names. |
| `ProcessWorkItemEventSnapshot` | Ordered `CREATED`/`CLAIMED`/`DELEGATED`/`COMPLETED`/`CANCELLED` task audit entry. |

Graph validation rejects a missing start target, duplicate/blank stable keys, duplicate enum steps,
cross-graph connections, unreachable nodes, incomplete task/decision/fork/subprocess routes,
parallel branches that bypass their paired join, and immediate-only cycles.

The starter persists instances, work items, and transitions in `onno_process_*`. The web starter
exposes the inbox plus claim/delegate/history/complete endpoints (`/api/tasks`), rendered by the
`<TaskInbox />` component of `@onno/ui`. Delegation uses the `IdentityLink` catalog as its employee picker.
Avatar/image/photo-hinted identity fields supply live photos in ownership, delegation, and history.
`HumanTask.subject(payload)` may return a typed catalog/document `Ref<T>`; the task inbox opens that
record in the shell's adjacent detail pane while retaining an ordinary deep-link `href`.
`subjectLabel(payload)` optionally persists a human-readable link snapshot such as
`"Order " + payload.orderNumber()`.
Every committed task mutation publishes `ProcessTasksChangedEvent`; eligible browser inboxes receive
the audience-scoped, payload-free `tasks-changed` SSE event and refetch automatically (also across
nodes through `ClusterEvent.ProcessTasksChanged`).
Automatic steps return the next payload and receive `ProcessExecutionContext.idempotencyKey()`; keep
them deterministic and use an outbox for external effects. JobRunr wakes durable timer and
subprocess waits. Cancellation closes descendants and work atomically. Evolving an active graph
requires a new definition version plus a registered `ProcessDefinitionMigration`; never reinterpret
stored instances under a changed same-version graph.

### `@DomainEvent` (repeatable; outbox)
`name` (required), `when = EventTiming.AFTER_WRITE|AFTER_POST|AFTER_DELETE` (default `AFTER_WRITE`).

### `@AccessControl` (RBAC; deny-by-default, `ADMIN` always allowed)
`readRoles=String[]{}`, `writeRoles=String[]{}` (falls back to `readRoles` if empty).
Documents only: `postRoles=String[]{}`, `unpostRoles=String[]{}` — narrow who may post/re-post or
unpost **on top of write access** (empty = every writer). Deleting a posted document needs the
unpost right. Enforced on REST/MCP/UI actions; direct `PostingService` calls are trusted code.

### `RecordAccessPolicy` (record-level; package `su.onno.access`, a Spring bean)
`RecordAccessPolicy.forCatalog|forDocument|forRegister|of(Type.class)` `.appliesTo(roles…)`
`.exemptRoles(roles…)` `.read(RecordScope)` (required) `.write(RecordScope)` (default = read)
`.defaults(d -> d.set(field, value))`. `RecordScope.eq|in(field, literal | Subject.recordId() |
Subject.username() | Subject.attribute(name))`, `isNull(field)`, `via(refField[, targetScope])`,
`and|or|not`, `all()|none()`. Subject attributes come from `AccessSubjectContributor` beans. Query and
command services take an `AccessSubject` (controller parameter; `AccessSubject.system()` for trusted
code); app code checks with the `RecordAccess` bean (`require|can|filter|clause`). Typed repositories
are unscoped.

### UI and behaviour
Presentation is React in the app's `src/main/ui` (`@onno/ui`), never domain annotations. Server
behaviour beyond CRUD is `Commands<E>` / `Checks<E>` / `PickerOptions<E>` / `IdentityLink` beans —
see the UI and commands sections below.

## Base classes & types (package `su.onno.model`, `su.onno.types`)

| Type | Carries |
| --- | --- |
| `CatalogObject` | `UUID id`, `String code`, `String description`, `boolean deletionMark`, `@Version Integer version`, `@Transient boolean isNew=true`. Flat catalogs have no hierarchy columns. |
| `HierarchicalCatalogObject<E>` | Extends `CatalogObject`, adding `boolean folder` and `Ref<E> parent` (`null` = root). Use `Category extends HierarchicalCatalogObject<Category>` with `@Catalog(hierarchical = true)`; set parents via `Ref.of(Category.class, id)`. UUID storage is unchanged. Legacy flat columns are retained unless destructive schema changes are explicitly enabled. |
| `DocumentObject` | `UUID id`, `String number`, `LocalDateTime date`, `boolean posted`, `boolean deletionMark`, `@Version Integer version`, `isNew=true`. |
| `TabularSectionRow` | `UUID id`, `@Transient int lineNumber` (1-based). |
| `AccumulationRecord` | `UUID id`, `LocalDateTime period`, `boolean active=true`, `UUID documentRef`, `MovementType movementType=RECEIPT`, `isNew=true`. |
| `InformationRecord` | `UUID id`, `LocalDateTime period`. |
| `Ref<T>` | `record Ref(Class<T> type, UUID id)`; `Ref.of(type, id)`. Stored as UUID column; resolve via `RefResolver`. |
| `PolyRef` | `record PolyRef(Class<?> type, UUID id)`; declare an explicit `@RefTargets({...})` on each field. Stored as `fully.qualified.JavaType|UUID`; use only when several catalog/document types are valid. |

Enums: `AccumulationType{BALANCE,TURNOVER}`, `Periodicity{NONE,SECOND,MINUTE,HOUR,DAY,MONTH,QUARTER,YEAR}`,
`MovementType{RECEIPT,EXPENSE}`, `PostingOrder{INDEPENDENT,CHRONOLOGICAL}`,
`EventTiming{AFTER_WRITE,AFTER_POST,AFTER_DELETE}`.

## Lifecycle & rules (packages `su.onno.lifecycle`, `su.onno.rules`)

| Interface | Method | When |
| --- | --- | --- |
| `BeforeWriteHandler` | `beforeWrite()` | Before save and before post — compute derived fields. |
| `AfterWriteHandler` | `afterWrite()` | After a successful repository or generic REST/import/MCP save; validation previews skip it. |
| `OnFillingHandler` | `onFilling()` | Seed/normalize defaults on **create/save**. It does not populate the initially rendered blank New form; use Java field initializers for values that must appear there. It runs on every save of a new entity (`isNew==true`), so guard on null and keep it idempotent. |
| `BeforePostHandler` | `beforePost()` | Before posting (validation) — **no Spring DI** (see below). |
| `Postable` | `handlePosting(PostingContext)` | Write register movements. |
| `BeforeUnpostHandler` | `beforeUnpost()` | Before unposting (incl. the unpost a delete of a posted document performs) — throw to veto; **no Spring DI**. Not run on re-post. |
| `AfterPostHandler` | `afterPost()` | After post — **no Spring DI**; prefer `@EventListener` on `DocumentPostedEvent`. |
| `BeforeDeleteHandler` | `beforeDelete()` | Before delete — runs on BOTH the repository path and the UI/REST DELETE; throw (e.g. `ValidationException`) to veto, which is how a catalog protects seeded system rows. |
| `Validated` | `List<BusinessRule> rules()` | Rules checked before write and before post — **no Spring DI** (see below). |

⚠️ **No Spring DI in any of these hooks.** They run on the domain object, which the framework creates
by reflection (`new`), not as a Spring bean — so `@Autowired` fields are null in `beforeWrite`,
`onFilling`, `beforePost`, `rules()`, `handlePosting`, `afterPost`, etc. Cross-entity work (e.g. a
`rules()` conflict check that queries other documents) needs a static `ApplicationContext` bridge bean
(a `@Component implements ApplicationContextAware` exposing a static accessor) or, for after-the-fact
side effects, an `@EventListener` on the published event (`DocumentPostedEvent`, `EntityChangedEvent`).

`BusinessRule` — `record(String name, String field, String message, BooleanSupplier condition)`.
Constructors: `new BusinessRule(name, message, condition)` (cross-field) and
`BusinessRule.onField(field, message, condition)`. Validation collects all failing rules into one
validation exception/payload.

## Posting (package `su.onno.posting`)

- `PostingService` — `post(DocumentObject)`, atomic `repost(DocumentObject)`, `unpost(DocumentObject)`, `preview(DocumentObject)`.
- `PostingContext` — `movements(Class<T extends AccumulationRecord>)` returns a `RegisterRepository<T>`;
  call `addReceipt(Consumer<T>)` / `addExpense(Consumer<T>)` to stage movements.
- Events: `DocumentPostedEvent(document)`, `DocumentUnpostedEvent(document)` (Spring events, after commit).
- `PostingEngine` runs `beforeWrite → beforePost → rules → handlePosting`. Normal posting stages
  movements before opening its JDBI persistence transaction, then atomically claims an existing
  unposted document, inserts movements, updates totals, rejects negative BALANCE results unless the
  register declares `allowNegative=true`, writes back computed fields, and sets `_posted=true`.
  Core `post` rejects an already-posted document; `repost` reverses old movements and writes the
  recalculated set in one transaction, preserving the old posting if recalculation fails. Unpost
  rejects drafts and deleted documents.
  Preview stages/returns movements without claiming the document, checking negative balances, or
  simulating chronological restoration, so it is not a guarantee that post succeeds. Save and
  commit before posting.

## Period close (package `su.onno.period`)

- `PeriodCloseService` bean (off with `onno.period-close.enabled=false`):
  `close(scope, closedThrough, actor, reason)` (soft), `close(scope, closedThrough, hard, actor, reason)`,
  `reopen(scope, closedThroughOrNull, actor, reason)`
  (reason required), `locks()`, `closedThrough(scope)`, `audit(limit)`,
  `override(new PeriodOverride(actor, reason), action)`.
- `PeriodScope.global()` / `.context("Finance")` / `.register("Cash")` / `.organization(orgRef)`;
  text form `global`, `context:<name>`, `register:<name>`, `organization:<id>`. Close date is
  inclusive. Organization scope needs `@PeriodCloseOrganization` on the document's org field.
- Soft close (default) admits `@PeriodAdjustment` document types (audited `ADJUSTMENT`) and
  overrides; hard close admits nothing. Turning hard into soft is a `reopen` at the same date.
- Rejects create/update/delete/post/repost/unpost of documents dated on or before the close date
  with `PeriodClosedException` (HTTP 409 `period-closed`) — in `PostingEngine` (incl. later
  documents of a `CHRONOLOGICAL` restoration), repository saves, and the generic command path.
- `@PeriodCloseExempt` on a `@Document` class opts a non-accounting document out.
- Roles: `onno.period-close.manage-roles` (+`ADMIN`) close/reopen; `onno.period-close.override-roles`
  bypass (empty by default; `ADMIN` only when listed). All audited in `onno_period_close_audit`.
- API: `GET /api/period-close`, `GET /audit`, `POST /close`, `POST /reopen`; `/api/ui/model`
  reports `features.periodClose`. UI: `@onno/ui`'s `<PeriodClose scopes? auditRows? />`, registered
  by the app as a page (e.g. `/period-close`, ADMIN, `when: m => m.features.periodClose === true`).

## Runtime query paths

There is no universal query DSL. Use `CatalogRepository` / `DocumentRepository` for typed business
reads and `RegisterRepository.query()` for typed balance/turnover reads. The generated REST, UI, and
MCP surfaces use dedicated metadata-driven catalog, document, and register query services.

The `su.onno.fields.Field<E,V>` getter token is the preferred field API in authored Java:
`row.enumValue(Order::getStatus, OrderStatus.class)`, `check.dependsOn(ScheduleEvent::getStartsAt)`. String overloads are unsafe escape hatches for
dynamic metadata; routes, labels, role/action/process keys, and filter expressions remain strings
because they are not Java field references.

## Repositories (package `su.onno.repository`)

- `CatalogRepository<T> extends ListCrudRepository<T,UUID>` + `findByCode(String)`.
- `DocumentRepository<T> extends ListCrudRepository<T,UUID>` + `findByNumber(String)`,
  `findByDateBetween(from, to)`.
- **Soft-delete-aware finders** (on both `CatalogRepository` and `DocumentRepository`): deletion is
  soft (`deletionMark = true`, row stays), and the inherited `findAll()`/`findById()`/`findByCode()`/
  `findByNumber()`/`findByDateBetween()` **return deletion-marked rows** (needed by `RefResolver` and
  restore/admin). Business logic (auth, posting, totals, option lists) must exclude them: use
  `findAllActive()`, `findActiveById(UUID)`, `findActiveByCode(String)` (catalog) /
  `findActiveByNumber(String)` + `findActiveByDateBetween(from,to)` (document) — or filter
  `!isDeletionMark()`. Backed by derived queries (`findByDeletionMarkFalse()` etc.).
- **Deletion-check guardrail** — boot-time scan (`onno.repository.deletion-check` = `warn` default /
  `strict` / `off`) flags any *consumer-declared* catalog/document finder returning entities that
  isn't deletion-scoped (no `…AndDeletionMarkFalse`, no `deletion_mark` in `@Query`, not `findActive*`).
  Opt a finder out with `@su.onno.repository.IncludesDeleted` when it must see tombstones (Ref
  resolution, restore/admin).
- Repository `delete(...)`, `deleteById(...)`, and bulk delete methods for catalog/document entities
  run `BeforeDeleteHandler` and persist `deletionMark=true`; a posted document must be unposted first.
  Repositories for ordinary non-onno aggregates still physically delete.
- `RegisterRepository<T>` — read-only for accumulation registers: `getBalance(...)`,
  `getTurnover(from,to,...)`, `getRecordsByDocument(uuid)`, plus `addReceipt/addExpense` used during
  posting; writes happen via the `PostingEngine`. Filters narrow a read in one query, not in Java:
  a `Map`/`RegisterFilter` value that is a `Collection` renders `col IN (…)`
  (`getBalance(Map.of("nomenclature", List.of(a, b)))`); the fluent `query()` builder
  (`.balance()/.turnover()/.at()/.from()/.to()/.groupBy()/.where()`) adds `whereIn(field, values)`
  and tuple `whereIn(fieldA, fieldB, tuples)` → `(colA, colB) IN ((…),(…))`, so a posting can fetch
  balances for exactly a document's dimension-tuple set rather than the whole register slice. An
  empty collection matches nothing.
- `InformationRegisterRepository<T>` — write-enabled: `write(record)`, `getSliceLast(date,...)`,
  `getSliceFirst(date,...)`, `getRecords(...)`, `delete(record)`.
- `RegisterRepository` also exposes admin/diagnostic `rebuildTotals()` (recompute cached BALANCE
  totals from raw movements) and `verifyTotals()` (consistency check) — reach for them after a bulk
  migration or to debug a drifted balance.
- Optimistic locking: `@Version` mismatch on save throws `OptimisticLockException` (`409` via API).

## Commands, checks, pickers, identity (packages `su.onno.command`, `su.onno.validation`, `su.onno.picker`, `su.onno.identity`)

- `Commands<E>` bean — `Class<E> entity()`, `define(CommandSpec<E> c)`. Several beans per entity
  merge (first key wins). Every command is on every surface: UI, REST (`/api/commands/{kind}/{name}
  [/{key}[/defaults|/batch]]`), MCP tool `<entity>_<key>` (static commands only; needs
  `onno.mcp.writes-enabled`).
  - `c.command(key)` record command; `c.collectionCommand(key)` no record id; `c.dynamic(live -> …)`
    commands from live data (re-evaluated per listing/run; not MCP tools).
  - `CommandBuilder`: `title(String)`, `title(row -> String)` (record only), `description`,
    `group("Change status")` (UI submenu), `roles(...)` (any-of on top of write access; `ADMIN`
    passes), `availableWhen(row -> bool)` (unlisted + rejected), `enabledWhen(row -> bool)`
    (listed disabled), `input(key, i -> i.title(…).text()|longText()|date()|number()|oneOf(…)|
    reference(Target.class).defaultValue(…).required())`, `inputRows(key, g -> g.title(…).required()
    .column(col, c -> …))`, `defaults(ctx -> CommandDefaults.ofValues(map)|ofRows(group, rows))`,
    `run(ctx -> CommandResult)` (required).
  - `RecordRow<E>`: `id()`, `get(getter|col)`, `text(getter|col)` (display), `bool(...)`,
    `enumValue(getter, Enum.class)`, `values()`.
  - `CommandContext`: `id()`, `input(key)` (`""` if absent), `inputRows(key)` (`List<Map<String,
    String>>`), `user()`, `subject()` (pass to `RecordAccess` for other records).
  - `CommandResult.ok()|reload()|success|info|warning|error(msg)` `.title(…)`, `.detail(…)`,
    `.details(list)`, `.andRefresh()`; `CommandResult.open(Ref)` / `.navigate(route)` /
    `.andNavigate(route)` set `navigateTo` (app path or `onno://`; command/external URLs rejected;
    applied after feedback and refresh). Expected refusal: `throw CommandRejectedException.builder()
    .title(…).message(…).fieldError(input, msg).formError(msg).build()` (HTTP 422; keeps the input
    dialog open).
- `Checks<E>` bean — `define(CheckSpec<E> v)`: `v.check(key, ctx -> List<Feedback>)
  .dependsOn(E::getA, E::getB).andDependsOn(E::getLines, Line::getX).debounce(Duration)`.
  `CheckContext`: `kind`, `name`, `entityType`, `id` (null on create), `values` (by field name).
  `Feedback.error|warning|info(field, msg)`, `Feedback.formError(msg)`. Advisory dry run
  (`POST /api/checks/{kind}/{name}/{key}`); `Validated` stays authoritative.
- `PickerOptions<E>` bean — `entity()`, `field()` (`"customer"` or `"section.field"`; one bean per
  field), `decorate(PickerContext, List<PickerCandidate>) → Map<UUID, PickerOption>`.
  `PickerContext`: `targetKind`, `targetName`, `fieldPath`, `formValues`, `section`, `rowIndex`,
  `rowValues`, `recordId`. `PickerOption.badge(label, PickerTone)`, `.disabled(label, tone, reason)`,
  `.filteredOut()`; `PickerTone.NEUTRAL|SUCCESS|WARNING|DANGER`.
- `IdentityLink` bean — `IdentityLink.of(Employee.class, "email")`: login → catalog record (task
  routing/delegation, comment authors, notifications, `Subject.recordId()`).

## UI (`@onno/ui`; app in `src/main/ui`; Gradle plugin `su.onno.ui`)

- Gradle: `id("su.onno.ui")`; tasks `onnoUiInit` (seed), `generateUiModel` (`model.gen.ts` from the
  Java entities), `buildUi` (`vite build` → jar `onno-app/`, served instead of the default shell).
  `onnoUi { sourceDir; npmProjectDir; nodeVersion; modelPackages }`. `vite.config.ts`:
  `plugins: [onno()]` from `@onno/ui/vite` (`onno({ backend })` for the dev proxy target).
- `defineApp({ entities, pages, navigation, extensions })` — once, in `main.tsx`:
  - `entities: { [logicalName]: EntityCustomization<E> }` — one per entity (`entities/Orders.ts`,
    `satisfies EntityCustomization<"Orders">`), applied wherever it shows — `icon`, `comments`,
    `list`, `form`, `fields`,
    `rowStyle(record) → "danger"|"warning"|"success"|"accent"|"muted"|null`,
    `commands: { key: { placement: "primary"|"menu"|"row"|"hidden", icon, title } | placement }`
    (keys: server commands, built-ins `post`/`unpost`/`duplicate`/`delete`/`openInList`,
    `print-<form>[-pdf]`), `recordPanels: [{ component, title, hint, width }]`.
  - `list`: `columns`, `labels`, `sort` (`"-date"`), `groupBy`, `groupable` (`["status",
    { date: "month" }]`), `aggregates` (`{ sum|avg|min|max: field, label, format }`), `filters`
    (`{ field, label, type?: "options"|"multiOptions"|"contains"|"startsWith"|"dateRange"|
    "numberRange", options?, optionsFrom? }`), `where`, `title`, `map` (`{ lat, lng, label?,
    defaultView? }`), `custom` (`{ component: ListRendererProps → node, label, defaultView }`),
    `selection` (component `{ ids, complete }`), `selectionCheckboxes`, `calendar`, `timeline`.
  - `form`: `fields` — header fields shown, in order. Selection/order is only ever an array
    (`list.columns`, `form.fields`).
  - `fields[field | "section.field"]` (presentation only; key order means nothing): `label`,
    `hidden`, `hideInList`, `hideInForm`, `group`, `width: "full"|"half"`, `input: "textarea"|"switch"|"image"|"images"|"file"|"color"|
    "avatar"|"map"`, `format: { money: "USD" } | { number: { decimals, grouping } } | { percent:
    { decimals } } | { date: "short"|"medium"|"long"|"full" | Intl options }`, `render(value,
    record)`, `placeholder`, `hint`, `refSecondary`, `filterBy: { targetField: formField }`,
    `openButton`, `clearButton`, `uniqueWithinSection`.
  - `pages: [{ path, component, label, icon, roles, when(model), view }]` — `"/"` is home; an
    entity route (`/documents/orders`) replaces that default screen; `:param`/`*` captures reach
    the component as `RouteProps` (`path`, `params`, `query`, `view`, `renderDefault()`).
  - `navigation: [{ title, icon, items: [entityName | pagePath | { entity|path, label, icon,
    roles }] }]` or `(model, defaults) => sections`; omitted → pages + one section per `context`.
  - `extensions: [{ id, slot, order, visible(ctx), component }]` — slots `entity.list.context-menu`,
    `entity.list.actions`, `entity.list.selection`, `entity.form.actions`, `entity.form.aside`,
    `crm.chat.*`, `crm.contact.actions|profile`, `crm.inbox.toolbar`.
- Typed model (`model.gen.ts`): `EntityName`, `CatalogName`/`DocumentName`/`RegisterName`,
  `RecordOf<E>` (with `<f>Display`, `<f>Ref` companions), `FieldOf<E>`, `TopField<E>`, `FormField<E>` (form-layout fields: no sections, no document `number`/`date`/`posted`), enum
  constants + `…Labels`, `RoleName` (extend: `declare module "@onno/ui" { interface OnnoRoles {
  X: true } }`), `InformationRegisterName`, `ConstantName`.
- `Where<E>`: `{ field: value | null | { eq, not, in, notIn, gt, gte, lt, lte, contains,
  startsWith }, id, and, or, not }`; registers' `MovementWhere` adds `period`, `recorder`,
  `movementType`.
- Measures: `count(entity, where?)`, `sum|avg|min|max(entity, numericField, where?)`; `By<E>` =
  field or `{ dateField: "minute"|"hour"|"day"|"week"|"month" }`.
- Page blocks: `Page` (`title`, `subtitle`, `actions`), `Row` (`widths: (number | { px })[]`),
  `EntityList` (`entity`, `where`, `columns`, `labels`, `overrides`, `customization`, `fill`,
  `defaultView|GroupBy|Sort`, controlled `view|groupBy|sort|search|selection` + `on…Change`),
  `RecordPage`, `RecordFields` (`fields: FormField[]`, `overrides`), `RecordLines` (`section`,
  `overrides`), `DefaultView`
  (`path`), `DefaultHome`, `PageHeader`, `EntityListView` (+ `applyListOptions`) over an own feed.
- Widgets (all `title`, `hint`): `Stat` (`measure`, `compare`, `trend`, `sparkline`, `ratioTo`,
  `currency`, `unit`, `format`), `Sparkline`, `Gauge` (`target`), `Chart` (`measure`+`by` or
  `series[]`, `type`, `splitBy`, `stacked`, `axes`, `thresholds`, `legend`, `curve`, `bucketMode`),
  `TimeRange` (`presets`, `default`, `id`), `Calendar` (`entity`, `start`, `end`|`duration`,
  `itemTitle`, `subtitle`, `color`, `fill`), `Kanban` (`entity`, `by` enum/ref/`posted`),
  `RecentRecords`, `EntityMap` (`lat`/`lng` or `geoJson`), `Setting` (`constant`), `TaskInbox`,
  `UserAccounts`, `ChangePassword`. Starters: `PrintTemplates` (`@onno/print`), `CrmInbox`,
  `CrmConnections`, `CrmConversationPage`, `CrmContactDetails` (`@onno/crm`), `channelSetups`
  (`@onno/crm-channels`).
- Data hooks (TanStack Query, live via `/api/events`): `useRecords(e, { where, sort, limit })`,
  `useRecord(e, id)`, `useAggregate(measure, by?)`, `useBalance(reg, { where, asOf })`,
  `useTurnover(reg, { from, to, where })`, `useMovements(reg, { from, to, where })`,
  `useInformationRegister(name, { where, at })`, `useConstant(name)`, `useSetConstants()`,
  `useCreateRecord`, `useUpdateRecord` (`{ id, values }`), `useDeleteRecord`, `usePostDocument`,
  `useUnpostDocument`, `useCommand(e, key)` (`mutate({ id?, inputs? })`); `useModel`,
  `useHasRole`, `useUiEvents(listener, filter)` (non-entity events), `useQuery`/`queryClient`/`api`.
- Navigation: `openRecord(e, id = "new", prefill?)`, `openRoute(link | path)`, `links.record|
  newRecord|list({ where })|page` → `onno://documents/orders/<id>`, `…/new?customer=<id>`,
  `…?where=<json>`, `onno://ops`.
- Text/format: `useTranslate`, `text(key)`, `useLocale`, `locale()`, `formatMoney(v, "USD")`,
  `formatDate(v, opts?)`, `displayOf(record, field)`.
- Config: `onno.ui.path` (`/ui`), `onno.ui.locale`, `onno.ui.messages.*`, `onno.ui.branding.*`,
  `onno.ui.login.demo-accounts`; `onno.web.read-only`, `onno.web.list.page-size`,
  `onno.web.dev-mode`.

Every readable entity is served by default; access is `@AccessControl` + record policies, never a
UI allowlist. Media inputs stream the file to `MediaStorage` and persist the returned URL (see
`docs/MEDIA_UPLOADS.md`).

## Print forms (onno-print-starter, package `su.onno.print`; `onno.print.enabled`)

- `@Component class X extends PrintForm<Order>`: `super("invoice", Order.class)` (key `[a-z0-9-]`,
  unique per type); `configure(PrintFormSpec)` → `label`, `template("classpath:/print/x.docx")`
  (default `classpath:/print/{key}.docx|.xlsx`), `icon`, `roles(...)`, `locale`;
  `fill(T, TemplateModel)`; optional `fileName(T)`.
- `TemplateModel`: `text(key, Object)` (Ref → record name, enum → `@EnumLabel`), `number(key, n[, pattern])`,
  `date(key, t[, pattern])`, `table(key, items, (row, item) -> row.text(...)…)`. Patterns apply in
  Word only; Excel cells keep their own format.
- Template syntax `{{key}}`, table rows `{{items.col}}` (row repeats per item). Unknown keys stay
  visible. Excel `SUM` ranges ending on the template row stretch over inserted rows.
- UI: record ⋯ menu entry per permitted form, command key `print-{key}` (`print-{key}-pdf` for PDF);
  place/icon it in the entity customization: `commands: { "print-invoice": { placement: "primary", icon: "printer" } }`.
- REST: `GET /api/print/{kind}/{name}/{id}` (list), `GET /api/print/{kind}/{name}/{id}/{form}[?format=pdf]`
  (file), `POST /api/print/batch/{kind}/{name}/{form}` `{"ids":[…]}` (zip), `/api/print/templates/**` (admin).
- Batch: list selection toolbar → zip (`onno.print.batch.max-records`, default 200).
- Uploaded templates: `<PrintTemplates />` from `@onno/print`; `onno.print.templates.*`
  (`enabled`, `manage-roles` = ADMIN, `max-size` = 10MB); same format as the built-in file.
- PDF: `onno.print.pdf.gotenberg.url` or `onno.print.pdf.libreoffice.command` (or a `PdfConverter`
  bean); `spec.pdf()` makes PDF the default (fails startup without a converter); menu gains `· PDF`.
- Generic SPIs behind it: `su.onno.web.RecordActionContributor` (record ⋯ menu) and
  `su.onno.web.SelectionDownloadContributor` (list selection-toolbar downloads).

## Notifications (package `su.onno.web.notifications`; `onno.notifications.*`)

- Per-user timeline behind the shell's bell + `/api/notifications` (sidebar trigger on desktop, a
  Notifications row in the More menu on mobile — with unread badges and a dot on the More tab).
  Persisted in the framework-owned `onno_notifications` table; delivered live over the
  `notification` SSE event (routed by recipient, relayed cross-node over the `ClusterEventBus`).
- Produce one from any bean: `notificationService.notify(NotificationRequest.to(recipientId).type("…")
  .title("…").body("…").link("kind/name/id").actor(currentUser).build())`. `recipientId` is the
  target's identity `recordId` (from `CurrentUserResolver`). This is the extension point — add a source
  by calling it from an `@EventListener`.
- Built-in, config-gated producers: **mentions** (`onno.notifications.mentions.enabled`) turn a comment
  `@`-mention of a user into a notification; **assignment** (`onno.notifications.assignments.enabled`)
  notifies the target of an `@AssigneeField`-annotated `Ref<>` when it's set/changed to a user.
- `@AssigneeField` (`su.onno.annotations`; on a catalog/document `Ref<>` attribute pointing at the identity catalog) — marks
  the assignee; setting it fires the assignment producer. Notification hint only; no storage/UI effect.

## Events & outbox (packages `su.onno.events`, `su.onno.messaging`)

- `EntityChangedEvent(changeType, entityType, entityName, UUID id, naturalKey)` — published on every
  write through controllers and `repository.save(...)`; drives `/api/events` SSE. Constants:
  changeType `created/updated/deleted/posted/unposted/changed`; entityType `catalog/document/register`.
- `EntityMentionedEvent(comment, mention, actor)` — published per readable `@`-mention in a posted
  comment; consumed by the notifications feature, and by any app `@EventListener` (mail, etc.).
- `OutboxWriter.append(aggregateType, aggregateId, eventType, payload)` → `onno_outbox`; the
  starter wires it to join active Spring JDBC transactions. Posting uses
  `append(Handle, ...)` so the business mutation and outbox row commit or roll back together.
  An application-owned relay reads committed rows.

## Schema & migration (packages `su.onno.schema`, `su.onno.migration`)

- `SchemaMode{APPLY,PLAN,VALIDATE,OFF}` ← `onno.schema.mode`. `SchemaUpgrader.run(jdbi)` diffs
  desired `SchemaModel` vs previous `SchemaSnapshot` (JSON in `onno_schema_history`) vs live DB.
- `SchemaChange.Type{CREATE_TABLE,RENAME_TABLE,RENAME_COLUMN,ADD_COLUMN,ALTER_COLUMN_TYPE,ALTER_COLUMN_NULLABILITY,ALTER_UNIQUE_KEY,DROP_COLUMN,DROP_TABLE}`;
  `MigrationPlan.safe()/destructive()`.
- `ALTER_UNIQUE_KEY` reconciles an information register's `(_period, dimensions…)` key with the live
  `UNIQUE` constraints, so a changed `periodicity`/`@Dimension` moves the key on an existing database.
  Widening it applies by default; narrowing it is destructive-gated.
- `AppMigration` — `version()` (segment-wise compare), `description()`, `migrate(MigrationContext)`;
  `MigrationContext.execute(sql)`. Runs once per DB, in version order, recorded in history.

## MCP tools (onno-mcp-starter, served at `/mcp`)

`describe_metadata` (including latest typed process graph descriptors), `list_catalog`,
`get_catalog`, `list_documents`, `get_document`,
`register_balance`, `register_movements`, `posting_preview` (reads); `create_catalog`,
`update_catalog`, `delete_catalog`, `create_document`, `update_document`, `delete_document` (writes,
gated by `onno.mcp.writes-enabled`); `post_document`, `unpost_document` (gated by
`onno.mcp.posting-enabled`). All run as the authenticated user under the same deny-by-default RBAC.
Catalog/document lists are keyset-paged. Register movements and balances are capped at 1,000 and
5,000 rows; wider matches fail explicitly rather than returning an incomplete list.
Add application tools with `@McpTool` on public Spring bean methods and describe inputs with
`@McpToolParam`; `Principal`/`McpToolContext` are injectable, `roles` restricts callers, and
`readOnly = false` opts into the global write gate. Use `McpToolProvider` for direct SDK tools.

Commands are MCP tools too: every static `Commands<E>` command is a tool `<entity>_<key>` with its
inputs as the schema (see the commands section).

## Composable CRM messaging

`CrmCustomerBinding<T>` wraps a `CrmCatalogBinding<T>` of an existing host catalog; an optional
inbound callback returns a saved `Ref<T>`. `CrmAgentBinding<T>` adds host identity assignment.
No CRM customer/employee/sales catalogs or navigation are installed. Customer fields, stages,
forms and sales metrics use ordinary onno metadata. See `onno-crm-starter/README.md`.

CRM connection pages: `<CrmConnections setups={[channelSetups]} scopes={["shared", "personal"]} defaultScope="shared"
sharedLabel=… personalLabel=… providers={["gmail", …]} showUnavailable />` (React, `@onno/crm`). Provider metadata
comes from `CrmAccountProvider`; ownership from `CrmConnectionAccounts`. Personal scope must
isolate credentials and inboxes. `EntityAccessPolicy.includesAdministrators()` is an explicit
opt-in privacy boundary, not the default role behavior.

### CRM screens

The CRM's screens are React components of `@onno/crm` with typed props, rendered from the app's
pages: `<CrmInbox workspace="support" list={…} contactFields={…} folders={…} />`,
`<CrmConnections setups={[channelSetups]} />`, `CrmContactDetails` (a record panel),
`CrmConversationPage` (at `/catalogs/crm_conversations/:id`). Workspace beans own the authorized
feed, commands, folders and facets; the server never names a component.

Collection reads: use limit/cursor and `{rows,nextCursor,hasMore}` (100 default, 500 max).
Only su.onno.query.Cursor issues context-bound typed positions; use KeysetWindow for SQL seek.
SQL DATE values must remain typed. Count only by explicit request; never compute a report from
a silently capped preview. A plain-`List` Java method drains the cursor
(`KeysetWindow.drain`) instead of returning the first window; request paths use the paged variants
(`RegisterQueryService.movementsPage`/`balancePage`/`turnoverPage`). In `@onno/ui`,
`getMovements`/`getBalance`/`getTurnover` return pages and `allRegisterRows` follows the cursor.
See docs/HEADLESS_READ_API.md, Collection pagination, for the cursor-only contract, bounded
selectors and CRM callback limits.
