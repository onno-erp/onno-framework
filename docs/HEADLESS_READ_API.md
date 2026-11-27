# Headless Read API

The generic REST API under `/api/**` (served by `onno-web-starter`) gives catalog and document
consumers a logical JSON contract: system fields and model attributes use the same names on reads
and writes (`id`, `description`, `taxId`, …), references expand alongside them, and secrets are
redacted. The previous storage-shaped contract remains available with
`?representation=storage`. This document is the response contract for headless consumers (a
separate front end, a sync job, a search indexer) so you don't have to learn the shape by reading
controller source (issues #33 and #314).

It pairs with the auth/CSRF notes in [AGENTS.md](../AGENTS.md#inspecting-a-running-app-read-this-before-you-curl):
every `/api/**` route is authenticated, reads need only the session cookie (or a bearer token in
`resource-server` mode), and `{name}` is the entity's annotation **logical name** (e.g. `Books`,
`SalesOrders`) — not the Java class name or localized `title`. Unknown `/api/**` routes return
`404`; they are never converted into the SPA shell.

## Endpoints

Historical register balances: `register_balance` accepts optional `asOf`: an inclusive
ISO-8601 local date-time (`2024-03-31T18:00`) or a date (`2024-03-31`, the whole day); offsets are rejected with `400`. REST: `GET /api/registers/{name}/balance?asOf=2024-03-31T23:59:59`.
With `asOf`, BALANCE registers sum active movements through the cutoff (receipts minus expenses),
grouped by dimensions; record access policies apply before aggregation and `where` then selects
balance rows. Without `asOf`, reads keep the fast materialized totals path. The 5,000-row limit still applies.

Accumulation-register reads return the logical vocabulary: dimension/resource field names with their
reference sidecars (`bookDisplay`, `bookRef`, …), plus `id`, `period`, `recorder` (the posting
document's id) and `movementType` (`RECEIPT`/`EXPENSE`) on movement rows. Each takes an optional
`where` — the same structured JSON predicate as the list feed — whose keys are the dimension and
resource field names plus `period`, `recorder` and `movementType` for movements and turnover (it
selects the movements read or summed), and the dimension and resource field names for a balance (it
selects balance rows; a resource compares the balance). An unknown key is a `400`.
`GET /api/list/registers/{name}/aggregate` is the register counterpart of the catalog/document
`/aggregate` read (same parameters and envelope): it aggregates movements, sums resources signed
(receipts add, expenses subtract — a sum is the turnover), counts movements for `count`, and
defaults `dateField` to `period`.

```text
GET /api/catalogs/{name}/{id}            one catalog row
GET /api/catalogs/{name}/children?parent={uuid}   hierarchical children (hierarchical catalogs); folders=true: folders only, with hasChildFolders
GET /api/catalogs/{name}/tree            paged flat nodes with parent links; ids=a,b: bounded nested paths
GET /api/documents/{name}/{id}           one document, with tabular sections inlined
GET /api/list/catalogs/{name}?cursor=&limit=       one keyset-paginated collection window
GET /api/list/documents/{name}?cursor=&limit=&from=&to=  one document window
POST /api/ref-options/search                  bounded contextual reference options
POST /api/ref-options/recent                  remember a picked reference value (204, CSRF)
POST /api/ref-options/decorate                which of a picker window's ids the dropdown would offer (CSRF)
GET /api/ui/pickers/{kind}/{name}             list descriptor for the "Show all..." picker dialog
GET /api/ui/record-refresh/{kind}/{name}/{id} saved record and current record actions for the open form
GET /api/history/{kind}/{name}/{id}?cursor=&limit=  field-level change history (entities with history = true)
GET /api/registers/{name}/movements?from=&to=&where=&cursor=&limit=    {rows, nextCursor, hasMore}
GET /api/registers/{name}/balance?asOf=&where=&cursor=&limit=&{dimension}=
GET /api/registers/{name}/turnover?from=&to=&where=&cursor=&limit=&{dimension}=
GET /api/list/registers/{name}/aggregate?metric=&field=&groupBy=&groupByDate=&seriesBy=&where=&dateField=&from=&to=
GET /api/information-registers/{name}/records?from=&to=&where=&cursor=&limit=
GET /api/information-registers/{name}/slice-last?at=&where=
GET /api/process-definitions                 latest versions plus typed graph descriptors
GET /api/processes                           caller-visible process instances
POST /api/processes/{definitionKey}          start; body is the definition's typed payload as JSON
GET /api/processes/{instanceId}              durable process snapshot
GET /api/processes/{instanceId}/history      append-only transition audit trail
GET /api/processes/{instanceId}/executions   execution tokens, waits, timers, and child links
POST /api/processes/{instanceId}/cancel      body: {"reason":"…"}
POST /api/processes/{instanceId}/migrate     migrate through registered definition versions
GET /api/tasks                               caller's candidate/assigned open work
POST /api/tasks/{workItemId}/claim
GET /api/tasks/{workItemId}/history       ordered task audit trail
POST /api/tasks/{workItemId}/delegate     body: {"targetActorId":"identity-record-uuid","reason":"…"}
POST /api/tasks/{workItemId}/complete        body: {"outcome":"ENUM_CONSTANT"}
```

Information-register records return one keyset window of stored rows, including periodic
history, in the same envelope as the catalog/document feeds: `{rows, nextCursor, hasMore}`.
Use `from` / `to` as optional inclusive ISO local date-times (for example
`2024-03-31T23:59:59`); the API reads stored periods without changing bucket granularity.
Narrow with `where`, the structured JSON predicate, for example
`?where={"customer":"<uuid>","region":"North"}`; its keys are the dimension, resource and
attribute field names plus `id` and `period`. Reference and enumeration values use their stored
UUIDs. Secret attributes are never filterable; an unknown field, and any other query parameter, is
rejected with `400` instead of being ignored.

`slice-last` returns the slice of last values as a plain list: for every dimension tuple, the record
in force `at` an inclusive ISO local date-time (default now) — the current price per product, the
rate on a date. A non-periodic register has one record per tuple, so its slice is its records (and
`at` is a `400`). `where` selects among the slice's rows (a resource compares the value in force).
Record policies apply; at most 5,000 rows, ordered by the dimensions.

`limit` defaults to 100 and is clamped to 1..500. Pass the previous `nextCursor` back as `cursor`
for the next window; `nextCursor` is `null` (and `hasMore` false) on the last one. The cursor is
sealed to the caller's record access, so a cursor issued to another user is rejected with `400`.
Rows are ordered by period descending, then ID descending to break ties; nonperiodic (`NONE`)
registers order by ID descending and reject `from` / `to` with `400`.
Invalid dates and reversed ranges also return `400`.

The response uses logical names: `id`, `period` (periodic registers only), dimension/resource/
attribute field names, and reference `*Display` / `*Ref` sidecars. `@AccessControl` gates the route
(`403` without a read grant); record policies filter rows before pagination, and restricted references
are masked by the common resolver. Secret attributes carry the common redacted sentinel.
Unknown register names return `404`. This is a read-only route; it does not expose SQL or writes.

The optional `onno-crm-starter` adds a focused command surface for its inbox (`@onno/crm`):

```text
GET  /api/crm/conversations/{id}/delivery       response: {"connected":true,"label":"Telegram @bot","maxTextLength":4096}
POST /api/crm/conversations/{id}/messages/{messageId}/retry
GET  /api/crm/conversations/{id}/messages
POST /api/crm/conversations/{id}/messages       body: {"body":"…"}  (email may add "html":"…", its formatted version)
POST /api/crm/conversations/{id}/read
```

Disconnected replies and retries of non-failed or foreign-conversation messages return 422.
Delivery enqueues in the same transaction as the CRM message through `CrmMessageTransport`.
A successful provider send is `SENT`; it does not imply a delivery/read receipt.

These routes require a permitted host `CrmInboxWorkspace`, `?workspace=<key>`, and host customer read access. Writes also require workspace write permission.
Business actions are host-authored as ordinary `Commands<Conversation>` commands. See
[onno-crm-starter/README.md](../onno-crm-starter/README.md) for the module integration contract.

Process actors are always derived from the authenticated principal; usernames and roles are never
accepted from the request body. Starting is authorized by the definition's typed
`startAssignment(payload)`. `GET /api/tasks` returns open candidate work plus work already
claimed by the caller. `ADMIN` is the process superuser. Completion outcome names are checked
against the active `HumanTask`'s declared enum before the persisted graph advances. Process
mutations use the same CSRF requirements as other session-authenticated `/api/**` writes.
Only a claimed task's current assignee (or `ADMIN`) may delegate it; a nonblank reason is required
and the transfer is durable. `GET /api/task-assignees?q=` searches the `IdentityLink` identity
catalog and returns `{actorId, username, display, avatarUrl}`. `actorId` is the stable catalog record
UUID; username/display/avatar are live presentation data. `avatarUrl` is present when the identity
catalog has a photo attribute (a `String` named `avatar`, `avatarUrl`, `photo`, `photoUrl`,
`picture`, `image` or `imageUrl`).

Definition responses include `{key,title,version,payloadType,graph}`. `graph.startStepKey` and each
node's `{stepKey,type,routes}` are safe structural metadata; payload-dependent assignments are not
exposed. Instance snapshots retain `currentStep` as compatibility sugar, but `activeSteps` is the
authority when parallel branches exist. They also carry `definitionVersion`, root/parent links,
completion/cancellation timestamps, and the required cancellation reason. Execution rows identify
the durable token, parent branch token, node type/status, due time, and child instance.

Cancellation is authorized by the definition's typed `cancellationAssignment(payload)` (plus the
starter and `ADMIN`); it cancels descendants and all live work. Migration is explicit: the
application must keep the stored and target definition versions registered and supply a
`ProcessDefinitionMigration` that maps every active token. Missing versions, migration edges, or
token mappings fail atomically.

```jsonc
// GET /api/tasks
{"rows":[{
  "id": "2d95…",
  "instanceId": "a4f1…",
  "definitionKey": "order-approval",
  "stepKey": "review",
  "title": "Review order O-42",
  "status": "OPEN",
  "assigneeId": null,
  "assignee": null,
  "subject": {"kind": "documents", "entityName": "Orders", "id": "f34c…"},
  "outcomes": ["APPROVE", "REJECT"]
}], "nextCursor": null, "hasMore": false}
```

For a mounted record form, `GET /api/ui/record-refresh/{kind}/{name}/{id}` returns
`{record, actions, commands, readOnly[, canPost]}` under the same access rules as the record view.
`record` uses the logical representation; `actions` are the record actions the viewer may perform
now, data only — `{key, url[, title][, danger]}` (`unpost`, `duplicate`, `delete`, `openInList`, or a
module's key such as `print-invoice` with its `title`); the client names, icons and places them.

Command handlers answer `{refresh, feedback, navigateTo}` (`CommandResult.success(...)`,
`.info|warning|error`, `.title(...)`, `.detail(...)`, `.andRefresh()`). Feedback is meaning only:
`severity` (`info|success|warning|error`), an optional `title`, `message` and `details`. The client
decides how to show it — short success/info/warning as a toast, errors and anything with details as
a dialog. `navigateTo` is optional and carries a destination for a single successful command:
`CommandResult.open(ref)` returns the referenced catalog/document's route and
`CommandResult.navigate(route)` / `.andNavigate(route)` choose a page or record. Only navigation
targets are accepted — an application path or `onno://` /
`onno://{catalogs|documents|registers|information-registers}/{name}[/{id}]`; command URLs (`logout`,
`delete/`, `action/`, `redirect/`, `open/`, `download/`) and external URLs are rejected by
`CommandResult` and ignored by the client, so a route built from stored or user data cannot trigger
a side effect or leave the app. Clients apply feedback and refresh before navigating; the
destination's normal access checks still apply. Batch responses keep their count/feedback summary
without per-record navigation.
An expected `CommandRejectedException` is HTTP 422 whose body is the feedback object itself, plus
the inputs to fix; a client with the input form open shows it there and keeps the form open:

```json
{
  "severity": "error",
  "title": "Approval blocked",
  "message": "The room is occupied",
  "details": ["Main Stage · Onegin · 18:00–21:00"],
  "fieldErrors": { "reason": ["Only soft conflicts may be justified"] },
  "formErrors": ["A justification cannot override a hard conflict"]
}
```

### Collection pagination

This section is the authoritative collection-read contract for REST and MCP.

**Request.** Omit `cursor` for the first page. `limit` defaults to **100** and is capped at
**500**; clients should send a positive integer. Replay the previous response's `nextCursor`
without decoding it. Keep the feed, effective filters, sort and authenticated access context
unchanged while continuing. HTTP helpers normalize non-positive internal limits to the default;
MCP's schema validates page-size bounds explicitly.

**Response.** Every collection feed below returns `{rows, nextCursor, hasMore}`. `rows` has at
most `limit` entries; `nextCursor` is null exactly when `hasMore` is false. Additional metadata
(such as workspace configuration or notification badge counts) does not replace this envelope.
No collection total is computed implicitly. Catalog/document feeds support explicit
`count=exact|estimate`; register list and CRM inbox feeds support `count=exact`. Badge counts
are a separate feature and apply the same record visibility as the notification rows.

**Cursor.** Only `su.onno.query.Cursor` issues tokens. All feeds now issue context-bound v2
positions, carrying typed sort values (including SQL DATE and nanosecond timestamps). A valid
foreign token (another entity/feed, sort, filter or access scope) returns HTTP 400 / an MCP error.
A syntactically damaged token or a position whose values do not match the projected SQL types
restarts from the first page, without executing an invalid seek comparison. Pre-v2 tokens cannot
prove their feed identity and are rejected as foreign; restart without a cursor after deployment.
The cursor is a position, not an authorization credential or a snapshot: SQL/access checks still
run on every request, and edits to sort values can move records.

**Implementation.** `su.onno.query.KeysetWindow` is the shared forward SQL helper. The web
starter's `KeysetWindow` adapter retains catalog/document backward/focus navigation and their optimized
non-null seek predicates, while using the same codec, limits and JDBC type validation. Sort
identifiers are validated metadata. Order tuples are unique; nullable keys are NULLS LAST in both
directions. Queries fetch at most `limit + 1`; the extra row detects continuation and is discarded.
No SQL OFFSET or full-result in-memory slicing is used for pagination. SQL COUNT is separate.

| Feed | Ordering / access |
| --- | --- |
| `/api/list/catalogs/{name}`, `/api/list/documents/{name}` | Validated sort + `_id`; search, filters, record scope and document date bounds before seeking |
| `/api/list/registers/{name}/movements`, `/api/registers/{name}/movements` | Validated sort (default `_period DESC`) + `_id`; active movements, date/filter/record scope |
| `/api/list/registers/{name}/balance`, `/api/registers/{name}/balance` | Validated sort + remaining dimension primary-key columns; BALANCE registers only |
| `/api/registers/{name}/turnover` | Grouped dimension tuple; record/dimension/period/`where` filters before aggregation and pagination |
| `/api/information-registers/{name}/records` | Period DESC + `_id` DESC (nonperiodic: `_id`); `where` and record scope before seeking |
| `/api/notifications` | `_created_at DESC, _id DESC`; recipient and target record scope before LIMIT; no legacy array alias |
| `/api/comments/{kind}/{name}/{id}` | `_created_at ASC, _id ASC`; parent record read access required |
| `/api/catalogs/{name}/children`, `/api/catalogs/{name}/tree` | `_is_folder DESC, _description ASC, _id ASC`; paged children or flat nodes with parent links |
| `/api/crm/inbox-workspaces/{key}` | Validated sort + UUID; workspace, customer and personal-account checks precede SQL page selection |
| `/api/crm/contacts/{customer}/activity` | SQL UNION of messages/notes; time, source and UUID DESC; only readable conversations; no legacy array alias |
| `/api/crm/inbox-workspaces/{key}/conversations/{id}/comments` | Shared comment page after workspace authorization |
| `/api/processes`, `/api/tasks` | Start/creation time + UUID; instance participation or task assignment applied in SQL |
| Process history, executions, task history | Requested instance/task authorization; sequence or entry time + UUID |
| `/api/history/{kind}/{name}/{id}` | Change time DESC + change id DESC; record read access (entity grant + record scope); cursor bound to record and caller |

**Breaking release.** Numeric `offset` pagination and the notification `items` / CRM activity
`entries` aliases are removed. Use `cursor` and read `rows`; no positional traversal helper is
retained. Record history uses the same envelope (its earlier `entries` / `nextBefore` / `before`
shape is gone). Java methods that return a plain `List` (for example `ProcessEngine.inbox(actor)`,
`CatalogQueryService.tree(...)`, `CommentService.list(...)`, `RegisterQueryService.turnover(...)`,
`RecordHistory.list(id)`) return the **complete** result: they follow the cursor through bounded
windows with `su.onno.query.KeysetWindow.drain(...)` and never silently truncate. Request paths
must use the paged variants. The explicitly bounded `*Bounded` register previews keep their
`truncated` flag; the paged register variants are `movementsPage` / `balancePage` /
`turnoverPage`. In `@onno/ui`, `getMovements` / `getBalance` / `getTurnover` take `cursor`/`limit`
and return the envelope, `allRegisterRows` follows the cursor, and `useMovements` / `useBalance` /
`useTurnover` read every window (500 rows at a time) before a component computes totals.
Historical balance pages accept `asOf` as an inclusive local date-time or a whole local date;
access scope is applied before summation, filters to the resulting balances, and cursors are
bound to the cutoff. Omit `asOf` to use current materialized totals.

**Backward/focus navigation.** Catalog/document pages additionally expose `prevCursor`,
`hasPrevious` and optional `focus`. `before=<prevCursor>` requests the preceding window;
`focus=<id>` requests the surrounding window. A hidden, deleted, filtered-out or unknown focus
falls back to the first window without revealing whether it exists. `cursor` wins over `before`,
which wins over `focus`. Forward/backward tokens share the same feed context.

**Bounded exceptions.** Arrays are retained for selector/configuration responses: reference search
max 200, mentions max 500, settings/process definitions max 500, tag selectors max 500, CRM workspace
and account choices max 100, notification types max 100. Tags/settings likewise reject oversized results rather
than silently hiding editable definitions. `/tree?ids=` is a bounded nested-path lookup (200 ids,
at most 65 levels). Group/aggregate visualization buckets are bounded and explicitly signal
truncation. Owned tabular sections and static model/graph metadata are aggregate payloads, not
independent collection feeds. Trusted business repository reads remain separate from user-facing
collection transports and must not be silently capped, particularly during posting.

**CRM Java callback compatibility.** Arbitrary host selectors/facets cannot be translated to SQL.
Their fallback scans conversations in batches of at most 500 and projects eligible sort keys into
a request-local SQL table. This bounds conversation buffers but still costs a source scan per
request. A shared Spring transaction makes repositories, JDBC callbacks and the projection use
one connection, including pools of size 1; temporary tables are dropped on exit. Host callbacks
must use that datasource/transaction and return per-conversation results independent of batch
membership. This is not a constant-time indexed workspace query. Schema-backed selection can be
introduced by hosts without changing the wire contract. Responses from replaced/unmounted UI
scopes are ignored, so delayed pages cannot be appended to a different record.

Reference pickers remain context-aware bounded searches (`POST /api/ref-options/search`). With an
empty `query` it leads with up to five of the caller's recent picks, each flagged `_recent: true`,
then the ordinary options without duplicates, still within `limit`; send `"recent": false` to omit
them. Picks are remembered with `POST /api/ref-options/recent` (`{targetKind, targetName, id}`); a
recent pick is re-read through the same search, so it obeys the deletion mark, record scope and the
request's `where` like any other option. The "Show all..." picker dialog pages the ordinary list
feed with the field's `where` (plus the parent for folder navigation on a hierarchical catalog) and
checks each window with `POST /api/ref-options/decorate` (`ids` plus the `/search` context), which
answers `offered` ids and their `decorations`. Recent picks do not grant visibility or bypass the
collection contract. See [onno-web-starter/README](../onno-web-starter/README.md) for the complete
list contract.

Row predicates are sent as `where`: URL-encoded JSON on the list feed, its `/groups` and
`/aggregate` (and a JSON object field on the ref-option `/search` and `/decorate` bodies). Keys are
logical field names (`code`, `description`, `number`, `date`, `posted`, `id`, attributes); a value is
a literal (equality), `null` (IS NULL) or an operator object — `eq`, `not` (`{"not": null}` is IS NOT
NULL), `in`, `notIn`, `gt`, `gte`, `lt`, `lte`, `contains`, `startsWith` (case-insensitive) — plus
`and`/`or` arrays and a `not` object. Refs and enums are UUID strings (an enum also accepts its
constant name), dates ISO strings. It applies to the window, focus, count, groups and aggregates,
always within the caller's record scope:

```text
GET /api/list/documents/Orders?where={"posted":true,"total":{"gte":100},"or":[{"note":{"contains":"rush"}},{"client":null}]}
```

An unknown field or operator, a malformed value, nesting deeper than 8 or an `in` list over 500
values is a `400`.

Catalog/document single-record, hierarchy, list-feed, reference-option,
create/update/duplicate, and post/unpost entity responses all accept
`?representation=logical|storage`. Omitted means `logical`; an unknown value is `400`. Registers
and aggregate/group responses retain their own storage/aggregation contracts and do not use this
switch.

Java callers of related-list query adapters select the public
`EntityJsonRepresentation.Mode.LOGICAL` or `STORAGE` with the same representation semantics.

## Response shape

Catalog/document responses use **logical API names by default**. Attributes use their Java
`fieldName`; system and sidecar names are camelCase. These are the default read keys:

| Key | Applies to | Meaning |
|-----|------------|---------|
| `id` | catalog, document, TS row | UUID primary key |
| `code` | catalog | natural key / slug |
| `number` | document | natural key / slug |
| `date` | document | timestamp |
| `posted` | document | posting flag |
| `description` | catalog | display name |
| `deletionMark` | catalog, document | soft-delete flag |
| `folder`, `parent` | hierarchical catalog | folder flag / parent UUID |
| `version` | catalog, document | optimistic-lock version |
| `parentId`, `lineNumber` | tabular-section row | back-reference to the owning catalog/document / 1-based ordinal |
| `<fieldName>` | attribute | Java field name; a `Ref<>`/enum is a UUID, a `PolyRef` is `JavaType\|UUID` |
| `<fieldName>Display` | `Ref<>`, `PolyRef` & enum attrs | resolved human label |
| `<fieldName>Ref` | `Ref<>`/`PolyRef` attrs | `{ id, type, display, kind?, javaType?, code?, avatarUrl?, color? }`; `type` is the target's logical name, while `kind`/`javaType` are present for polymorphic refs |
| `<fieldName>Code` | catalog-`Ref<>` attrs only | the target's code |
| `<fieldName>Avatar` | catalog-`Ref<>` attrs only | the target's `avatar_url` |
| `<fieldName>Color` | enum & catalog-`Ref<>` attrs | `@EnumLabel(color)` hex, or the ref target's `color` column — a status pill |
| `<fieldName>Restricted` | `Ref<>`/`PolyRef` attrs | `true` when the viewer may not read the target; see [Restricted references](#restricted-references) |
| `parentRestricted` | hierarchical catalogs | `true` when the parent is outside the viewer's record scope (`parent` is then `null`) |

### Storage compatibility representation

Append `?representation=storage` to retain the pre-#314 response shape. It maps the default names
back to `_id`, `_description`, `_posted`, `tax_id`, `region_display`, and so on. This is an explicit
compatibility contract, not an undocumented database leak; tests pin it while consumers migrate.
The bundled SPA consumes the preferred logical representation; storage column names remain confined
to its server-side query descriptors.

Writes accept both vocabularies during the compatibility period. For example, `taxId` and `tax_id`
are aliases, as are `description` and `_description`; the logical spelling is canonical. Supplying
both with equal values is allowed. Supplying both with different values returns `400` instead of
choosing one silently. Read-only keys and display/ref/color companions remain ignored by partial
writes, so a default logical GET payload can be submitted to PUT without renaming writable keys.

### Temporal values

Temporal columns are database-independent ISO strings:

| Java type | Read representation | Accepted write representation |
|-----------|---------------------|-------------------------------|
| `LocalDate` | `yyyy-MM-dd` | `yyyy-MM-dd` |
| `LocalDateTime` | offset-free `yyyy-MM-ddTHH:mm[:ss[.fraction]]` | the same offset-free ISO representation |
| `LocalTime` | `HH:mm[:ss[.fraction]]` | the same |
| `Instant` | ISO UTC `yyyy-MM-ddTHH:mm:ss[.fraction]Z` | ISO date-time **with** `Z` or `±HH:mm`; offset-free values are a `400` |

`Instant` is an absolute moment, stored as `TIMESTAMP WITH TIME ZONE` and always read back in UTC.
Clients choose the display zone; the bundled UI uses `onno.ui.time-zone` (exposed as `timeZone` in
`/api/config`) or, when unset, the viewer's browser zone. List `ge`/`le` bounds accept the same
`HH:mm` / offset-bearing shapes and compare natively.

`LocalDateTime` is a business wall-clock value, not an instant. Offset- or zone-bearing writes such
as `2026-06-04T10:00+03:00` and `2026-06-04T10:00Z` are rejected with a field-specific `400`
validation response; callers must choose the intended wall time and send it without an offset.

PostgreSQL/JDBC may internally expose timestamps as `Timestamp` or offset-bearing values, but the
read API normalizes those before JSON serialization. The generated form also canonicalizes loaded
catalog, document, and tabular-section temporal values before save. Default headless reads and
writes use the same field name:

```jsonc
// GET /api/documents/Events/{id}
{ "startsAt": "2026-06-04T10:00" }

// PUT /api/documents/Events/{id}
{ "startsAt": "2026-06-04T10:00" }
```

### Catalog row

```jsonc
{
  "id": "f3b1…",             // UUID primary key
  "code": "C-000123",        // natural key / slug
  "description": "Acme Corp",
  "deletionMark": false,
  "folder": false,
  "parent": null,             // UUID of parent folder (hierarchical catalogs)
  "version": 3,               // optimistic-lock version
  "taxId": "B12345678",      // attribute field
  "region": "a17c…",          // a Ref<> / enum attribute is stored as a UUID
  "regionDisplay": "Madrid",  // + resolved display (see "Reference & enum expansion")
  "regionRef": { "id": "a17c…", "type": "Regions", "display": "Madrid", "code": "R-01" },
  "regionCode": "R-01"        // catalog-ref only; + regionAvatar when the target has one
}
```

Hierarchical catalog writes validate `parent` after the entity's filling/before-write hooks. The
parent must be an existing folder in the same catalog, not marked for deletion, and its ancestor
chain must not contain a cycle. `parent: null` moves a record to the root. A folder with active
children cannot be turned into an item or soft-deleted. Invalid hierarchy writes return HTTP 400
with `error: "validation"`, `field: "parent"` and `fieldErrors.parent`; an inaccessible parent still
uses the existing record-access response. Catalog `/validate` reports these rules in its normal
HTTP 200 validation payload without saving. Typed repository saves enforce the same invariants.

### Document row

```jsonc
{
  "id": "…",
  "number": "SO-00042",       // natural key / slug
  "date": "2026-06-04T10:00:00",
  "posted": true,
  "deletionMark": false,
  "version": 1,
  "customer": "…",            // Ref<> UUID (+ customerDisplay / customerRef)
  "items": [                  // tabular section, keyed by its section name — GET /{id} only
    {
      "id": "…",
      "parentId": "…",        // back-reference to the document
      "lineNumber": 1,
      "product": "…",         // row attribute columns, same conventions
      "productDisplay": "Widget",
      "quantity": 3
    }
  ]
}
```

List-feed rows do **not** inline tabular sections; single-catalog and single-document reads do. A single-record read
returns `404` when the id is unknown.

## Reference & enum expansion

A `Ref<>` or `@Enumeration` attribute is stored as a UUID. For each such field the read layer adds
two sibling keys so the client need not make a second call:

- `{fieldName}Display` — a human-readable label (catalog description or code; for an enum, the value's
  `@EnumLabel`, falling back to the constant name when unlabelled).
- `{fieldName}Ref` — an object `{ id, type, display, code?, avatarUrl?, color? }` for richer rendering
  (`type` is the target's logical name; `code`/`avatarUrl`/`color` may be present for catalog refs).
  Document refs get only `Display` + `Ref` companions.
- `{fieldName}Code` — catalog refs only: the target's code.
- `{fieldName}Avatar` — catalog refs only: the target's `avatar_url`, when it has one.
- `{fieldName}Color` — a badge colour (CSS hex) the client paints as a status pill. Emitted for an
  **enum** value declaring `@EnumLabel(color="#…")`, and for a **catalog ref** whose target declares
  a `color` attribute (column-name convention, like `avatar_url`) holding a non-blank value — this
  is what lets a user-editable status *catalog* keep the colored pills a status enum had. Absent
  when there is no colour.

The raw `{fieldName}` value remains the UUID for `Ref<>`, or `fully.qualified.JavaType|UUID` for a
`PolyRef`, so writers can round-trip it unchanged. A polymorphic `Ref` companion also includes `kind`
(`catalog`/`document`) and `javaType`; its `type` is the selected target's logical name.

With `?representation=storage`, these companions retain their former `{column}_display`,
`{column}_ref`, `{column}_code`, `{column}_avatar`, `{column}_color` and `{column}_restricted` names.

### Restricted references

References resolve **per viewer**. When the viewer has no entity-level read grant on the target, or
the target record is outside the viewer's record scope (see
[Record access policies](RECORD_ACCESS_POLICIES.md)), the ref is *restricted*:

```json
{
"tenant": null,
"tenantDisplay": "—",
"tenantRestricted": true,
"tenantRef": { "type": "Tenants", "display": "—", "restricted": true }
}
```

The target's id and label are withheld, so a list never reveals the name — or the existence — of a
record its viewer can't open. A client that echoes `null` back on a partial update does not clear the
stored link. In group headers (`/groups`) the raw value is kept as the group's expand key; only the
label is masked.

## Record scope

For a user restricted by a record policy every read is scoped: lists, counts (`count=estimate` returns
the exact scoped count), groups, aggregates, search and trees only contain records in scope, and
`get` of any other id is `404` — indistinguishable from a missing record. `?ids=` live patches drop
out-of-scope ids. Keyset cursors are bound to their feed and access context (an opaque context
suffix); replaying one as another user is a `400`.

## Secrets

Columns from a `@Attribute(secret = true)` field are **write-only**. On read they are replaced in
place with the sentinel string `__SECRET_SET__` when a value is stored, or `null` when empty — the
ciphertext is never returned. Submitting the sentinel back on a write means "leave unchanged".
Secret attributes are also excluded from every catalog/document list-query allowlist: clients cannot
filter, sort, group, or aggregate by them. This prevents row membership or counts from becoming a
blind oracle for encrypted values.

## Record history

`GET /api/history/{kind}/{name}/{id}` returns the change history of one catalog item
(`kind = catalogs`) or document (`kind = documents`) whose entity opted in with
`@Catalog(history = true)` / `@Document(history = true)`, as one window of the shared collection
envelope: `limit` (default 100, max 500) and `cursor` (the previous window's `nextCursor`). Entries
are ordered by change time, then change id, so events stamped at the same instant are neither
skipped nor repeated. The cursor is bound to the record and the caller; a foreign one is `400`.

```json
{
  "rows": [
    {
      "id": "0f6f…",
      "event": "UPDATED",
      "at": "2026-11-13T09:41:07.120394Z",
      "actor": "vet@clinic.example",
      "actorId": "5a0c…",
      "changes": [
        { "section": null, "line": null, "rowChange": null,
          "field": "owner", "label": "Owner", "type": "ref", "secret": false,
          "old": "9d2e…", "new": "41b7…", "oldDisplay": "Ann Lee", "newDisplay": "Bob Stone" },
        { "section": "procedures", "line": 2, "rowChange": "ADDED",
          "field": "title", "label": "Title", "type": "text", "secret": false,
          "old": null, "new": "Bandage" }
      ]
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

- Entries are newest first. `event` is `CREATED`, `UPDATED`, `DELETED` (deletion mark set),
  `RESTORED`, `POSTED` or `UNPOSTED`; post/unpost/delete entries usually have no `changes`. A
  `CREATED` entry lists the initial values with `old: null`.
- `actor` is the login the change is attributed to (`system` for trusted code); `actorId` is the
  `Layout.identity(...)` record id when known. `at` is a zone-qualified instant.
- `field` is the logical name (`code`, `description`, `parent`, `folder`, `number`, `date`, or an
  attribute's field name). `section` / `line` / `rowChange` (`ADDED` / `REMOVED` / `CHANGED`)
  describe a tabular-section row; rows are compared by line position, so inserting a line in the
  middle reads as changes to the lines after it.
- `old` / `new` are the stored values as text: reference and enumeration ids, ISO-8601 dates,
  plain decimals, `true`/`false`. `type` (`text`, `number`, `boolean`, `date`, `datetime`, `ref`,
  `enum`) tells a client how to format them. `oldDisplay` / `newDisplay` carry the resolved
  reference description or enumeration label.
- **Access.** The endpoint needs what reading the record needs: no read grant on the entity is
  `403`; an entity without history, or a record outside the caller's record scope, is `404`. A
  reference the caller may not read has its id withheld (`null`) and display `—`, as on the record
  itself. A `secret` attribute reports `"secret": true` with `old` and `new` both `null`; its
  values are never stored in the history table.

## Writes (partial, logical names plus storage aliases)

Writes mirror the default logical reads:

- **Request bodies canonically use camelCase `fieldName`.** System fields are catalog `code` /
  `description` / `folder` / `parent` / `version` and document `number` / `date` / `version`.
  Storage aliases (`tax_id`, `_description`, `_version`, and tabular-row storage columns) are also
  accepted; conflicting duplicate spellings are `400`. A `Ref<>`/enum is written as its bare UUID string.
  A `PolyRef` accepts its raw `JavaType|UUID` string or `{ "type": "<logical-name-or-Java-type>",
  "id": "<uuid>" }`; the declared `@RefTargets` allowlist is enforced.
- **Updates are partial.** `PUT /api/{catalogs|documents}/{name}/{id}` only touches the fields present
  in the body — omitted fields keep their stored value, and an empty body is a no-op. So a
  `PUT { "startsAt": "…" }` moves just that field and does **not** null the rest. Validation follows
  the same rule: `@Attribute(required = true)` fields absent from an update body are not flagged
  (they're unchanged) — but a key explicitly present with `null`/blank still fails, because that
  write would clear the column. Entity-level `rules()` always run on the merged state.

```text
POST /api/catalogs/{name}                 create (body = camelCase fields)
PUT  /api/catalogs/{name}/{id}            partial update
POST /api/documents/{name}                create
PUT  /api/documents/{name}/{id}           partial update
POST /api/documents/{name}/{id}/post      post, or atomically repost an already-posted document
```

**Relations saved with the record.** A create or update body may carry `@relations`: the complete
row set of one or more many-to-many join entities that reference the record. Each entry names the
join catalog or document, the reference field pointing back at the record (`via`, which must be a
single-target reference to the written entity) and its rows:

```json
{ "description": "North clinic",
  "@relations": [{ "entity": "Clinic Doctors", "via": "clinic",
                   "rows": [{ "id": "<existing link>", "doctor": "<uuid>", "role": "Chief" },
                            { "doctor": "<uuid>" }] }] }
```

The server replaces the set in the record's transaction: a row with `id` updates that join record
(partially, like `PUT`), a row without one creates a join record with `via` set to the record, and live
join records of the record missing from `rows` are delete-marked. Only join records the caller can
read count: a link a record policy hides from them is never offered and never removed. Every row goes through the ordinary
write path (access, record policies, hooks, rules, history). A row `id` belonging to another record is
`400`; any failure rolls back the record and every relation. The response is the record as usual.

Two more contracts worth knowing:

Business merging and any undo are host commands; see the [CRM composition guide](../onno-crm-starter/README.md).

Writes are record-scoped too: updating, deleting, posting or unposting a record outside the caller's
write scope is `404`; a create or update whose result would leave the write scope is `403` (nothing is
written); a reference value — header or tabular line — pointing at a record the caller may not read
is `422`. Policy defaults (e.g. `owner`) are filled on create and cannot be overridden by the body.

## Filtering & deletion

`list` returns only live rows (`deletionMark = false`; `_deletion_mark` in storage representation).
Deletes are soft (the mark is set), so a
deleted row disappears from `list` but is still reachable by `get` until purged. The generic
`DELETE` and Spring Data `CatalogRepository`/`DocumentRepository` delete methods run the same
command: `BeforeDeleteHandler`, then the mark (version bumped, delete events), without `beforeWrite`
or validation. Deleting a posted document answers `409`; unpost it first (`batch-delete` reports it
as a failed id).

## Reacting to changes

Server-side consumers (cache/ISR revalidation, search indexing, outbox relays) should not poll. Every
write — through the generic controllers **and** through `repository.save(...)` — publishes a Spring
`EntityChangedEvent(changeType, entityType, entityName, id, naturalKey)`; the `naturalKey` is the
catalog code / document number, so a listener can revalidate a specific resource rather than
everything. The same event drives the browser live-update SSE stream (`GET /api/events`). See
[`su.onno.events.EntityChangedEvent`](../onno-framework/src/main/java/su/onno/events/EntityChangedEvent.java).

If you consume `/api/events` with a browser `EventSource`, note the stream sends **named** events
(the change type: `created` / `updated` / `deleted` / `posted` / `unposted` / `changed`, plus
`ready` / `presence` / `notification` / `tasks-changed`) — never the default unnamed `message`.
`tasks-changed` is an audience-scoped, payload-free signal to refetch authenticated `GET /api/tasks`;
candidate assignments are not sent to the browser. `EventSource.onmessage` fires for nothing; you
must `addEventListener("updated", …)` (etc.) per event name you care about.

## MCP register movements

For authenticated MCP clients, `register_movements` and `register_balance` return the same
`{rows,nextCursor,hasMore}` page in MCP's `result` wrapper, in the logical vocabulary above. Both
accept `cursor`, `limit` (default 100, maximum 500) and `where` (the structured predicate; reference
and enumeration values use UUID strings). Movements additionally accept inclusive `from`/`to`,
validated `sort`, and `descending` (default true); balance accepts `asOf`. The predicate and record
scope are applied in SQL before seeking. Unknown `where` fields and invalid page bounds produce an
MCP error. See [Collection pagination](#collection-pagination) for the contract.

## Notes for a public read view

There is no separate "public projection" endpoint yet; the generic read API is the contract above and
is auth-gated. To expose a curated, anonymous read surface, front it with your own controller that
maps the logical entity to your DTOs, and (if it also accepts writes) add its path to
`onno.auth.additional-public-paths` and `onno.auth.csrf-ignored-paths`. Mandatory framework
bootstrap paths remain public; legacy `onno.auth.public-paths` entries are still honoured.

### CRM workspace and contacts (optional CRM starter)

`GET /api/crm/workspace` returns `{workspace:{version,config,availableFields},canConfigure:false,customerCatalog}`.
It exposes developer-authored presentation from `CrmWorkspaceCustomizer`; no layout write API or
CRM custom-value storage exists. `GET /api/crm/contacts/{id}` returns
`{catalogName,fields,identities,conversations,canWrite}`. The bound host catalog supplies selected
fields and permissions; related conversations are workspace-filtered. Canonical redirects resolve
host-initiated link consolidation. Customer CRUD/search use the host's generic catalog endpoints.
There are no CRM contact edit, duplicate-search, merge-preview, merge, history or undo endpoints.

`GET /api/crm/statuses` returns configured `{id,description,color,closed}` choices (empty if disabled).
`GET /api/crm/channels` returns `{channels,canManage}` for enabled connectors only, with no credentials.
`POST /api/crm/channels/{key}` accepts `{action,credential?}` and enforces host `CrmChannelAccess`
(ADMIN by default). Unknown connectors return 404; invalid commands return 422.
`POST /api/crm/contacts/{id}/identities` accepts manual `{channel,address}` email/phone identities,
requiring writable workspace membership and host customer write permission. Telegram's cached JPEG
endpoint `/api/crm/telegram/avatars/{identityId}` requires customer/workspace access as well, and so does
Instagram's `/api/crm/instagram/avatars/{identityId}`: the adapter downloads the profile picture of each person who
messages the account (a few per poll, each re-checked weekly) and serves its own copy, since Instagram's signed CDN
URLs expire within days and never leave the server.

The starter activates only with a `CrmCustomerBinding`; it creates no customer, employee or sales
catalog. Customer/assignee keys in CRM persistence are UUIDs scoped to explicit host bindings.
Dedicated inbox feeds add `customerDisplay`, `customerRef:{type,display,id} (type is the host catalog logical name)` and `customerAvatar` from
the binding, and resolve assignee labels only when the caller may read the bound employee.

The UI `onno-list` descriptor may carry `selectionCheckboxes: true` to display an optional Onno
selection column in catalog/document tables. This changes presentation only: selected actions use
the existing `POST /api/actions/{kind}/{name}/{key}/batch` contract and role/validation checks.
Select-all operates on loaded rows rather than requesting an unbounded set of matching IDs.

The CRM workspace response's `config.folders` contains ordered developer-authored folders:
`key`, `label`, `channels`, `statuses`, `priorities` (status UUID and channel/priority enum-name arrays), `conversationIds` (UUID array), and `unreadOnly`. Matching enum UUIDs are also exposed as
`channelIds`, `statusIds`, `priorityIds` for the host list representation.
Empty arrays are unrestricted; criteria intersect. An empty folders array selects the flat inbox
list. The first matching folder wins. These are client views over the current authorized list, not additional read permissions.

### Optional Gmail adapter (`onno-crm-channels-starter`)

When `onno.crm.channels.gmail.enabled=true`, `CrmChannelAccess`-protected `POST /api/crm/gmail/authorize` (normal CSRF protection)
returns `{ "url": "https://accounts.google.com/…" }`. The session-bound, single-use GET callback at
`/api/crm/gmail/callback` exchanges the authorization code and returns to the local `returnPath` supplied to the authorize request.
Credentials are never returned. Existing `/api/crm/channels` includes Gmail health and actions;
message reading and reply enqueueing retain the standard CRM endpoints and authorization. New
connections ask for `gmail.readonly`, `gmail.send` and `gmail.modify`; only the first two are required.

The delivery connection (`GET /api/crm/conversations/{id}/delivery`) adds `senders:[{id,label}]` and
the default `sender` (the conversation's own mailbox address); the reply command accepts an optional
`from` among those ids (else 400). Gmail offers shared mailboxes and the caller's own personal one.
It also adds `cc`, the suggested Cc of a reply (reply-all: the latest received email's To and Cc without the
deployment's mailboxes and the recipient). The reply command accepts an optional `cc` list (at most 20 single
addresses; else 422), stored on the message as `copyTo`; messages and activity entries carry an email's `sentTo`
and `copyTo` (comma-joined `Name <address>` entries, null when unknown).

Mail search and restore, all requiring inbox workspace access (restore: write access) and limited to
shared mailboxes and the caller's own personal ones (any other `account` is 404):

- `GET /api/crm/gmail/accounts` → `[{address,label,canModify}]`.
- `GET /api/crm/gmail/search?account=&q=&pageToken=` (`q` required, at most 500 characters) →
  `{messages:[{id,threadId,from,subject,snippet,date,archived,conversationId}],nextPageToken}`, 20 per
  page over all mail including archived (spam and trash excluded); `date` is ISO-8601,
  `conversationId` is null unless the thread is already in the CRM and readable.
- `POST /api/crm/gmail/restore` `{account,messageId,toInbox}` → `{conversationId,movedToInbox}`;
  imports the whole thread and, with `toInbox` and the modify grant, moves it back to the Inbox.
  Idempotent. Gmail failures answer 429, 404, 409 (reconnect) or 502.

### CRM inbox workspace API

`CrmInboxWorkspace` beans activate scoped inbox mode, separate from persisted channel accounts.
`GET /api/crm/inbox-workspaces` returns only accessible `{key,label,canWrite}` entries.
`GET /api/crm/inbox-workspaces/{key}?q=&cursor=&limit=100` returns
`{key,label,config,canWrite,list,channelAccounts,rows,nextCursor,hasMore}`. `total` is optional
and requires `count=exact`. Rows use logical camel-case fields and `...Display`/`...Color`/`...Ref`
sidecars. Workspace selection and access precede page selection; see the scoped inbox pagination
details below. The workspace choice list and channel-account choices are hard-capped at 100.

Every existing `/api/crm/conversations/{id}/...` command/read requires the
`workspace` query parameter. Membership and the workspace's read/write roles are independently
checked; missing scope, an inaccessible workspace, a foreign chat or read-only mutation returns 403.
The CRM workspace feed accepts `ids` (comma-separated or repeated UUIDs, at most 500) for live row refreshes. It intersects these IDs with workspace membership and read authorization before returning rows or counts. Reading a conversation therefore refreshes that conversation without replacing it with the first inbox row.

`GET|POST /api/crm/inbox-workspaces/{key}/conversations/{id}/comments` accesses the existing internal
notes. GET accepts `cursor`/`limit` and returns the common page envelope; POST accepts `{body}` of 1–8000 characters, also subject to the shared
`onno.comments.max-length` limit (4000 by default). These routes use normal session CSRF protection.

Business merging and any undo are host commands; see the [CRM composition guide](../onno-crm-starter/README.md).

A page binds `<CrmInbox workspace="support" />` to one workspace.
The scoped feed accepts `status` UUIDs and `channel`/`priority` enum names (`all` by default);
filters apply after workspace membership and before pagination.

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

### Optional WhatsApp webhook

With `onno.crm.channels.whatsapp.enabled=true`, `GET /api/crm/whatsapp/webhook` accepts `hub.mode=subscribe`, `hub.verify_token`, and `hub.challenge`, returning the plain challenge only after token validation. `POST` accepts Meta JSON with `X-Hub-Signature-256`, verifies the raw-body HMAC before parsing, limits payloads to 1 MiB, and returns 200 after applying recognized configured-account events. Missing/invalid signatures return 403; malformed payloads return 400. This exact path needs public access and a CSRF exemption in cookie-auth hosts. Other CRM endpoints retain their normal authorization.

### Unified contact activity

The CRM Inbox presents one row per contact and a chronological timeline across its channel conversations, internal comments and system events. Underlying conversations retain provider routing and email thread identifiers. The reply selector changes only the destination, and defaults to the latest incoming message's conversation; an unsent draft keeps its selected destination.

`GET /api/crm/contacts/{customer}/activity?cursor=&limit=100` returns `{rows,nextCursor,hasMore}` (newest first, default 100, maximum 500). Only the `rows` key and cursor continuation are supported. No total is calculated. Messages and internal notes are combined by SQL UNION ALL, restricted to authorized conversation keys before seeking on `(time DESC, source DESC, UUID DESC)`. Files, HTML, author photos and account labels are resolved only for the selected page. Each entry includes `id`, `conversationId`, `subject`, `channel`, `kind`, `direction`, `authorName`, `body`, `at`, and `deliveryStatus`; message entries also retain `senderAccount`, the account used to send the reply, or null, and an email's `sentTo`/`copyTo` recipient lists. Only readable conversations are included, with canonical contact resolution after merging. The UI loads older pages on demand and updates through existing CRM SSE invalidations.

`POST /api/crm/contacts/{customer}/activity` accepts `conversationId`, `type` (`QUOTED`, `CALL_PLANNED`, `CALL_COMPLETED`, `MEETING_PLANNED`, `OTHER`), nonblank `details` up to 7000 characters, and optional local `scheduledFor`. It requires write access to the matching contact's conversation. It stores an internal `SYSTEM_EVENT` without sending a channel message or changing the sales stage. Planned dates are descriptive event text, not reminders or calendar invitations. Domain workflows can also append system events through the existing conversation-message repository.

### Print form downloads (optional `onno-print-starter`)

`GET /api/print/{kind}/{name}/{id}` lists the print forms the caller may use for a record as
`[{key, label, format, url}]` (`format` is `docx` or `xlsx`). `GET {url}` returns the filled file
with `Content-Disposition: attachment`. Both apply the same entity read access and record scope as
`GET /api/{kind}/{name}/{id}` (a record outside scope is `404`), plus the form's optional roles
(`403`). The response is a binary file, not JSON. `?format=pdf` (or the template format) picks the
output when a PDF converter is configured; `formats` in the listing says which values are accepted.
`POST /api/print/batch/{kind}/{name}/{form}` with `{"ids":[…]}` (CSRF) returns a zip, skipping ids
outside the caller's scope. Template management (`/api/print/templates/**`) is admin-only; see
`onno-print-starter/README.md`.

### Record tag API

`GET /api/tags/{kind}/{name}` lists selectable tag definitions. `POST /api/tags/{kind}/{name}`
with `{name,color}` returns the existing definition whose name matches case-insensitively, or
creates one (name 1–500 characters after trimming; `color` is `#RRGGBB`, default `#8b78ff`); the
record tag picker uses it to create tags inline. It requires write access to the tagged entity and,
when a `TagCatalog` owns that scope, write access to that catalog as well — creation then goes
through `TagCatalog.create(name, color)`, whose default delegates to `importTag` with a fresh ID.
Definitions can still be managed through the owning tag catalog’s ordinary CRUD API. `GET /api/tags/{kind}/{name}/{id}` lists a record's tags.
`POST /api/tags/{kind}/{name}/{id}/{tagId}` adds an assignment; `DELETE` removes it.
Kinds are `catalogs` and `documents`; names normalize to the owning entity. Responses contain
`{id,name,color}`. Definitions have stable UUIDs; assignment mutations are idempotent and reject
tags from another entity library. All reads require entity read access; mutations require write
access, existing live records, and applicable `TagAccessPolicy` checks. Invalid input returns 400;
missing/deleted records return 404; denied access returns 403. Tag assignment changes emit an
`updated` SSE event with entity type `tag` and the record ID.

Business merging and any undo are host commands; see the [CRM composition guide](../onno-crm-starter/README.md).

Unified contact activity entries include `authorAvatarUrl` and `mine` for internal notes. Ownership matches the signed-in identity record ID, not the display name; photos resolve from the live identity catalog using the standard comment avatar resolver. The inbox preserves both fields when rendering notes.

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

### Opening a workspace conversation

In workspace mode, the app renders `@onno/crm`'s `CrmConversationPage` at
`/catalogs/crm_conversations/:id`; the server verifies host customer permissions and workspace
membership, using an accessible inbox workspace. Its feed is
`GET /api/crm/inbox-workspaces/{key}/conversation/{id}`; it returns only that contact's conversations
within the authorized workspace and retains normal filtering/pagination. Foreign records return
403 and missing records return 404. Generic catalog/message read and export restrictions remain
unchanged. Without workspace definitions, the normal default catalog form remains in use.

Host applications contribute header actions through the shared extension slots. The CRM starter
supplies authorized commands but no default action-button contributions. Customer lifecycle stages,
forms and any template catalog belong to the host, with the standard generic read/write contracts.

`Conversation.status` is a UUID scoped to the host status catalog or enumeration. Logical reads include `statusDisplay`,
`statusColor`, and `statusRef`. `POST /api/crm/conversations/{id}/status?workspace={key}` accepts
`{"status":"configured-uuid"}` and requires the same host customer read permission and writable-workspace membership
as other conversation commands. The target must occur in application `CrmStateConfiguration`.
The host owns status records and editing; `CrmStateConfiguration` binds them without copying them.

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

### CRM account context and empty folders

Scoped inbox feeds include `list.channelAccounts` (`id`, `channel`, `label`) from readable workspace
conversations before applying filters/pagination. Hidden conversations never contribute account choices.
Activity feed entries include `accountLabel` (inbox description and address) after conversation access
checks. Manual activity writes honor `onno.web.read-only` in addition to customer/workspace write checks.
Folder configuration supports `matchNone: true` for visible empty categories; omitted/false preserves
existing empty-criteria matching. See the [CRM guide](../onno-crm-starter/README.md#unified-inbox-controls-and-manual-activity).

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

Telegram business onboarding uses `POST /api/crm/telegram/business/{account UUID}/authorize`
(account ownership, workspace write access and CSRF required). The returned one-use Telegram link
proves the provider user before attaching a business connection. `telegram-business` managed accounts
support PERSONAL/SHARED independently of sender mode. Routing, deduplication and delivery are isolated
from the ordinary shared bot inbox, with reply permissions checked before sends. See the channels
starter README for lifecycle, skipped paused updates, attachment and history limits.

CRM audio attachments keep the existing `contentType`, `url`, `filename`, `size`, and `image` contract. The inbox renders `audio/*` with playback controls; no additional message field is required. Common audio formats served through `/api/media/{key}` use inline disposition.

Catalog `@TabularSection` fields follow the same row contract as documents in REST and MCP.
Create/update accepts section-name arrays; omitted sections are preserved and `[]` clears them.
Catalog header and section replacements share one transaction, including the version check.

`POST /api/catalogs/{name}/{id}/duplicate` also copies tabular-section rows in their original order.
The copy receives fresh record and row IDs; secret attributes are omitted, as for documents.

### Standard information-register UI feed

`GET /api/ui/view/information-registers/{name}` returns `{informationRegister: {periodic, list}}`.
`GET /api/list/information-registers/{name}` supplies `{rows, nextCursor, hasMore, total?}` in the
same logical vocabulary as the information-register REST API. It accepts `cursor`, `limit`,
`sort` (a non-secret storage column), `dir`, `where`, the standard list `eq/in/like/prefix/ge/le`
filters and `count=exact`. Optional `at` selects the latest record per dimension tuple on a periodic
register; value filters apply after slicing. The slice is paged and does not use the legacy
5000-row slice cap. Defaults are period then ID descending (periodic) or ID descending.
Read roles and dimension record policies apply to both the surface and every feed window;
cursors are bound to filters, date, sort and caller. No write or record-detail surface is provided.
The canonical `/api/information-registers/{name}/records` and `/slice-last` contracts are unchanged.

Information-register reference columns retain the standard storage-ID sort order.
