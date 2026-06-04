# Headless Read API

The generic REST API under `/api/**` (served by `onec-ui-starter`) is admin-shaped: its JSON uses
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
ref pickers); without either parameter you get the full list.

## Response shape

Keys are **storage column names**, not Java field names. Framework columns are prefixed with `_`;
attribute columns are the `snake_case` of the field name (or the explicit `@Attribute(name=...)`).

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
  "region_ref": { "id": "a17c…", "display": "Madrid", "code": "R-01", "avatarUrl": null }
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

- `{column}_display` — a human-readable label (catalog description or code; enum value name).
- `{column}_ref` — an object `{ "display", "code", "avatarUrl" }` (catalogs) for richer rendering.

The raw `{column}` value remains the UUID, so writers can round-trip it unchanged.

## Secrets

Columns from a `@Attribute(secret = true)` field are **write-only**. On read they are replaced in
place with the sentinel string `__SECRET_SET__` when a value is stored, or `null` when empty — the
ciphertext is never returned. Submitting the sentinel back on a write means "leave unchanged".

## Filtering & deletion

`list` returns only live rows (`_deletion_mark = false`). Deletes are soft (the mark is set), so a
deleted row disappears from `list` but is still reachable by `get` until purged.

## Reacting to changes

Server-side consumers (cache/ISR revalidation, search indexing, outbox relays) should not poll. Every
write — through the generic controllers **and** through `repository.save(...)` — publishes a Spring
`EntityChangedEvent(changeType, entityType, entityName, id, naturalKey)`; the `naturalKey` is the
catalog code / document number, so a listener can revalidate a specific resource rather than
everything. The same event drives the browser live-update SSE stream (`GET /api/events`). See
[`com.onec.events.EntityChangedEvent`](../onec-framework/src/main/java/com/onec/events/EntityChangedEvent.java).

## Notes for a public read view

There is no separate "public projection" endpoint yet; the generic read API is the contract above and
is auth-gated. To expose a curated, anonymous read surface, front it with your own controller that
maps these column-name keys to your DTOs, and (if it also accepts writes) add its path to
`onec.auth.public-paths` and `onec.auth.csrf-ignored-paths`.
