# Record-level access policies

Entity-level RBAC (`@AccessControl(readRoles, writeRoles)`) decides *which entities* a role may
see. A **record access policy** decides *which records* of an entity a user may see and change — the
missing piece for apps with external users (a customer portal, a client seeing their own orders, a
tenant console). Policies are declared once, as Spring beans, and enforced by the framework on every
path where catalog, document or register data leaves the app or is changed on a user's behalf.

## Quick start

```java
@Bean
RecordAccessPolicy tenantsByOwner() {
    return RecordAccessPolicy.forCatalog(Tenant.class)
            .appliesTo("CUSTOMER")
            .read(RecordScope.eq("owner", Subject.recordId()))
            .write(RecordScope.eq("owner", Subject.recordId()))   // defaults to the read scope
            .defaults(d -> d.set("owner", Subject.recordId()));   // filled on create
}

@Bean
RecordAccessPolicy buildJobsByTenant() {
    return RecordAccessPolicy.forDocument(BuildJob.class)
            .appliesTo("CUSTOMER")
            .read(RecordScope.via("tenant"));                     // a job is visible when its tenant is
}
```

`Subject.recordId()` is the signed-in user's identity record — the `Layout.identity(...)` catalog
row matched by login (here an `Owner` catalog). No switch turns policies on: they are active as
soon as `RecordAccessPolicy` beans exist.

## Concepts

### The subject — who is asking

Every scoped read and write runs for an `AccessSubject`:

- `AccessSubject.User(username, roles, recordId, attributes)` — a signed-in user, resolved once per
  request by `AccessSubjectResolver`: login and roles as before, `recordId` from the identity link,
  and `attributes` from any `AccessSubjectContributor` beans.
- `AccessSubject.system()` — trusted code (posting, jobs, processes, migrations, connectors). Never
  scoped, and never inferred: a request without an authenticated user resolves to an anonymous user
  with no roles, not to `system()`.

App-defined attributes come from contributors:

```java
@Bean
AccessSubjectContributor regions(RegionDirectory directory) {
    return user -> Map.of("regions", directory.regionsOf(user.username()));
}
```

A controller method receives the subject by declaring a parameter of type `AccessSubject`; an
`@McpTool` method may declare one too (or read `McpToolContext.subject()`); an action handler reads
`ActionContext.subject()`.

### The scope — a predicate the database evaluates

A `RecordScope` is a small closed predicate tree, not a Java lambda, so it compiles into the SQL
`WHERE` and composes with keyset paging, counts, groups and aggregates.

| Scope | Meaning |
|---|---|
| `RecordScope.eq("owner", Subject.recordId())` | field equals a subject value |
| `RecordScope.eq("status", Status.ACTIVE)` | field equals a literal (enums bind as their stored UUID) |
| `RecordScope.in("region", Subject.attribute("regions"))` | field in a collection |
| `RecordScope.isNull("archivedAt")` | field is null |
| `RecordScope.via("tenant")` | the referenced record must be readable under *its own* policies |
| `RecordScope.via("tenant", scope)` | the referenced record must satisfy `scope` |
| `and(…)`, `or(…)`, `not(…)`, `all()`, `none()` | composition |

Field names are logical attribute names (plus `id`, and `code`/`description`/`parent` on catalogs,
`number`/`date`/`posted` on documents). Values are literals or `Subject` placeholders; everything
binds as a parameter. A placeholder the subject cannot supply (no identity record, attribute not
contributed) makes its predicate **unknown** — SQL three-valued logic — so it is false, also under
`not(…)`. A scope fails closed, never open.

Register policies scope by **dimension** fields (directly or through `via`), which exist in both the
movement and the totals table, so balances read from the totals table stay correct. Tabular sections
inherit their document's scope.

### The policy — who it applies to

1. A policy **applies** to a subject holding at least one of its `appliesTo(...)` roles.
2. A subject holding `ADMIN` or one of the policy's `exemptRoles(...)` is **exempt**; exemption wins
   (staff who also hold `CUSTOMER` see everything).
3. Several applicable policies for one entity are **OR'ed** — each role grants a slice.
4. With no applicable policy the entity is unscoped; `@AccessControl` stays the only gate.
5. A policy never widens access: the entity-level grant is checked first.

`write(...)` defaults to the read scope; `write(RecordScope.none())` allows changes only through
trusted code (actions, processes). `defaults(...)` fills fields on create — the New form shows them
too, and an explicit body value cannot override them.

## What is enforced where

