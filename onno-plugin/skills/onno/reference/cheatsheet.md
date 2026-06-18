# onno API cheat sheet

Dense lookup for the framework's public surface. Verify against `onno-framework/src/main/java/su/onno`
if something looks off — the code wins, and you should fix this file if it has drifted.

## Annotations (package `su.onno.annotations`)

### `@Catalog` (on a class extending `CatalogObject`)
| Element | Type | Default | Notes |
| --- | --- | --- | --- |
| `name` | String | — (required) | Logical/display name (URL-safe). |
| `title` | String | `""` | UI label; falls back to `name`. |
| `tableName` | String | `""` | Stable DB table; derived from `name` if empty. |
| `previousNames` | String[] | `{}` | Former name/tableName for rename detection. |
| `codeLength` | int | `9` | Max code length. |
| `hierarchical` | boolean | `false` | Enables parent/child folders. |
| `autoNumber` | boolean | `true` | Auto-generate codes. |
| `codePrefix` | String | `""` | Fixed code prefix. |
| `context` | String | `""` | Bounded-context grouping / future service split point. |

### `@Document` (on a class extending `DocumentObject`)
`name` (required), `title=""`, `tableName=""`, `previousNames={}` (covers doc + tabular section
table renames), `numberLength=11`, `autoNumber=true`, `numberPrefix=""`, `context=""`.

### `@TabularSection` (on a `List<Row>` field of a document; `Row extends TabularSectionRow`)
`name=""` (child table name; derived from the field name if empty).

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

### `@AccumulationRegister` (on a class extending `AccumulationRecord`)
`name` (required), `title=""`, `tableName=""`, `type = AccumulationType.BALANCE|TURNOVER` (default
`BALANCE`), `context=""`. Fields are `@Dimension` (grouping keys) and `@Resource` (numbers).

### `@InformationRegister` (on a class extending `InformationRecord`)
`name` (required), `tableName=""`, `periodicity = Periodicity.NONE|DAY|MONTH|QUARTER|YEAR` (default
`NONE`), `context=""`. Fields are `@Dimension`, `@Resource`, and plain `@Attribute`.

### `@Dimension` / `@Resource` (register fields)
`@Dimension`: `name=""`, `displayName=""`. `@Resource`: `name=""`, `displayName=""`, `precision=15`,
`scale=2`.

### `@Enumeration` (on a Java `enum`)
`name` (required), `tableName=""`. Persisted as a reference table; each value gets a stable UUID.

### `@Constant` (on a plain class with one value field)
`name` (required).

### `@ScheduledJob` / `@Scheduled`
`@ScheduledJob`: `name` (required), `cron` (required). Plain Spring `@Scheduled` jobs also work
(the example uses `@Scheduled(initialDelay=…, fixedDelay=…)`).

### `@DomainEvent` (repeatable; outbox)
`name` (required), `when = EventTiming.AFTER_WRITE|AFTER_POST|AFTER_DELETE` (default `AFTER_WRITE`).

### `@AccessControl` (RBAC; deny-by-default, `ADMIN` always allowed)
`readRoles=String[]{}`, `writeRoles=String[]{}` (falls back to `readRoles` if empty).

### Deprecated — do NOT add to new code
`@UiHint`, `@UiSection`, `@DashboardWidget`. UI is authored via `Layout`/`Page`/`EntityView` beans.

## Base classes & types (package `su.onno.model`, `su.onno.types`)

| Type | Carries |
| --- | --- |
| `CatalogObject` | `UUID id`, `String code`, `String description`, `boolean deletionMark`, `boolean folder`, `UUID parent`, `@Version Integer version`, `@Transient boolean isNew=true`. |
| `DocumentObject` | `UUID id`, `String number`, `LocalDateTime date`, `boolean posted`, `boolean deletionMark`, `@Version Integer version`, `isNew=true`. |
| `TabularSectionRow` | `UUID id`, `@Transient int lineNumber` (1-based). |
| `AccumulationRecord` | `UUID id`, `LocalDateTime period`, `boolean active=true`, `UUID documentRef`, `MovementType movementType=RECEIPT`, `isNew=true`. |
| `InformationRecord` | `UUID id`, `LocalDateTime period`. |
| `Ref<T>` | `record Ref(Class<T> type, UUID id)`; `Ref.of(type, id)`. Stored as UUID column; resolve via `RefResolver`. |

