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

**Supported attribute field types:** `String`, `int`/`Integer`, `long`/`Long`, `boolean`/`Boolean`,
`double`/`Double`, `float`/`Float`, `BigDecimal`, `UUID`, `LocalDate`, `LocalDateTime`, Java `enum`s
(`@Enumeration`), and `Ref<T>`. **There is no `LocalTime`** (nor `Instant` / `OffsetDateTime` /
`ZonedDateTime`) — schema generation throws `IllegalArgumentException` at startup for any other type.
Model a time-of-day as a `LocalDateTime`, or a `String`/`int` if you only need the clock value.

**Naming rule — entity/attribute `name` must be ASCII / URL-safe.** It becomes the REST route segment
(`/api/catalogs/{name}`), the derived table name, and the access-check key; it is **not** validated, so
a non-ASCII name (e.g. Cyrillic `@Catalog(name="Спектакли")`) compiles but bites at runtime — the SPA
posts the browser's percent-encoded route path, which previously made presence/SSE access checks 403
("not allowed to read catalog: %D1%81…"). Keep `name` ASCII and put the localized label in `title`.

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
colored status pill in list cells and the form dropdown — the colour rides the read API
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

### UI authoring
UI is authored via `Layout`/`Page`/`EntityView` beans, not domain annotations.

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
| `BeforePostHandler` | `beforePost()` | Before posting (validation) — **no Spring DI** (see below). |
| `Postable` | `handlePosting(PostingContext)` | Write register movements. |
| `AfterPostHandler` | `afterPost()` | After post — **no Spring DI**; prefer `@EventListener` on `DocumentPostedEvent`. |
| `BeforeDeleteHandler` | `beforeDelete()` | Before delete. |
| `Validated` | `List<BusinessRule> rules()` | Rules checked before write and before post — **no Spring DI** (see below). |

⚠️ **No Spring DI in any of these hooks.** They run on the domain object, which the framework creates
by reflection (`new`), not as a Spring bean — so `@Autowired` fields are null in `beforeWrite`,
`onFilling`, `beforePost`, `rules()`, `handlePosting`, `afterPost`, etc. Cross-entity work (e.g. a
`rules()` conflict check that queries other documents) needs a static `ApplicationContext` bridge bean
(a `@Component implements ApplicationContextAware` exposing a static accessor) or, for after-the-fact
side effects, an `@EventListener` on the published event (`DocumentPostedEvent`, `EntityChangedEvent`).

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
  `spec.section(name).page(route, label, icon)` adds a sidebar link to an authored `Page` at an
  arbitrary route (the nav peer of `.catalog(...)`) — e.g. a custom dashboard `.page("/ops", "Sales Ops", "activity")`.
  - Branding logo: `spec.shell().logo(url)` or `.logo(lightUrl, darkUrl)`, plus `.logoWidth(px)` /
    `.logoHeight(px)` (or `.logoSize(w, h)`). ⚠️ The sidebar wraps the logo in a **left-aligned**
    (flex-start) box with fixed margins — there is no centering option, so a logo only looks centred if
    `logoWidth` equals the sidebar content width. Size the image to fill it, or bake the padding into
    the asset. Serve logo assets from `classpath:/static/ui/...` (see the static-asset note below).