| Path | Behaviour |
|---|---|
| lists, counts, groups, aggregates, chart buckets, KPI tiles | scope ANDed into the `WHERE` before `GROUP BY`/aggregation |
| `get(id)`, detail surfaces, duplicate | outside the scope → **404**, same as missing |
| `rows?ids=` live patches | out-of-scope ids are silently dropped |
| `count=estimate` | a scoped subject gets the exact scoped count (planner statistics would reveal the table size) |
| trees / children | scope per node; an in-scope child of an out-of-scope parent is returned as a root, `parent` withheld (`parentRestricted: true`) |
| free-text search over refs | the inner `EXISTS` carries the target's scope and read grant — search is never an oracle |
| ref display (`*Display`, `*Ref`) | a ref the viewer may not read renders restricted: `{field}Restricted: true`, display `—`, id withheld |
| keyset cursors | bound to the subject (`…~fingerprint`); replaying another subject's cursor is a 400 |
| update / delete / post / unpost | the record must be in the **write** scope (404 otherwise) |
| create / update post-image | must satisfy the write scope (403, rolled back) |
| ref values written (header and lines) | must point at readable records (422) |
| a restricted ref echoed back as `null` on update | ignored — a link you can't see can't be cleared by accident |
| batch delete / batch actions | per id, same rules |
| actions | the record is loaded with the write scope (404); `enabledWhen` is evaluated server-side (409) |
| CSV import upserts | an existing code/number outside the write scope is a row error, not an overwrite |
| related lists | the parent record must be readable; junction rows are scoped |
| ref pickers, mentions, comments, tags, presence | target/record scope applied; unreadable records are 404 or not readable |
| notifications | a notification about a record the recipient can't read is withheld from its feed and stream |
| task inbox | task visibility stays assignment-based; a subject label of an unreadable record is masked |
| SSE `/api/events` | a record event reaches a scoped subscriber only if the record is in its scope (one query per event and distinct scope) |
| MCP built-in tools | run as the caller's subject through the same services |
| CRM inbox | the bound customer catalog's policy narrows readable contacts (on top of `readableWhen`) |

## What is *not* scoped — trusted code

Typed repositories (`CatalogRepository`, `DocumentRepository`, `RegisterRepository`), posting,
process engine internals, scheduled jobs, migrations, Kafka relays and CRM channel transports run as
`AccessSubject.system()` and see everything. **Code that serves external users from typed repositories
must check through `RecordAccess`:**

```java
recordAccess.require(subject, Tenant.class, id, AccessMode.READ);   // 404 if not
boolean ok = recordAccess.can(subject, Tenant.class, id, AccessMode.WRITE);
Set<UUID> visible = recordAccess.filter(subject, Tenant.class, ids, AccessMode.READ);
ScopeClause clause = recordAccess.clause(subject, Tenant.class, AccessMode.READ, "t"); // for own SQL
```

Not covered in this release: field-level masking, and **media** (`/api/media/{key}` is not bound to a
record — do not store customer-private files as media when external users exist).

## Startup validation

Startup fails on an unknown entity or field, a scope over a secret or polymorphic-ref field, `via`
on a non-ref field, an inherited `via` cycle, a register scoped by a non-dimension, a literal that
can never match, a policy without a read scope, and a **dead** policy (none of its `appliesTo` roles
may read the entity at all).

Every scoped column gets an index (`CREATE INDEX IF NOT EXISTS idx_<table>_<column>`) at startup
when `onno.schema.mode=apply`; otherwise the statements are logged as a warning.
`onno.access.records.log-denials=true` logs every scope-based 404 at DEBUG on `su.onno.ui.RecordAccess`.

## Verifying a policy

The framework ships a conformance suite (`RecordPolicyConformanceTest` in `onno-ui-starter`): it
seeds two owners, marks every record of owner B with a `LEAK-B` sentinel, drives every endpoint as
owner A, and fails if any response contains a sentinel or one of B's ids. Its coverage guard fails the
build when a new endpoint is added without a record-policy classification. Copy the pattern for your
own app's custom endpoints.

## Rationale

- **A predicate tree, not a lambda.** A row lambda can only filter rows already loaded — it breaks
  paging, counts and aggregates and invites leaks through every path that forgets to apply it. A
  closed AST compiles to SQL, validates at boot, and has one in-memory evaluator with identical
  three-valued semantics.
- **A required subject, not a thread-local.** Every query/command service method takes the subject
  as a parameter, so forgetting it is a compile error rather than a silent unscoped read, and it
  survives thread hops (dashboard widget pools, batch fan-out, SSE fan-out, MCP).
- **404, not 403.** A record outside the scope is indistinguishable from a missing one, so ids can't
  be probed.
- **Write defaults to read.** The common case (owners change what they own) stays one line; read-only
  external access is an explicit `write(RecordScope.none())`.
- **Supersedes the one-off mechanisms.** `TagAccessPolicy` and CRM `readableWhen` keep working and run
  in addition; both are slated to move onto `RecordAccessPolicy`.