Enums: `AccumulationType{BALANCE,TURNOVER}`, `Periodicity{NONE,DAY,MONTH,QUARTER,YEAR}`,
`MovementType{RECEIPT,EXPENSE}`, `EventTiming{AFTER_WRITE,AFTER_POST,AFTER_DELETE}`.

## Lifecycle & rules (packages `su.onno.lifecycle`, `su.onno.rules`)

| Interface | Method | When |
| --- | --- | --- |
| `BeforeWriteHandler` | `beforeWrite()` | Before save and before post — compute derived fields. |
| `AfterWriteHandler` | `afterWrite()` | After save. |
| `OnFillingHandler` | `onFilling()` | When a new instance is filled. |
| `BeforePostHandler` | `beforePost()` | Before posting (validation). |
| `Postable` | `handlePosting(PostingContext)` | Write register movements. |
| `AfterPostHandler` | `afterPost()` | After post — **no Spring DI**; prefer `@EventListener` on `DocumentPostedEvent`. |
| `BeforeDeleteHandler` | `beforeDelete()` | Before delete. |
| `Validated` | `List<BusinessRule> rules()` | Rules checked before write and before post. |

`BusinessRule` — `record(String name, String field, String message, BooleanSupplier condition)`.
Constructors: `new BusinessRule(name, message, condition)` (cross-field) and
`BusinessRule.onField(field, message, condition)`. First failing rule throws with its message.

## Posting (package `su.onno.posting`)

- `PostingService` — `post(DocumentObject)`, `unpost(DocumentObject)`, `preview(DocumentObject)`.
- `PostingContext` — `movements(Class<T extends AccumulationRecord>)` returns a `RegisterRepository<T>`;
  call `addReceipt(Consumer<T>)` / `addExpense(Consumer<T>)` to stage movements.
- Events: `DocumentPostedEvent(document)`, `DocumentUnpostedEvent(document)` (Spring events, after commit).
- `PostingEngine` runs `beforeWrite → beforePost → rules` then, in its own JDBI transaction, inserts
  movements, updates totals, rejects negative BALANCE results, writes back computed fields, sets
  `_posted=true`. **Save then post — never wrap both in one `@Transactional`.**

## Query engine (package `su.onno.query`)

`QueryEngine.from(Class).select(…).where(…).groupBy(…).orderBy(…).limit(n).fetch()` →
`List<Row>` (case-insensitive keys) or `.fetchInto(Dto.class)`. Built on an immutable `QuerySpec`
AST, a fluent `QueryBuilder`, and a shared `SqlRenderer`. `Ref`-navigation auto-joins via `Path`.

`Q` static helpers build type-safe paths from method references:
`Q.col(SalesOrder::getNumber)`, `Q.ref(SalesOrder::getCustomer, Customer::getName)` (emits the join),
`Q.count()/sum/avg/min/max`, predicates `Q.eq/ne/gt/gte/lt/lte/between/like/in/isNull/isNotNull`,
`Q.as(item, alias)`. Predicate ops: `EQ,NE,GT,GTE,LT,LTE,BETWEEN,IN,LIKE,IS_NULL,IS_NOT_NULL`.

## Repositories (package `su.onno.repository`)

- `CatalogRepository<T> extends ListCrudRepository<T,UUID>` + `findByCode(String)`.
- `DocumentRepository<T> extends ListCrudRepository<T,UUID>` + `findByNumber(String)`,
  `findByDateBetween(from, to)`.
- `RegisterRepository<T>` — read-only for accumulation registers: `getBalance(...)`,
  `getTurnover(from,to,...)`, `getRecordsByDocument(uuid)`, plus `addReceipt/addExpense` used during
  posting; writes happen via the `PostingEngine`.
- `InformationRegisterRepository<T>` — write-enabled: `write(record)`, `getSliceLast(date,...)`,
  `getSliceFirst(date,...)`, `getRecords(...)`, `delete(record)`.
- Optimistic locking: `@Version` mismatch on save throws `OptimisticLockException` (`409` via API).

## UI DSL (package `su.onno.ui`)

- `Layout` — `profile()` (persona or null=default), `viewport()`, `configure(LayoutSpec)`:
  `spec.section(name).icon(…).catalog(X.class).document(Y.class)`, `spec.shell().nav(NavStyle.SIDEBAR)`,
  `spec.title/theme/priority/roles(...)`, `spec.identity(directoryClass, loginField)`.
