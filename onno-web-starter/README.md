# onno-web-starter

The web layer of an onno application (Java package `su.onno.web`). It exposes the business model over
HTTP with no per-entity controller to write:

- the generic **REST API** for catalogs, documents, accumulation and information registers, settings,
  list feeds, grouping and aggregates;
- **access control** — per-entity roles, `RecordAccessPolicy` record scopes, `EntityAccessPolicy`
  vetoes, read-only mode — on every read and write;
- the endpoints behind the framework's **commands, live checks and picker options**;
- comments, mentions, notifications, presence, tags, media uploads and business-process tasks;
- the **live event stream** (`/api/events`, SSE);
- **serving the UI**: the application's own build or the bundled default shell, plus the `/api/ui`
  model the UI renders from.

The UI itself is not in this module. It is the npm package `@onno/ui` in [`../onno-ui`](../onno-ui)
(see [its README](../onno-ui/README.md)); this module's Gradle build compiles it into the default
shell it ships. An application's own UI (`src/main/ui`) is built by the `su.onno.ui` Gradle plugin
([`onno-ui-gradle-plugin`](../onno-ui-gradle-plugin/README.md)).

## Enabling

```kotlin
dependencies {
    implementation("su.onno:onno-framework-starter:<version>")
    implementation("su.onno:onno-web-starter:<version>")
    implementation("su.onno:onno-auth-starter:<version>")   // sessions, login, CSRF
}
```

Auto-configuration (`UiAutoConfiguration`) kicks in when the starter is on the classpath, a
`MetadataRegistry` bean exists (contributed by `onno-framework-starter`) and `onno.web.enabled` is not
`false`. Every readable entity then has a REST API and a default screen.


## Configuration

Two prefixes, split by what they configure: `onno.web.*` (`WebProperties`) is how the web layer
behaves; `onno.ui.*` (`UiProperties`) is what the UI looks like. Every key, with its full
description, is in [docs/CONFIGURATION.md](../docs/CONFIGURATION.md).

