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
`name` (required), `title=""` (display label for the type; falls back to `name`), `tableName=""`.
Persisted as a reference table; each value gets a stable UUID. Annotate a constant with
`@EnumLabel("…")` to give it a human/localized display label (surfaced in `{col}_display`, the
`enumValues[].label` metadata, and the dropdown) without renaming the constant; unlabelled constants
display as their name. `@EnumLabel(value="…", color="#RRGGBB")` additionally paints the value as a
colored status pill in list cells, the dropdown, and the detail view — the colour rides the read API
as `{col}_color` and `enumValues[].color`; an uncoloured value renders as plain text.

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
| `OnFillingHandler` | `onFilling()` | **Seed defaults here** so the New form opens populated (status, `date`/`period` = now, `quantity = 1`, default counterparty). ⚠️ Runs on **every save of a new entity** (`isNew==true`), not just the blank-form pre-fill — the repository persist path calls it too (`OnnoBeforeConvertCallback`). So make it **idempotent / guard on null** (`if (getDate()==null) setDate(now)`); an unconditional `status = NEW` clobbers a status set by a seeder/import or chosen on the form. For a fixed default that should never be overwritten, prefer a Java field initializer over `onFilling`. |
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
`Q.as(item, alias)`, ordering `Q.asc(ref)/Q.desc(ref)`. Predicate ops:
`EQ,NE,GT,GTE,LT,LTE,BETWEEN,IN,LIKE,IS_NULL,IS_NOT_NULL`. `Row` (the untyped result) has coercing
typed accessors — `getUuid/getBigDecimal/getLong/getInt/getBoolean/getDateTime(col)`, plus `has(col)`,
`columns()`, `asMap()` — so you rarely cast a raw `Object`.

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

## UI DSL (package `su.onno.ui`)

- `Layout` — `profile()` (persona or null=default), `viewport()`, `configure(LayoutSpec)`:
  `spec.section(name).icon(…).catalog(X.class).document(Y.class)`, `spec.shell().nav(NavStyle.SIDEBAR)`,
  `spec.title/theme/priority/roles(...)`, `spec.identity(directoryClass, loginField)`.
- `Page` — `route()`, `profile()`, `viewport()`, `compose(PageBuilder)`: `b.title/subtitle`,
  `b.widget(title)` → `WidgetBuilder.type(…).width(…).document/catalog(…).config(k,v)`, `b.text`,
  `b.list(entity)` (embeds an entity's full interactive list surface — New button, custom actions,
  search/sort), `b.constants()` / `b.constants(heading, names…)`, `b.actions(heading, ActionSpec)`
  (a section of server-handled buttons, reusing the entity `ActionSpec` DSL), `b.custom(type, payload)`.
- `EntityView` (non-generic) — `Class<?> entity()` (names the target catalog/document), `profile()`,
  `list(ListSpec)`, `fields(EntityConfigBuilder)`, `actions(ActionSpec)`, `inputs(InputSpec)`,
  `comments()` (return `true` to opt this catalog/document into the `/api/comments` discussion
  thread; off by default, gated by the global `onno.comments.enabled` switch).
  - `ListSpec`: `title`, `searchable/noSearch`, `sortBy(field, desc)`, `columns(...)`,
    `column(field,label)`, `label(field,label)`, `hide(...)`, `filter(field)` →
    `options/multiOptions(String...)` (value shown verbatim) or `options/multiOptions(Map<value,label>)`
    (value→label split: query matches the value, dropdown shows the label — pass a `LinkedHashMap` for
    order) / `contains` / `startsWith` / `dateRange`; `map()` → `MapSpec` adds a Table⇄Map toggle —
    `field("lat,lng")` or `lat(f).lng(f)` or `geoJson(f)`, `label(f)` (marker popup), `defaultView()`
    (open on the map).
  - `ActionSpec`: `action(key)` → `ActionBuilder.label/icon(String)`, `logo(urlOrStaticPath)` (image
    instead of the lucide icon — e.g. a brand mark), `scope(ActionScope.ROW|TOOLBAR|DETAIL)`,
    `handler(ctx→ActionResult)` or `navigate(url)`. A **row** action may vary per row — `icon(row→String)`,
    `label(row→String)`, `visibleWhen(row→bool)`, `enabledWhen(row→bool)` — taking an `ActionRow`
    (`id()`, `text(col)`, `enumValue(col,Type)`), evaluated as the list renders (#116).
  - `EntityConfigBuilder`: `field(name)` → `FieldHintBuilder`; `icon(name)` (nav icon, any lucide
    name); a **tabular-section column** is addressed with a section-scoped key
    `field("<section>.<field>")` (e.g. `field("items.unitPrice").format("currency:USD")`) — the
    prefix is the `@TabularSection(name=…)`, and it scopes the hint so it can't collide with a
    same-named top-level field; a **register's** resource columns format the same way via an
    `EntityView` whose `entity()` is the register class (no served surface, just the hints);
    `action(name)` → `ActionHintBuilder` places a **detail-header** action (`post`/`unpost`/
    `edit`/`delete` or a custom one) as `.primary()` / `.inMenu()` (overflow ⋯) / `.hidden()` (stays
    on REST), #185; `relatedList(name, joinCatalog)` → `RelatedListBuilder` renders an inline
    related-rows panel on a catalog (the catalog analogue of a document `@TabularSection`) —
    `.via(field)` (Ref scoping rows to the parent, required), `.display(field)` (Ref shown/picked per
    row, required), `.columns(fields…)`, `.label(text)`, `.hideInDetail()`.
  - `FieldHintBuilder`: `order(int)`, `group(String)`, `width(String)`, `widget(String)` (`switch`,
    `textarea`; media: `image`/`photo`/`avatar`/`images`/`gallery`/`photos`/`file` — streamed to
    `POST /api/media`, the attribute stores the returned URL; `map`/`geo`), `placeholder`, `format`
    (`currency:EUR`, `integer`/`decimal`/`percent`, date patterns `dd-MM-yy`/`dd/MM/yyyy HH:mm`, …),
    `hint(String)`, `label(String)`, `refSecondary(targetField)` (shows a second attribute under each
    Ref-picker option to disambiguate same-named records, #185), `hideInList/Form/Detail()`,
    `visibleInList/Form/Detail(bool)`, chain `.field(next)`. `label(String)` localizes a field's
    form/detail/list label — including the built-in system columns (`code`/`description`,
    `number`/`date`/`posted`) that have no other DSL label path (#154), e.g. `.field("posted").label("Статус")`.

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
