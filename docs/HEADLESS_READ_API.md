# Headless Read API

The generic REST API under `/api/**` (served by `onno-ui-starter`) gives catalog and document
consumers a logical JSON contract: system fields and model attributes use the same names on reads
and writes (`id`, `description`, `taxId`, …), references expand alongside them, and secrets are
redacted. The previous storage-shaped contract remains available with
`?representation=storage`. This document is the response contract for headless consumers (a
separate front end, a sync job, a search indexer) so you don't have to learn the shape by reading
controller source (issues #33 and #314).

It pairs with the auth/CSRF notes in [AGENTS.md](../AGENTS.md#inspecting-a-running-app-read-this-before-you-curl):
every `/api/**` route is authenticated, reads need only the session cookie (or a bearer token in
`resource-server` mode), and `{name}` is the entity's annotation **logical name** (e.g. `Books`,
`SalesOrders`) — not the Java class name or localized `title`.

## Endpoints

```text
GET /api/catalogs/{name}/{id}            one catalog row
GET /api/catalogs/{name}/children?parent={uuid}   hierarchical children (hierarchical catalogs)
GET /api/catalogs/{name}/tree            full hierarchy as nested `children` arrays
GET /api/documents/{name}/{id}           one document, with tabular sections inlined
GET /api/list/catalogs/{name}?cursor=&limit=       one keyset-paginated collection window
GET /api/list/documents/{name}?cursor=&limit=&from=&to=  one document window
POST /api/ref-options/search                  bounded contextual reference options
GET /api/registers/{name}/movements
GET /api/registers/{name}/balance
GET /api/registers/{name}/turnover?from=&to=
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

Process actors are always derived from the authenticated principal; usernames and roles are never
accepted from the request body. Starting is authorized by the definition's typed
`startAssignment(payload)`. `GET /api/tasks` returns open candidate work plus work already
claimed by the caller. `ADMIN` is the process superuser. Completion outcome names are checked
against the active `HumanTask`'s declared enum before the persisted graph advances. Process
mutations use the same CSRF requirements as other session-authenticated `/api/**` writes.
Only a claimed task's current assignee (or `ADMIN`) may delegate it; a nonblank reason is required
and the transfer is durable. `GET /api/task-assignees?q=` searches the configured layout identity
catalog and returns `{actorId, username, display, avatarUrl}`. `actorId` is the stable catalog record
UUID; username/display/avatar are live presentation data. `avatarUrl` is present when the identity
catalog has an attribute configured with the `avatar` or `image` field widget.

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
[{
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
}]
```

Action handlers use the successful response `{refresh, feedback}`. Feedback has `severity`
(`info|success|warning|error`) and `presentation` (`toast|dialog|inline`). Text is structured through
`ActionToast`, `ActionDialog`, or `ActionFeedback`; navigation is static action declaration
metadata, not a handler response. An expected `ActionRejectedException` is HTTP 422 whose body is
the feedback object itself:

```json
{
  "severity": "error",
  "presentation": "inline",
  "title": "Approval blocked",
  "message": "The room is occupied",
  "details": ["Main Stage · Onegin · 18:00–21:00"],
  "fieldErrors": { "reason": ["Only soft conflicts may be justified"] },
  "formErrors": ["A justification cannot override a hard conflict"],
  "dismissLabel": null,
  "keepFormOpen": true
}
```

Catalog and document collections are available only through the keyset list feed. It returns
`{rows, nextCursor, hasMore}`; start without a cursor and replay each opaque `nextCursor` until
`hasMore` is false. Ref pickers use `POST /api/ref-options/search`, which accepts bounded,
context-aware search input. There is no unbounded collection or offset-paging endpoint. See
[onno-ui-starter/README](../onno-ui-starter/README.md) for the complete list contract.

Catalog/document single-record, hierarchy, related-catalog, list-feed, reference-option,
create/update/duplicate, and post/unpost entity responses all accept
`?representation=logical|storage`. Omitted means `logical`; an unknown value is `400`. Registers
and aggregate/group responses retain their own storage/aggregation contracts and do not use this
switch.

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
| `parentId`, `lineNumber` | tabular-section row | back-reference to the document / 1-based ordinal |
| `<fieldName>` | attribute | Java field name; a `Ref<>`/enum is a UUID, a `PolyRef` is `JavaType\|UUID` |
| `<fieldName>Display` | `Ref<>`, `PolyRef` & enum attrs | resolved human label |
| `<fieldName>Ref` | `Ref<>`/`PolyRef` attrs | `{ id, type, display, kind?, javaType?, code?, avatarUrl?, color? }`; `type` is the target's logical name, while `kind`/`javaType` are present for polymorphic refs |
| `<fieldName>Code` | catalog-`Ref<>` attrs only | the target's code |
| `<fieldName>Avatar` | catalog-`Ref<>` attrs only | the target's `avatar_url` |
| `<fieldName>Color` | enum & catalog-`Ref<>` attrs | `@EnumLabel(color)` hex, or the ref target's `color` column — a status pill |

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

List-feed rows do **not** inline tabular sections; single-document reads do. A single-record read
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
`{column}_ref`, `{column}_code`, `{column}_avatar`, and `{column}_color` names.

## Secrets

Columns from a `@Attribute(secret = true)` field are **write-only**. On read they are replaced in
place with the sentinel string `__SECRET_SET__` when a value is stored, or `null` when empty — the
ciphertext is never returned. Submitting the sentinel back on a write means "leave unchanged".
Secret attributes are also excluded from every catalog/document list-query allowlist: clients cannot
filter, sort, group, or aggregate by them. This prevents row membership or counts from becoming a
blind oracle for encrypted values.

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

Two more contracts worth knowing:

- **Tabular sections replace, they don't merge.** Submitting a section (keyed by its section name)
  deletes and re-inserts that whole section; a section absent from the body is left untouched.
- **Lifecycle hooks re-run on every write.** `beforeWrite` runs on create *and* update (and
  `onFilling` on create), then `afterWrite` runs after a successful persist; validation previews do
  not call `afterWrite`. `beforePost` runs on post/repost. Don't assume hooks only fire once.

## Filtering & deletion

`list` returns only live rows (`deletionMark = false`; `_deletion_mark` in storage representation).
Deletes are soft (the mark is set), so a
deleted row disappears from `list` but is still reachable by `get` until purged. Spring Data
`CatalogRepository`/`DocumentRepository` delete methods use the same tombstone contract; posted
documents must be unposted before repository deletion.

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

## Notes for a public read view

There is no separate "public projection" endpoint yet; the generic read API is the contract above and
is auth-gated. To expose a curated, anonymous read surface, front it with your own controller that
maps the logical entity to your DTOs, and (if it also accepts writes) add its path to
`onno.auth.public-paths` and `onno.auth.csrf-ignored-paths`.