| Key | Default | Purpose |
|-----|---------|---------|
| `onno.web.enabled` | `true` | Master switch for the whole starter. |
| `onno.web.read-only` | `false` | When `true`, every write — create/update/delete, post/unpost, commands, over REST, MCP and starter endpoints — returns `403`, and the model reports no write rights so the UI renders read-only. |
| `onno.web.list.page-size` | `50` | Rows per keyset window when a list does not ask for its own (ceiling 500). |
| `onno.web.batch.parallelism` | `4` | How many records of a multi-record command or delete run in parallel. Keep it below the JDBC pool size. |
| `onno.web.dev-mode` / `onno.web.dev-reload-trigger` | auto / `.onno-reload` | Live reload over `/api/events` (see [docs/RUNNING.md](../docs/RUNNING.md)). |
| `onno.web.update-check.*` | enabled, `24h` | Poll onno-cloud for a newer framework release and show an "update available" notice. Fail-silent; `enabled: false` makes no outbound call. |
| `onno.ui.path` | `/ui` | URL prefix the UI is mounted under; the bare root redirects here. `/` mounts it at the web root. |
| `onno.ui.branding.*` | none | App name, logos, compact mark, favicon and light/dark palette, served by the public `GET /api/branding`. |
| `onno.ui.locale` / `onno.ui.messages.*` | `en` / empty | The chrome language and per-key overrides (see [Localizing the chrome](#localizing-the-chrome)). |
| `onno.ui.theme.*` | empty | Free-form design tokens, served by `GET /api/theme`; each becomes a CSS custom property (`--{key}`). |
| `onno.ui.login.demo-accounts` | none | One-tap demo sign-ins on the login screen (demo only: the passwords reach the browser). |
| `onno.media.*`, `onno.comments.*`, `onno.notifications.*`, `onno.access.records.*` | | Uploads, comment threads, the notification center and record-policy diagnostics (below). |

### Theme tokens

Corner rounding is unified behind three CSS custom properties, so the whole UI reshapes from config:

| Token | Drives | Default |
|-------|--------|---------|
| `--radius-control` | compact actions, chips and badges | `9999px` (pill) |
| `--radius-field` | inputs, selects, search fields, tabs and segmented controls | `0.75rem` |
| `--radius-card` | cards, the list toolbar, popovers, menus and dialogs | `1rem` |

```yaml
onno:
  ui:
    theme:
      "radius-control": "0.5rem"
      "radius-field": "0.625rem"
      "radius-card": "0.75rem"
```

Avatars and status pills stay round regardless.

### Localizing the chrome

The domain is localized where it is declared: entity `title`, `@Attribute(displayName = …)`,
`@EnumLabel`, and the app's own labels in `src/main/ui`. `onno.ui.messages` covers the framework's own
**chrome**: buttons (`New`/`Write`/`Post`/`Delete`…), confirmation dialogs, the login screen,
empty/loading states and client-side validation messages. Each key has one English default
(`su.onno.web.UiMessages.DEFAULTS`); only those keys are read. `onno.ui.locale` picks a built-in
bundle first (onno ships `ru`) and is also the locale the UI formats dates and numbers in. The
resolved map rides on `GET /api/config`. One language per deployment; there is no
`Accept-Language` negotiation.

Quote the dotted keys in YAML (in a `.properties` file use `onno.ui.messages[action.new]=Новый`):

```yaml
onno:
  ui:
    locale: ru
    messages:
      "action.save": "Записать"
      "empty.noRecords": "Нет записей"
```

### Update-available notice

With `onno.web.update-check.enabled` on (the default), `UpdateChecker` polls
`onno.web.update-check.url` on a background thread and compares the announced version with the
running one. The result rides on `GET /api/config` as `update: { available, current, latest, url }`
and the UI shows a dismissible toast. Every failure is swallowed.

## Access model — read this before calling the API

Security is configured by **`onno-auth-starter`** (its `SecurityFilterChain`), not here. The shape
that matters to an integrator:

- **The UI is public; `/api/**` requires an authenticated session.** Everything outside `/api/**`
  (the shell and static assets) is served anonymously so the login screen can load. The public API
  paths are `/api/theme`, `/api/config`, `/api/branding`, `/api/ui/login` (the login screen's
  methods and demo accounts), `/api/auth/login`, `/api/auth/me` and `/api/auth/csrf`.
  Unauthenticated `/api/**` calls get `401` with `{"error":"unauthenticated"}` — not an HTTP Basic
  challenge.
- **Auth is a session cookie, established by a JSON login** (or a bearer JWT in the auth starter's
  `resource-server` mode).
- **Per-entity RBAC is deny-by-default.** A catalog/document/register is invisible and uneditable
  unless its `@AccessControl` read/write roles grant the caller. `ADMIN` bypasses every per-entity
  check. Roles match case-insensitively with the `ROLE_` prefix stripped. Failing a check is `403`.
  Documents may narrow posting with `@AccessControl(postRoles = …, unpostRoles = …)`.
- **Record-level policies narrow further.** A `RecordAccessPolicy` bean scopes which *records* the
  holders of a role see and change — enforced in every list/count/group/aggregate/search/get, every
  write, command, picker, comment/tag/presence/notification surface, SSE delivery and MCP tool. A
  record outside the caller's scope is `404`, like a missing one; a reference to an unreadable record
  reads as restricted (`{field}Restricted: true`, no id). See
  [Record access policies](../docs/RECORD_ACCESS_POLICIES.md).
- **`EntityAccessPolicy` beans** add deny-only vetoes on top (REST, UI, MCP and event visibility).
  `ADMIN` stays exempt unless the policy's `includesAdministrators()` returns `true`; the CRM uses
  that to keep personal inboxes private.
- **Mutating requests need a CSRF token.** POST/PUT/DELETE send the `XSRF-TOKEN` cookie's value back
  in the `X-XSRF-TOKEN` header. `POST /api/auth/login` is exempt so a client can bootstrap a session.

Every query and command service in this module takes an `AccessSubject`. Controller code receives it
as a parameter (`AccessSubject subject`), command handlers from `CommandContext.subject()`, MCP tools
from `McpToolContext.subject()`; only trusted background code passes `AccessSubject.system()`. Typed
repositories are unscoped — custom code serving a user must check
`RecordAccess.require(subject, Type.class, id, AccessMode.READ)`.

### Auth endpoints (from onno-auth-starter)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/auth/login` | Body `{"username","password"}`. Sets the session cookie and returns `{authenticated, username, roles}`; `401` on bad credentials. CSRF-exempt. |
| POST | `/api/auth/logout` | Invalidates the session. |
| GET | `/api/auth/me` | Current user, or `{authenticated:false,...}` when anonymous. |
| GET | `/api/auth/csrf` | This session's CSRF token `{token, headerName, parameterName}`, for clients that cannot read the cookie (native mobile). |

### Logging in to call the API

```bash
# 1. Hit any public endpoint to receive the XSRF-TOKEN cookie.
curl -c jar.txt http://localhost:8080/api/config

# 2. Log in (CSRF-exempt). The response sets the session cookie into the same jar.
curl -b jar.txt -c jar.txt -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin@onnobooks.local","password":"admin"}'

# 3. Reads only need the session cookie.
curl -b jar.txt 'http://localhost:8080/api/list/catalogs/Books?limit=50'

# 4. Writes also need the CSRF header.
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
curl -b jar.txt -X POST http://localhost:8080/api/catalogs/Books \
  -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d '{"description":"The Left Hand of Darkness"}'
```

The browser UI gets this for free: the cookie is sent automatically and `@onno/ui`'s client copies
`XSRF-TOKEN` into the header on writes.

### Providing users

Users come from `onno-auth-starter`. Prefer `mode: database` — accounts live in the application
database and are managed at runtime; the `users` list only seeds the first account, which must set a
new password at first sign-in:

```yaml
onno:
  auth:
    mode: database
    users:
      - username: admin
        password: change-me-on-first-login
        roles: [ADMIN]
```

`@onno/ui` renders account administration with `<UserAccounts />` (ADMIN: create with a one-time
password, roles, disable/enable, reset password, sign out everywhere) and the holder's own password
change with `<ChangePassword />`. A session holding a one-time password sees only the
change-password screen until it is replaced.

## REST API

Entity-scoped paths take a `{name}` that is the entity's **logical name**, not its Java class: it is
matched against the descriptor's `logicalName()` with spaces and underscores stripped and
lower-cased. `@Catalog(name = "Stock Receipts")` is `/api/catalogs/Stock Receipts`,
`/api/catalogs/stock_receipts` or `/api/catalogs/stockreceipts` — never `StockReceipt`. An unknown
name is `404`.

Responses use the logical camelCase vocabulary (`id`, `description`, `taxId`, `customerDisplay`, …)
and writes accept the same names, so values round-trip. `?representation=storage` returns storage
column names; writes accept both, rejecting unequal duplicates with `400`. The exact JSON is
specified in [docs/HEADLESS_READ_API.md](../docs/HEADLESS_READ_API.md).

### Catalogs — `/api/catalogs`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}/{id}` | One record. |
| GET | `/{name}/children?parent={uuid}` | Direct children of a hierarchical catalog (`400` otherwise); omit `parent` for roots. `folders=true` returns only folders, each with `hasChildFolders`. |
| GET | `/{name}/tree` | Full nested tree (hierarchical only). `ids=a,b` returns only the paths from the roots to those records. |
| POST | `/{name}` | Create. Body: `code`/`description`/`folder`/`parent` plus attribute fields; the code is generated when omitted. |
| PUT | `/{name}/{id}` | Partial update. Send `version` for optimistic locking — a stale version is `409`. |
| POST | `/{name}/{id}/duplicate` | Server-side copy with a fresh id and code; secret attributes start unset. |
| POST | `/{name}/batch-delete` | Soft-delete `{ids: […]}` (≤500); per-id failures don't abort; returns `{ok, failed, total}`. |
| DELETE | `/{name}/{id}` | Soft delete (sets the deletion mark). `BeforeDeleteHandler.beforeDelete()` runs first and may veto. |
| POST | `/{name}/validate`, `/{name}/{id}/validate` | Dry run of the write path (hooks, rules) on unsaved values; nothing persists. |

### Documents — `/api/documents`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}/{id}` | One document with its tabular sections inlined. |
| POST | `/{name}` | Create. Header fields plus tabular-section arrays keyed by section name; the number is generated when omitted. |
| PUT | `/{name}/{id}` | Partial update; supplied tabular sections are replaced wholesale. `version` for optimistic locking. |
| POST | `/{name}/{id}/post` | Post a draft, or atomically reverse and recalculate a posted one. Needs write access and, when declared, one of the `postRoles`. |
| POST | `/{name}/{id}/unpost` | Reverse a posted document. Needs the `unpostRoles` when declared; `BeforeUnpostHandler` may veto. |
| GET | `/{name}/{id}/posting-preview` | The movements a post would write, without writing them. |
| POST | `/{name}/{id}/duplicate` | Server-side copy: attributes and lines, fresh id and number, dated now, unposted. |
| POST | `/{name}/batch-delete` | Soft-delete `{ids: […]}` (≤500); posted ones fail (unpost first). |
| DELETE | `/{name}/{id}` | Soft delete (`BeforeDeleteHandler`, no `beforeWrite`/validation); a posted document answers `409` — unpost it first. |
| POST | `/{name}/validate`, `/{name}/{id}/validate` | Dry run of the write path; nothing persists. |

### Accumulation registers — `/api/registers`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}/movements?from=&to=&where=` | Movements, newest first. `where` over dimensions, resources, `period`, `recorder`, `movementType`. |
| GET | `/{name}/balance?asOf=&where=` | Current (or as-of) balances of a `BALANCE` register. |
| GET | `/{name}/turnover?from=&to=&where=` | Turnover between two dates; `from` and `to` are required. |

### Information registers — `/api/information-registers`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}/records?from=&to=&where=&cursor=&limit=` | Stored records, newest first, in the `{rows, nextCursor, hasMore}` envelope. |
| GET | `/{name}/slice-last?at=&where=` | Per dimension tuple, the record in force at `at` (default now). |

### Settings — `/api/settings`

`GET` lists every `@Constant` as `{name, type, value}`; `PUT` with `{ "<constant name>": value, … }`
saves values. Administrator-only.

### Which records: `where`

Every read that selects records — list feeds, groups, aggregates, register and information-register
reads, reference-picker search — takes `where`, a structured JSON predicate (`Where`) compiled into
bound SQL. Field names are logical names; values are compared typed (references and enum values by
UUID — an enum also accepts its constant name — dates ISO):

```json
{ "posted": true, "total": { "gte": 100 }, "status": { "in": ["NEW", "CONFIRMED"] },
  "or": [{ "customer": "3f2…" }, { "note": { "contains": "rush" } }] }
```

Operators: equality (a value), `null`, `eq`, `not`, `in`, `notIn`, `gt`, `gte`, `lt`, `lte`,
`contains`, `startsWith`, combined with `and`, `or`, `not`. URL-encode it as a query parameter
(`?where=%7B%22posted%22%3Atrue%7D`). `@onno/ui` types it per entity from the generated model.

### List feeds — `/api/list`

Lists are fed window by window so a 100k-row entity never ships whole. Paging is **keyset (seek)**:
indexed, constant-time at any depth, and immune to rows shifting mid-scroll.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/list/catalogs/{name}?cursor=&limit=&sort=&dir=&q=&where=` | One window. Omit `cursor` for the first; pass back `nextCursor` for the next. `focus=<id>` returns the window containing that record; `before=<prevCursor>` the window above. Envelope `{ rows, nextCursor, hasMore, prevCursor, hasPrevious, focus? }`. |
| GET | `/api/list/documents/{name}?…&from=&to=` | The same, plus a date range; newest first by default. |
| GET | `/api/list/{kind}/{name}/groups?groupBy=&granularity=&q=&where=&agg=fn,field` | One header per value of `groupBy` (a date field per `day`/`month`/`year` bucket) over the same rows as the flat list: `{ groups: [{ label, color?, count, values[], expand[] }], capped }`. Each header's `expand` is the predicate that loads its rows from the flat feed. Capped at 200 headers. |
| GET | `/api/list/{kind}/{name}/aggregate?metric=&field=&groupBy=&groupByDate=&seriesBy=&where=&dateField=&from=&to=` | A measure (`count`/`sum`/`avg`/`min`/`max`) grouped on the server: `{ buckets: [{ key, label?, series?, seriesLabel?, value }], truncated }`. Date-bucketed axes zero-fill empty periods; capped at 1000 buckets. Backs `Stat`, `Chart`, `useAggregate`. |
| GET | `/api/list/registers/{name}/movements`, `/balance`, `/aggregate` | The same feeds over a register (`{rows, nextCursor, hasMore, total}`), with `from`/`to`, `where` and resource ranges. |

Besides `where`, the feeds accept the list toolbar's facet parameters (`eq`, `in`, `like`, `prefix`,
`ge`, `le`, each `field,value`), validated against the entity's columns.

- **No COUNT by default.** `hasMore` is what a scroller needs. `?count=exact` adds a precise total;
  `?count=estimate` a cheap PostgreSQL planner figure.
- **The cursor is opaque.** It encodes the sort and the last row's position; replayed against a
  different sort it is ignored (paging restarts).
- **Focus window.** `?focus=<id>` seeks to the record; a record the query cannot see yields the plain
  first window without `focus`, so the feed never reveals it.
- **Indexes.** The schema engine emits composite `(_code, _id)` / `(_description, _id)` / `(_date,
  _id)` indexes for the seek, and on PostgreSQL trigram indexes on `_code`/`_description`/`_number`
  and on `@Attribute(searchIndex = true)` columns for `q=` (`onno.schema.search-indexes=off`
  disables them).
- `@Attribute(secret = true)` fields are write-only and never valid filter, sort, group or aggregate
  columns.

## The UI model and default screens — `/api/ui`

The server describes what exists and what the caller may do; it never describes screens.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/ui/model` | Every catalog, document and register the caller can read (`kind`, `logicalName`, `title`, `context`, `route`, `canWrite`, posting rights), the readable information registers, the `@Constant` settings (administrators only), and the signed-in user with roles. The UI builds navigation and permissions from it. |
| GET | `/api/ui/view/{catalogs\|documents}/{name}` | The entity's default list descriptor: columns, filters, the commands it offers and selection downloads. |
| GET | `/api/ui/view/{catalogs\|documents}/{name}/{id}`, `/new`, `/{id}/duplicate` | The record form descriptor: field metadata (names, types, references, constraints), the record, the record's actions and their state, `canPost`. |
| GET | `/api/ui/view/registers/{name}` | The register report descriptor. |
| GET | `/api/ui/record-refresh/{kind}/{name}/{id}` | The saved record and its action state, re-read after a command. |
| GET | `/api/ui/pickers/{kind}/{name}` | The list descriptor behind a reference field's "Show all…" dialog. |
| GET | `/api/branding`, `/api/config`, `/api/theme` | Branding (public), read-only mode + locale + chrome strings + update notice, theme tokens. |

Descriptors carry data only — no icons, widths, widgets or English titles. Presentation is the React
client's, customized by the application in `src/main/ui` (see [onno-ui](../onno-ui/README.md)).

## Commands, checks and picker options

Entity behaviour beyond CRUD is declared in Java, in `onno-framework` packages, and served here.

**Commands** (`su.onno.command`) — a `Commands<E>` bean declares what can be done with an entity's
records. Every command is exposed on the REST API below, in the UI (row buttons, header buttons,
menus — placed by the app's `commands` customization) and as an MCP tool (`onno-mcp-starter`). The
server enforces entity write access, the command's `roles`, `onno.web.read-only`, record scope and
the per-record `availableWhen`/`enabledWhen` on every surface.

```java
@Component
class OrderCommands implements Commands<Order> {
    public Class<Order> entity() { return Order.class; }

    public void define(CommandSpec<Order> c) {
        c.command("cancel").title("Cancel order")
                .description("Cancels the order and records the reason in its note.")
                .availableWhen(row -> row.enumValue(Order::getStatus, OrderStatus.class) != OrderStatus.CANCELLED)
                .input("reason", i -> i.title("Reason").longText().required())
                .run(ctx -> cancel(ctx.id(), ctx.input("reason")));

        c.collectionCommand("bulkReceive").title("Quick receive")
                .inputRows("lines", g -> g.title("Lines").required()
                        .column("book", col -> col.title("Book").reference(Book.class).required())
                        .column("qty", col -> col.title("Qty").number().required()))
                .run(ctx -> receive(ctx.inputRows("lines")));
    }
}
```

`.group("Change status")` gathers commands into a submenu; `.title(row -> …)` titles per record;
`c.dynamic(live -> …)` declares commands from live data (one "Assign" entry per employee). A handler
returns `CommandResult.ok()`, `reload()`, or `success|info|warning|error(message)` with optional
`.title(...)`, `.detail(...)` and `.andRefresh()` — meaning only; the UI decides toast or dialog.
`throw new CommandRejectedException(...)` answers a purposeful `422`, shown inline in an open input
form.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/commands/{kind}/{name}[?id=]` | `{commands: [descriptor…], state: {key: {visible, enabled, title?}}}` — the state evaluated for that record. |
| POST | `/api/commands/{kind}/{name}/{key}` | Body `{id?, inputs: {key: value \| rows[]}}` → `{refresh, feedback}`. |
| GET | `/api/commands/{kind}/{name}/{key}/defaults[?id=]` | Pre-filled input values `{values, rows}`. |
| POST | `/api/commands/{kind}/{name}/{key}/batch` | Body `{ids: […], inputs}` → `{ok, failed, total[, feedback]}`; each id in its own transaction. |

**Live checks** (`su.onno.validation`) — a `Checks<E>` bean declares advisory checks that run as the
user types; the save-time `Validated` rules stay authoritative:

```java
v.check("schedule-conflicts", this::conflicts)
        .dependsOn(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
        .debounce(Duration.ofMillis(200));
```

`POST /api/checks/{kind}/{name}/{key}` with `{"id": uuid?, "values": {...}}` answers
`[{severity, field, message}]`. The caller must be able to write the entity (and the record).

**Picker options** (`su.onno.picker`) — a `PickerOptions<E>` bean bound to one reference field
(`entity()` + `field()`, `"participants.employee"` for a line column) decorates the candidates from
the live form values: `PickerOption.badge(label, tone)`, `disabled(label, tone, reason)` or
`filteredOut()`.

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/ref-options/search` | Typeahead over a reference target: `{targetKind, targetName, query, limit, where?, ownerKind, ownerName, fieldPath, formValues, …}`. An empty query leads with the caller's recent picks (`_recent: true`). Every option obeys the target's deletion mark, record scope and `where`, then the field's `PickerOptions`. |
| POST | `/api/ref-options/recent` | Remember a pick `{targetKind, targetName, id}`. |
| POST | `/api/ref-options/decorate` | Which ids of a "Show all…" dialog window the picker offers, with their decorations. |

**Identity** (`su.onno.identity`) — an `IdentityLink.of(Employee.class, "email")` bean links a login
to its record in a catalog: the UI greets the person and shows their avatar, comments and
notifications are attributed to it, process tasks are routed to it, and record policies read it as
`Subject.recordId()`.

**Module contributions.** A `RecordActionContributor` bean adds read-only actions to record forms
(`RecordAction(key, title, url)`; `onno-print-starter` uses it for its print menu), and a
`SelectionDownloadContributor` bean adds selection downloads to lists (POSTs `{"ids":[…]}` and saves
the attachment). Both must filter by the request's `AccessSubject`; the UI places them by `key` like
any command.

## Business processes and tasks

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/process-definitions` | Definitions with version and typed graph descriptor. |
| GET / POST | `/api/processes`, `/api/processes/{definitionKey}` | Instances visible to the caller; start one (the body is the payload, authorized by `startAssignment`). |
| GET | `/api/processes/{instanceId}[/history\|/executions]` | One instance (ADMIN, starter, candidate/assignee or a recorded actor). |
| POST | `/api/processes/{instanceId}/cancel`, `/migrate` | `{"reason":"…"}`; migration follows registered typed migrations. |
| GET | `/api/tasks`, `/api/task-assignees` | The caller's work items; delegation candidates from the `IdentityLink` catalog. |
| POST | `/api/tasks/{id}/claim`, `/delegate`, `/complete` | `delegate {"targetActorId","reason"}`, `complete {"outcome":"ENUM_CONSTANT"}`. |
| GET | `/api/tasks/{id}/history` | The task's audit trail. |

Visibility comes from `HumanTask.assignment(payload)` on the server; the browser cannot nominate its
own actor or roles. Every committed change emits an audience-scoped `tasks-changed` event, so
`@onno/ui`'s `<TaskInbox />` refetches by itself.

## Collaboration

### Comments — `/api/comments`

A record can carry a **discussion thread** with replies, reactions, `@` mentions and `#` document
references. Comments live in the framework-owned `onno_comments` table, so no entity needs modelling.
The UI shows the thread under a record form when the entity's customization sets `comments: true`.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/comments/{kind}/{name}/{id}` | The thread, oldest first; each comment has `parentId`, live-resolved `mentions` and grouped `reactions`. |
| POST | `/api/comments/{kind}/{name}/{id}` | `{ "body": "…", "parentId": "…"? }`. The author is the session's user. |
| POST | `/api/comments/{commentId}/reactions` | Toggle `{ "emoji": "👍" }` (`👍 ❤️ 🎉 👀 ✅`). |
| DELETE | `/api/comments/{commentId}` | Soft delete; author or `ADMIN`, with current read access to the record. |
| GET | `/api/mentions?q=&kind=people\|catalogs\|documents` | Mention typeahead: `people` (the `IdentityLink` catalog) for `@`, any record for `#`. `GET /api/mentions/resolve` resolves one record. |

Reading and posting need read access to the record. A mention is stored as a token in the body
(`@[Display](kind/name/id)`); one the author cannot read is stripped on post, one the viewer cannot
read renders as plain text. Each readable `@` mention publishes an `EntityMentionedEvent`.
`onno.comments.enabled=false` removes the feature; `onno.comments.max-length` caps bodies (4000).

### Notifications — `/api/notifications`

A per-user notification center in the framework-owned `onno_notifications` table, pushed live as a
`notification` SSE event to the recipient.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/notifications[?unread&cursor]` | The caller's timeline: `{ items, nextCursor, hasMore, unreadCount }`. |
| POST | `/api/notifications/{id}/read`, `/read-all` | Mark read. |

Raise one from any bean:

```java
notificationService.notify(NotificationRequest.to(recipientId)   // the identity record id
        .type("approval")
        .title("Your purchase was approved")
        .link("documents/purchase_orders/" + id)
        .build());
```

Built in: comment mentions (`onno.notifications.mentions.enabled`) and assignments — setting a
`Ref` attribute annotated `@AssigneeField` notifies the assignee
(`onno.notifications.assignments.enabled`). `onno.notifications.retention-days` (90) prunes read
notifications.

### Presence — `/api/presence`

Who else is viewing what. The UI `POST`s `{ "path": "<route>", "action": "enter" | "heartbeat" |
"leave" }` to `/api/presence` for a record, a list or any page (entity routes need read access);
`GET /api/presence` returns the snapshot of routes the caller may see. Viewers expire after ~45s without a heartbeat and changes stream as `presence` events,
relayed across nodes by the `ClusterEventBus`. Best effort: a failed heartbeat shows no error.

### Tags — `/api/tags/{kind}/{name}`

Stable tag definitions and record assignments (`onno_tags`, `onno_tag_links`), scoped per entity.
`GET` lists the library, `POST {name, color}` creates a tag (write access to the entity),
`GET /{id}` lists a record's tags, `POST`/`DELETE /{id}/{tag}` assign and remove. `@onno/ui`'s
`<EntityTags kind name id />` renders them. A `TagAccessPolicy` bean adds record-level checks; a
`TagCatalog` bean binds the library to an ordinary application catalog.

### Media — `/api/media`

`POST /api/media` (multipart) stores an upload and returns its URL; `GET /api/media/{key}` serves it;
`GET /api/media/limits` reports the size and type limits. Image, color and file fields use it. The
default `MediaStorage` writes to `onno.media.filesystem.directory`, keeping the original filename and
type in private sidecars. See [docs/MEDIA_UPLOADS.md](../docs/MEDIA_UPLOADS.md).

## Live updates — `/api/events`

`GET /api/events` is a Server-Sent Events stream: entity changes (create, update, delete, post,
unpost, register changes), `presence`, `notification`, `tasks-changed`, comment events and, in dev
mode, `reload`. Every event is filtered to what the subscriber may read. Repository and command
events reach subscribers **after the publishing transaction commits**, so a refetch sees the
committed row and a rollback emits nothing. On a Postgres datasource, events cross nodes over the
`ClusterEventBus` (`onno-framework-starter`).

`@onno/ui` opens one connection per browser origin (shared across tabs) and turns each change into a
query-cache invalidation, so every list, form, chart and `useRecords` over that entity refreshes.

## Serving the UI

- **The application's own UI.** With the `su.onno.ui` Gradle plugin, `src/main/ui` is built into
  `classpath:/onno-app/`. When `onno-app/index.html` exists it is served instead of the default
  shell, and its assets resolve first.
- **The default shell.** Otherwise the starter serves the `@onno/ui` shell bundled in its jar at
  `classpath:/static/ui/`: a list and form for every readable entity, navigation grouped by context.
- **Base path.** The UI is mounted under `onno.ui.path` (default `/ui`). The served `index.html` has
  the prefix baked in, so the client uses it as its router `basename`; the bare root redirects to
  it (or serves the shell directly when the path is `/`). Assets are served from the web root and
  load at any depth.
- **Index fallback is navigation-only.** Only `GET` requests under the mount whose `Accept` includes
  `text/html` fall back to `index.html`, so deep links cold-load while missing assets and unknown
  `/api/**` routes stay `404`. Application controllers and Spring Boot's static locations keep
  working outside the mount.

> There is no anonymous metadata/manifest endpoint. To introspect the model at runtime, call
> `/api/ui/model` with a session, use the generated endpoints above, or the MCP `describe_metadata`
> tool from `onno-mcp-starter`.

## Building this module

`buildFrontend` builds `../onno-ui` (one `npm install` at the repository root serves the whole npm
workspace) and `processResources` copies `onno-ui/dist` into `static/ui/`; neither is committed.
`typecheckFrontend` runs `tsc -b` over the package, its Vitest suite and the type-level tests of the
public API; `testFrontend` runs Vitest and is part of `check`.

```bash
./gradlew :onno-web-starter:testFrontend
./gradlew :onno-web-starter:buildFrontend
./gradlew :onno-web-starter:test
```

### Standard information-register screens

Readable information registers have a read-only `/information-registers/{name}` surface.
`GET /api/ui/view/information-registers/{name}` supplies the descriptor and
`GET /api/list/information-registers/{name}` supplies sortable, filtered keyset windows with optional
exact count. Periodic registers support `at` for a paged latest-values slice. Role and dimension
record policies apply on every request. See [the read contract](../docs/HEADLESS_READ_API.md).