- `Page` — `route()`, `profile()`, `viewport()`, `compose(PageBuilder)`: `b.title/subtitle`,
  `b.widget(title)` → `WidgetBuilder.type(…).width(…).document/catalog(…).config(k,v)`, `b.text`,
  `b.list(entity)`, `b.constants()`, `b.custom(type, payload)`.
- `EntityView` (non-generic) — `Class<?> entity()` (names the target catalog/document), `profile()`,
  `list(ListSpec)`, `fields(EntityConfigBuilder)`, `actions(ActionSpec)`, `inputs(InputSpec)`,
  `comments()` (return `true` to opt this catalog/document into the `/api/comments` discussion
  thread; off by default, gated by the global `onno.comments.enabled` switch).
  - `ListSpec`: `title`, `searchable/noSearch`, `sortBy(field, desc)`, `columns(...)`,
    `column(field,label)`, `label(field,label)`, `hide(...)`, `filter(field)` → options/contains/dateRange.
  - `ActionSpec`: `action(key)` → `ActionBuilder.label/icon(String)`, `scope(ActionScope.ROW|TOOLBAR|DETAIL)`,
    `handler(ctx→ActionResult)` or `navigate(url)`. A **row** action may vary per row — `icon(row→String)`,
    `label(row→String)`, `visibleWhen(row→bool)`, `enabledWhen(row→bool)` — taking an `ActionRow`
    (`id()`, `text(col)`, `enumValue(col,Type)`), evaluated as the list renders (#116).
  - `EntityConfigBuilder`: `field(name)` → `FieldHintBuilder`, `relatedList(name, joinCatalog)`,
    `action(name)`, `icon(name)`.
  - `FieldHintBuilder`: `order(int)`, `group(String)`, `width(String)`, `widget(String)` (`switch`,
    `textarea`, `image`/`avatar`/`gallery`/`file`, `map`/`geo`, …), `placeholder`, `format`
    (`currency:EUR`, `dd-MM-yy`, …), `hideInList/Form/Detail()`, `visibleInList/Form/Detail(bool)`,
    chain `.field(next)`.

An entity surface is only served if it has an `EntityView` for the active profile (no view → `404`);
that is necessary **but not sufficient** for the sidebar. **Nav is curated:** an entity shows in the
sidebar only if a `Layout` section lists it (`spec.section(...).catalog(X.class)`) — a view alone
makes it reachable by direct route but unlisted. No auto-listing of unclaimed catalogs (removed; cf.
#69). Media widgets stream the file to `MediaStorage` and persist the returned URL (see
`docs/MEDIA_UPLOADS.md`).

## Events & outbox (packages `su.onno.events`, `su.onno.messaging`)

- `EntityChangedEvent(changeType, entityType, entityName, UUID id, naturalKey)` — published on every
  write through controllers and `repository.save(...)`; drives `/api/events` SSE. Constants:
  changeType `created/updated/deleted/posted/unposted/changed`; entityType `catalog/document/register`.
- `OutboxWriter.append(aggregateType, aggregateId, eventType, payload)` → `onno_outbox`; relayed by
  `onno-kafka-starter` as CloudEvents.

## Schema & migration (packages `su.onno.schema`, `su.onno.migration`)

- `SchemaMode{APPLY,PLAN,VALIDATE,OFF}` ← `onno.schema.mode`. `SchemaUpgrader.run(jdbi)` diffs
  desired `SchemaModel` vs previous `SchemaSnapshot` (JSON in `onno_schema_history`) vs live DB.
- `SchemaChange.Type{CREATE_TABLE,RENAME_TABLE,RENAME_COLUMN,ADD_COLUMN,ALTER_COLUMN_TYPE,DROP_COLUMN,DROP_TABLE}`;
  `MigrationPlan.safe()/destructive()`.
- `AppMigration` — `version()` (segment-wise compare), `description()`, `migrate(MigrationContext)`;
  `MigrationContext.execute(sql)`. Runs once per DB, in version order, recorded in history.

## MCP tools (onno-mcp-starter, served at `/mcp`)

`describe_metadata`, `list_catalog`, `get_catalog`, `list_documents`, `get_document`,
`register_balance`, `register_movements`, `posting_preview` (reads); `create_catalog`,
`update_catalog`, `delete_catalog`, `create_document`, `update_document`, `delete_document` (writes,
gated by `onno.mcp.writes-enabled`); `post_document`, `unpost_document` (gated by
`onno.mcp.posting-enabled`). All run as the authenticated user under the same deny-by-default RBAC.
