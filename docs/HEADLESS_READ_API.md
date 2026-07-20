# Headless Read API

The generic REST API under `/api/**` (served by `onno-ui-starter`) is admin-shaped: its JSON uses
raw storage column names (`_id`, `_code`, …), expands references inline, and redacts secrets. This
document is the response contract for headless consumers (a separate front end, a sync job, a search
indexer) so you don't have to learn the shape by reading controller source (issue #33).

It pairs with the auth/CSRF notes in [AGENTS.md](../AGENTS.md#inspecting-a-running-app-read-this-before-you-curl):
every `/api/**` route is authenticated, reads need only the session cookie (or a bearer token in
`resource-server` mode), and `{name}` is the entity's **display/logical name** (e.g. `Properties`,
`Reservations`) — not the Java class name.

## Endpoints

```text
GET /api/catalogs/{name}                 list (live rows only)
GET /api/catalogs/{name}/{id}            one catalog row
GET /api/catalogs/{name}/children?parent={uuid}   hierarchical children (hierarchical catalogs)
GET /api/catalogs/{name}/tree            full hierarchy as nested `children` arrays
GET /api/documents/{name}?from=&to=      list (optional date range)
GET /api/documents/{name}/{id}           one document, with tabular sections inlined
GET /api/registers/{name}/movements
GET /api/registers/{name}/balance
GET /api/registers/{name}/turnover?from=&to=
```

Both list endpoints also accept `?q=<text>&limit=<n>` to switch to a capped typeahead search (used by
ref pickers); without either parameter you get the full list, **capped at 1000 rows** as a safety
limit (catalogs ordered by `_code`, documents newest first). Past the cap the server logs a warning
and truncates; consumers that need everything should page the **keyset list feed**
(`GET /api/list/catalogs/{name}?cursor=&limit=` / `/api/list/documents/{name}?cursor=&limit=`,
returning `{ rows, nextCursor, hasMore }` — loop while `nextCursor` is non-null) or, for documents,
narrow the date range. See [onno-ui-starter/README](../onno-ui-starter/README.md) for the full
keyset contract.

## Response shape

Keys are **storage column names**, not Java field names. Framework columns are prefixed with `_`;
attribute columns are the `snake_case` of the field name (or the explicit `@Attribute(name=...)`).
Reading `description` (no underscore) or a camelCase `taxId` returns `undefined` — the read keys are:

| Key | Applies to | Meaning |
|-----|------------|---------|
| `_id` | catalog, document, TS row | UUID primary key |
| `_code` | catalog | natural key / slug |
| `_number` | document | natural key / slug |
| `_date` | document | timestamp |
| `_posted` | document | posting flag |
| `_description` | catalog | **the display name** (there is no `description` key) |
| `_deletion_mark` | catalog, document | soft-delete flag |
| `_is_folder`, `_parent` | hierarchical catalog | folder flag / parent UUID |
| `_version` | catalog, document | optimistic-lock version |
| `_parent_id`, `_line_number` | tabular-section row | back-reference to the document / 1-based ordinal |
| `_period`, `_active` | register rows | period / active flag |
| `<snake_col>` | attribute | `snake_case(fieldName)` (or `@Attribute(name)`); a `Ref<>`/enum is stored as its UUID |
| `<col>_display` | `Ref<>` & enum attrs | resolved human label |
| `<col>_ref` | `Ref<>` attrs | `{ id, type, display, code?, avatarUrl?, color? }` |
| `<col>_code` | catalog-`Ref<>` attrs only | the target's code |
| `<col>_avatar` | catalog-`Ref<>` attrs only | the target's `avatar_url` |
| `<col>_color` | enum & catalog-`Ref<>` attrs | `@EnumLabel(color)` hex, or the ref target's `color` column — a status pill |

### Temporal values

Temporal columns are database-independent ISO strings:

| Java type | Read representation | Accepted write representation |
|-----------|---------------------|-------------------------------|
| `LocalDate` | `yyyy-MM-dd` | `yyyy-MM-dd` |
| `LocalDateTime` | offset-free `yyyy-MM-ddTHH:mm[:ss[.fraction]]` | the same; offset-bearing ISO (`Z`, `+03:00`) is also accepted |

`LocalDateTime` is a business wall-clock value, not an instant. If a write includes an offset, the
offset is treated as transport decoration and the local fields are preserved without conversion:
`2026-06-04T10:00+03:00` stores and reads back as `2026-06-04T10:00`.

PostgreSQL/JDBC may internally expose timestamps as `Timestamp` or offset-bearing values, but the
read API normalizes those before JSON serialization. The generated form also canonicalizes loaded
catalog, document, and tabular-section temporal values before save. Headless callers must still map
the snake_case read key to the camelCase write field:

```jsonc
// GET /api/documents/Events/{id}
{ "starts_at": "2026-06-04T10:00" }

// PUT /api/documents/Events/{id}
{ "startsAt": "2026-06-04T10:00" }
```

### Catalog row