- `Page` — `route()`, `profile()`, `viewport()`, `compose(PageBuilder)`: `b.title/subtitle`,
  `b.bare()` / `b.header(false)` (drop the title/subtitle row),
  `b.widget(title)` → `WidgetBuilder.type(…).width(…).document/catalog(…).config(k,v)`, `b.text`,
  Grid: widgets flow into rows by `width("1/4"|"1/3"|"1/2"|"2/3"|"full"|any "n/m")` in `order(n)`;
  a row closes when fractions sum to 1. Cells are weights, so a row that doesn't sum to 1 stretches
  its widgets proportionally past their declared width — `rowBreak()` forces the widget to start a
  fresh row instead of joining a half-filled one. Multi-widget rows render equal-height (cards
  stretch to the tallest neighbour). Mobile stacks everything full-width.
  `b.list(entity)` (embeds an entity's full interactive list surface — New button, custom actions,
  search/sort), `b.actions(heading, ActionSpec)`
  (a section of server-handled buttons, reusing the entity `ActionSpec` DSL), `b.custom(type, payload)`,
  `b.row(r -> r.col("2/3", c -> …).col("1/3", c -> …))` (arbitrary column layout — widths are
  fractions / `"<n>px"` / null=equal; a column is a full region so rows nest; columns stack on
  mobile), `b.aside(a -> …)` (shortcut for the common right-rail two-column case).
  - **Everything is a page.** A `Page` is served at *any* route: `/` (home dashboard), `/settings`,
    a default surface route (`/catalogs/{name}`, `/documents/{name}`, or `/registers/{name}` — an
    authored page there **overrides** that surface's default list/report), or an arbitrary custom route (`/ops`, `/reports`,
    via the `GET /api/divkit/{*route}` catch-all). The framework serves a sensible default at every
    surface; a registered `Page` bean at that route replaces it. So dashboards, settings, and list
    pages are one concept — a page — not three subsystems. `profile()`/`viewport()` pick the most
    specific match (like `EntityView`). A custom route only shows in the nav once a `Layout` section
    links it with `.page(route, label, icon)`.
  A widget's `type(…)` may be a **custom widget** the framework has no built-in for: author it as a
  React component in `src/main/widgets/*.tsx` (via `@onno/widget-sdk`) and apply the `su.onno.widgets`
  Gradle plugin — it compiles (Node + esbuild, React aliased to the host), serves under
  `{onno.ui.path}/plugins/**`, and auto-loads at boot; no frontend project. Config `onno.ui.plugins.*`.
  - **Host UI primitives** (≥ host contract v2 / framework 1.5.0): `import { Segmented, Select, Button,
    Badge, DatePicker, … } from "@onno/widget-sdk"` (or `ui.Segmented`) — the app's real controls, not
    lookalikes. Reach for these before hand-rolling.
  - **Styling** (since 1.5.0): the plugin now runs **Tailwind over `src/main/widgets`** and ships
    `onno-widgets.css` (utilities-only, preflight off, host tokens), injected at boot — so a widget's
    own utility classes (incl. `border-l`, arbitrary `-left-[5px]`) get real CSS. Residual caveats:
    only `src/main/widgets` is scanned (class names in imported helpers or built by string
    concatenation aren't seen — keep them literal), and dynamic colors still want inline `style` with
    `hsl(var(--primary))` / `hsl(var(--border))`.
  - **Live updates:** the SDK `api` is read-only (no event subscription). A widget that must react to
    others' writes opens `new EventSource("/api/events")` itself and listens per **named** event
    (`created`/`updated`/`deleted`/`posted`/`unposted`/`changed`) — `onmessage` never fires (events are
    named, not the default `message`), so use `addEventListener("updated", …)`.
- **Static assets** (logos, kiosk/TV pages, fonts) must live under `classpath:/static/ui/…`; they're
  served at the web root. Anything that does NOT resolve to a real file there falls through to the SPA
  `index.html` (HTTP 200, `text/html`) — so a file under a bare `static/` (not `static/ui/`), or a
  mistyped path, silently returns the app shell instead of a 404, and won't render under `nosniff`.
  `/api/**` and `{onno.ui.path}/plugins/**` are exempt. For a custom path or content-type, add a
  dedicated `@GetMapping` (a controller out-precedences the SPA fallback).
- `EntityView` (non-generic) — `Class<?> entity()` (names the target catalog/document), `profile()`,
  `list(ListSpec)`, `fields(EntityConfigBuilder)`, `actions(ActionSpec)`, `inputs(InputSpec)`,
  `comments()` (return `true` to opt this catalog/document into the `/api/comments` discussion
  thread; off by default, gated by the global `onno.comments.enabled` switch).
  - `ListSpec`: `title`, `searchable/noSearch`, `sortBy(field, desc)`, `columns(...)`,
    `column(field,label)`, `label(field,label)`, `hide(...)`, `feed(FeedMode.INFINITE|PAGED)`
    (INFINITE = cursor/keyset scroll, the default; PAGED = numbered offset pages — else inherits
    `onno.ui.list.default-feed`), `pageSize(n)` (rows per window/page; else `onno.ui.list.page-size`),
    `groupable(field…)` (columns for a backend "Group by ▾" picker — a date field buckets by
    day/month/year, a group's rows expand lazily) + `aggregate(field, Agg.SUM|AVG|MIN|MAX[, label])`
    (per-group subtotal on each header) + `defaultGroupBy(field)` (open the list already grouped by
    that column — must also be `groupable`, else ignored with a warning; the viewer can still switch
    back to "None"),
    `filter(field)` →
    `options/multiOptions(String...)` (value shown verbatim), `options/multiOptions(Map<value,label>)`
    (value→label split: query matches the value, dropdown shows the label — pass a `LinkedHashMap` for
    order), or `options/multiOptions(Collection<ListSpec.Option>)` when choices need richer display
    such as `ListSpec.Option.withAvatar(employeeId, name, avatarUrl)`; `.options(...).multiple()` is
    the configurable form of the same multi-select behavior
    as `.multiOptions(...)`; `contains` / `startsWith` / `dateRange`; an **`@Enumeration` field** persists as
    deterministic UUIDs, so the resolver translates its select options — author the constant name
    (`"SHIPPED"`) or its `@EnumLabel` text, or author **no options** (`.multiOptions()`) to offer
    every declared value labelled like the pills; `map()` → `MapSpec` adds a Table⇄Map toggle —
    `field("lat,lng")` or `lat(f).lng(f)` or `geoJson(f)`, `label(f)` (marker popup), `defaultView()`
    (open on the map); `custom(type)` → `CustomSpec` delegates the list **body** to a
    widget-registry component (`registerListRenderer(type, C)` from `@onno/widget-sdk`; props =
    `{rows, list, open, openUrl}`) behind a Table⇄custom toggle — `label(s)` (toggle label, else the
    `list.customView` message), `defaultView()`; the framework keeps search/filters/sort/feed/live
    refresh, and an unregistered type degrades to the default grid (no toggle); `rowStyle(row →
    RowStyle)` = **conditional row formatting** — evaluated server-side per row (same `ActionRow` as
    state-aware actions), returns `ListSpec.RowStyle.DANGER/WARNING/SUCCESS/ACCENT/MUTED` or `null`
    (default look); the grid renders a theme-aware translucent wash (e.g. urgent rows red, cancelled
    faded), also inside expanded groups; a throwing function = `null` for that row.
  - `ActionSpec`: `action(key)` → `ActionBuilder.label/icon(String)`, `logo(urlOrStaticPath)` (image
    instead of the lucide icon — e.g. a brand mark), `scope(ActionScope.ROW|TOOLBAR|DETAIL)`,
    `handler(ctx→ActionResult)` or `navigate(url)`. `roles("MANAGER", …)` restricts who may run the
    action (`ADMIN` always passes; #227): on an entity action it's a finer gate on top of the
    entity's write roles, on a page action (which has no entity to gate on) it's the only
    authorization and the button is hidden from callers who lack it. Server actions honour
    `onno.ui.read-only` (page actions too). `form(f→…)` makes the click open a **modal input
    dialog** first (same DSL as toolbar inputs, plus `.required()` and `InputType.TEXTAREA`); the
    submitted values reach the handler as `ctx.input(key)` — the "Cancel with a reason" idiom. Works
    on every scope (row/toolbar/detail, context menu, batch — a batch collects once and applies to
    every selected row). A form field can be a **reference picker** of another entity via
    `input(key).reference(Target.class)` (`InputType.REFERENCE`; value is the picked record's id),
    and a form can declare a **repeatable row group** `group(key, g→ g.column(col, c→…))` — an
    add/remove tabular grid whose columns reuse the field DSL (incl. `.reference(...)`), read back
    with `ctx.inputRows(key)` → `List<Map<String,String>>` (`.required()` = at least one row).
    Groups are action-form only; a toolbar renders scalars and ignores them (#248). A **row** action may vary per row — `icon(row→String)`,
    `label(row→String)`, `visibleWhen(row→bool)`, `enabledWhen(row→bool)` — taking an `ActionRow`
    (`id()`, `text(col)`, `enumValue(col,Type)`, `bool(col)`), evaluated as the list renders (#116).
    The **same functions apply to a `DETAIL` action**, evaluated against the loaded record as the
    record surface renders (#255) — a header button can hide/relabel/grey itself by the record's
    state (`visibleWhen` false drops it; `enabledWhen` false greys it); two DETAIL actions with
    complementary `visibleWhen` swap a plain button for a form-opening one in a specific state.
    TOOLBAR actions have no record context (fixed icon/label).
    `menu("Change status")` moves a ROW action off the inline row buttons into the row's
    **right-click context menu**, under a submenu with that label (same-label actions group; one
    action per enum value is the idiom). `color("#…")` renders a compact swatch for menu entries
    such as status choices; `logo(url)` renders there too, useful for assignee avatars. The list
    also supports **batch selection** (⌘/Ctrl-click
    toggle, Shift-click range, ⌘A = all loaded rows, ⇧⌘↓/⇧⌘↑ = extend to bottom/top, gated on the
    list being engaged): right-clicking the selection runs any server row action over every
    selected id — as ONE request via `POST /api/actions/{kind}/{name}/{key}/batch` (`{ids,inputs}`,
    ≤500, returns `{ok,failed,total}`) — plus a two-step "Delete N" via
    `POST /api/{kind}/{name}/batch-delete`. **⌘C/⌘V**: copy puts the rows on the clipboard as TSV
    (pasteable into text/spreadsheets) + an app payload; paste (≤50) on the same entity's list
    creates server-side copies via `POST /api/{kind}/{name}/{id}/duplicate` (fresh identity,
    catalog description + " (copy)" [`duplicate.copySuffix`], documents unposted/dated now,
    secrets unset). Forms with unsaved edits confirm "Discard changes?" before the tab closes.
  - `EntityConfigBuilder`: `field(name)` → `FieldHintBuilder`; `icon(name)` (nav icon, any lucide
    name); a **tabular-section column** is addressed with a section-scoped key
    `field("<section>.<field>")` (e.g. `field("items.unitPrice").format("currency:USD")`) — the
    prefix is the `@TabularSection(name=…)`, and it scopes the hint so it can't collide with a
    same-named top-level field; a **register's** resource columns format the same way via an
    `EntityView` whose `entity()` is the register class (no served surface, just the hints);
    `action(name)` → `ActionHintBuilder` places a **record-header** action (`unpost`/`duplicate`/
    `delete` or a custom one) as `.primary()` / `.inMenu()` (overflow ⋯) / `.hidden()` (stays
    on REST), #185 — the record surface is the editable form itself (no separate detail/edit
    pages), so `post` is the form's built-in Post button (`.hidden()` hides it) and there is no
    `edit` action anymore; `relatedList(name, joinCatalog)` → `RelatedListBuilder` renders an inline
    related-rows panel on a catalog (the catalog analogue of a document `@TabularSection`) —
    `.via(field)` (Ref scoping rows to the parent, required), `.display(field)` (Ref shown/picked per
    row, required), `.columns(fields…)`, `.label(text)`, `.hideInDetail()`.
  - `FieldHintBuilder`: `order(int)`, `group(String)` (fields sharing a group render as their own
    card with that heading on the edit form), `width(String)` (`half`/`1/2` = half a row on wide
    screens; else full), `widget(String)` (`switch`,
    `textarea`; media: `image`/`photo`/`avatar`/`images`/`gallery`/`photos`/`file` — streamed to
    `POST /api/media`, the attribute stores the returned URL; `map`/`geo`), `placeholder`, `format`
    (`currency:EUR`, `integer`/`decimal`/`percent`, date patterns `dd-MM-yy`/`dd/MM/yyyy HH:mm`, …),
    `hint(String)`, `label(String)`, `refSecondary(targetField)` (shows a second attribute under each
    Ref-picker option to disambiguate same-named records, #185), `hideInList/Form/Detail()`,
    `visibleInList/Form/Detail(bool)`, chain `.field(next)`. `label(String)` localizes a field's
    form/detail/list label — including the built-in system columns (`code`/`description`,
    `number`/`date`/`posted`) that have no other DSL label path (#154), e.g. `.field("posted").label("Статус")`.
    A `String` attribute with `length` > 1000 (or unbounded) renders as a textarea automatically.
    A Ref picker's pinned "+ New" opens the target's create form in a side pane and, on save,
    auto-selects the new record back into the field (cancel/manual pick drops the hand-off).

An entity surface is only served if it has an `EntityView` for the active profile (no view → `404`);
that is necessary **but not sufficient** for the sidebar. **Nav is curated:** an entity shows in the
sidebar only if a `Layout` section lists it (`spec.section(...).catalog(X.class)`) — a view alone
makes it reachable by direct route but unlisted. No auto-listing of unclaimed catalogs (removed; cf.
#69). Media widgets stream the file to `MediaStorage` and persist the returned URL (see
`docs/MEDIA_UPLOADS.md`).

## Notifications (package `su.onno.ui.notifications`; `onno.notifications.*`)

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
- `@AssigneeField` (on a catalog/document `Ref<>` attribute pointing at the identity catalog) — marks
  the assignee; setting it fires the assignment producer. Notification hint only; no storage/UI effect.

## Events & outbox (packages `su.onno.events`, `su.onno.messaging`)

- `EntityChangedEvent(changeType, entityType, entityName, UUID id, naturalKey)` — published on every
  write through controllers and `repository.save(...)`; drives `/api/events` SSE. Constants:
  changeType `created/updated/deleted/posted/unposted/changed`; entityType `catalog/document/register`.
- `EntityMentionedEvent(comment, mention, actor)` — published per readable `@`-mention in a posted
  comment; consumed by the notifications feature, and by any app `@EventListener` (mail, etc.).
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