```jsonc
{
  "_id": "f3b1…",            // UUID primary key
  "_code": "C-000123",        // natural key / slug
  "_description": "Acme Corp",
  "_deletion_mark": false,
  "_is_folder": false,
  "_parent": null,            // UUID of parent folder (hierarchical catalogs)
  "_version": 3,              // optimistic-lock version
  "tax_id": "B12345678",      // attribute column (field `taxId`)
  "region": "a17c…",          // a Ref<> / enum attribute is stored as a UUID
  "region_display": "Madrid", // + resolved display (see "Reference & enum expansion")
  "region_ref": { "id": "a17c…", "type": "catalog", "display": "Madrid", "code": "R-01", "avatarUrl": null },
  "region_code": "R-01"       // catalog-ref only; + region_avatar when the target has one
}
```

### Document row

```jsonc
{
  "_id": "…",
  "_number": "SO-00042",      // natural key / slug
  "_date": "2026-06-04T10:00:00",
  "_posted": true,
  "_deletion_mark": false,
  "_version": 1,
  "customer": "…",            // Ref<> UUID (+ customer_display / customer_ref)
  "items": [                  // tabular section, keyed by its section name — GET /{id} only
    {
      "_id": "…",
      "_parent_id": "…",      // back-reference to the document
      "_line_number": 1,
      "product": "…",         // row attribute columns, same conventions
      "product_display": "Widget",
      "quantity": 3
    }
  ]
}
```

`list` returns a JSON array and does **not** inline tabular sections; `get` returns a single object
and **does**. `get` returns `404` when the id is unknown.

## Reference & enum expansion

A `Ref<>` or `@Enumeration` attribute is stored as a UUID. For each such column the read layer adds
two sibling keys so the client need not make a second call:

- `{column}_display` — a human-readable label (catalog description or code; for an enum, the value's
  `@EnumLabel`, falling back to the constant name when unlabelled).
- `{column}_ref` — an object `{ id, type, display, code?, avatarUrl?, color? }` for richer rendering
  (`type` is `catalog`/`document`; `code`/`avatarUrl`/`color` present for catalog refs). Document
  refs get only `_display` + `_ref`.
- `{column}_code` — catalog refs only: the target's code.
- `{column}_avatar` — catalog refs only: the target's `avatar_url`, when it has one.
- `{column}_color` — a badge colour (CSS hex) the client paints as a status pill. Emitted for an
  **enum** value declaring `@EnumLabel(color="#…")`, and for a **catalog ref** whose target declares
  a `color` attribute (column-name convention, like `avatar_url`) holding a non-blank value — this
  is what lets a user-editable status *catalog* keep the colored pills a status enum had. Absent
  when there is no colour.

The raw `{column}` value remains the UUID, so writers can round-trip it unchanged.

## Secrets

Columns from a `@Attribute(secret = true)` field are **write-only**. On read they are replaced in
place with the sentinel string `__SECRET_SET__` when a value is stored, or `null` when empty — the
ciphertext is never returned. Submitting the sentinel back on a write means "leave unchanged".

## Writes (partial, camelCase)

Writes are the mirror image of reads, and two things surprise people:

- **Request bodies use camelCase `fieldName`, not the snake_case read columns.** You read
  `tax_id` / `region_display`, but you write `{ "taxId": "…" }`. System fields are the logical names
  too: catalog `code` / `description` / `folder` / `parent` / `version`; document `number` / `date` /
  `version` (`_version` is also accepted). A `Ref<>`/enum is written as its bare UUID string.
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
POST /api/documents/{name}/{id}/post      post (re-post first unposts, then posts)
```

Two more contracts worth knowing:

- **Tabular sections replace, they don't merge.** Submitting a section (keyed by its section name)
  deletes and re-inserts that whole section; a section absent from the body is left untouched.
- **Lifecycle hooks re-run on every write.** `beforeWrite` runs on create *and* update (and
  `onFilling` on create); `beforePost` runs on post. Don't assume they only fire once.

## Filtering & deletion

`list` returns only live rows (`_deletion_mark = false`). Deletes are soft (the mark is set), so a
deleted row disappears from `list` but is still reachable by `get` until purged.

## Reacting to changes

Server-side consumers (cache/ISR revalidation, search indexing, outbox relays) should not poll. Every
write — through the generic controllers **and** through `repository.save(...)` — publishes a Spring
`EntityChangedEvent(changeType, entityType, entityName, id, naturalKey)`; the `naturalKey` is the
catalog code / document number, so a listener can revalidate a specific resource rather than
everything. The same event drives the browser live-update SSE stream (`GET /api/events`). See
[`su.onno.events.EntityChangedEvent`](../onno-framework/src/main/java/su/onno/events/EntityChangedEvent.java).

If you consume `/api/events` with a browser `EventSource`, note the stream sends **named** events
(the change type: `created` / `updated` / `deleted` / `posted` / `unposted` / `changed`, plus
`ready` / `presence` / `notification`) — never the default unnamed `message`. So `EventSource.onmessage`
fires for nothing; you must `addEventListener("updated", …)` (etc.) per event name you care about.

## Notes for a public read view

There is no separate "public projection" endpoint yet; the generic read API is the contract above and
is auth-gated. To expose a curated, anonymous read surface, front it with your own controller that
maps these column-name keys to your DTOs, and (if it also accepts writes) add its path to
`onno.auth.public-paths` and `onno.auth.csrf-ignored-paths`.
