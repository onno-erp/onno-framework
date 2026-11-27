# Record-level access policies

Entity-level access (the application's `RoleGrants` bean: `read`, `write`, `post`, `unpost` per entity) decides *which entities* a role may
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

`Subject.recordId()` is the signed-in user's identity record — the row of the `IdentityLink` bean's
catalog matched by login (here `IdentityLink.of(Owner.class, "email")`). No switch turns policies
on: they are active as soon as `RecordAccessPolicy` beans exist.

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
`@McpTool` method may declare one too (or read `McpToolContext.subject()`); a command handler reads
`CommandContext.subject()`, which is never `null`. Code that builds a `CommandContext` itself (a test,
a custom dispatcher) passes the caller's subject, or `AccessSubject.system()` for trusted code.

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
2. A subject holding one of the policy's `exemptRoles(...)` is **exempt** (no role is exempt implicitly; trusted code is never scoped); exemption wins
   (staff who also hold `CUSTOMER` see everything).
3. Several applicable policies for one entity are **OR'ed** — each role grants a slice.
4. With no applicable policy the entity is unscoped; the `RoleGrants` entity grant stays the only gate.
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
| batch delete / batch commands | per id, same rules |
| commands | the record is loaded with the write scope (404); `availableWhen`/`enabledWhen` are evaluated server-side (409) |
| CSV import upserts | an existing code/number outside the write scope is a row error, not an overwrite |
| ref pickers, mentions, comments, tags, presence | target/record scope applied; unreadable records are 404 or not readable |
| notifications | a notification about a record the recipient can't read is withheld from its feed and stream (one rule for both), and the unread badge counts only what the feed shows |
| task inbox | task visibility stays assignment-based; a subject label of an unreadable record is masked |
| links to a renamed or removed entity | a former `previousNames` entry still resolves; a name that resolves to nothing is withheld/masked for a subject some policy narrows, never for trusted code or unnarrowed subjects |
| SSE `/api/events` | a record event reaches a scoped subscriber only if the record is in its scope (one query per event and distinct scope) |
| MCP built-in and command tools | run as the caller's subject through the same services |
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

Every check takes a non-null subject (`null` throws even before the first policy is declared).
`canReadRecord`/`canReadRoute` only narrow by record scope, for links stored as strings; they do not
check entity grants or existence for an unnarrowed subject, so authorize with `can`/`require`. Parse
such links with `RecordRoute.parse(route)`, and check many at once with
`recordAccess.readable(subject, routes)` (one query per narrowed entity).

Not covered in this release: field-level masking, and **media** (`/api/media/{key}` is not bound to a
record — do not store customer-private files as media when external users exist).

## Startup validation

Startup fails on an unknown entity or field, a scope over a secret or polymorphic-ref field, `via`
on a non-ref field, an inherited `via` cycle, a register scoped by a non-dimension, a literal that
can never match, a policy without a read scope, an `appliesTo` or `exemptRoles` role that is neither
a declared role nor a `fromIdp` name, and a **dead** policy (none of its `appliesTo` roles has a
read grant on the entity in `RoleGrants`). Neither role check applies to an application without
grants.

Every scoped column gets an index (`idx_<table>_<column>`, shortened with a hash past 63
characters) when `onno.schema.mode` resolves to `apply` — the default; a blank value counts as
`apply`, as it does for the schema itself. Any other mode, or
`onno.access.records.create-indexes=false`, only logs the statements as a warning.

On PostgreSQL the indexes are reconciled on a background thread, off the startup path, so neither
startup nor writes to the table wait for them. A missing index is built with
`CREATE INDEX CONCURRENTLY`; an index left INVALID by an interrupted build
(`pg_index.indisvalid = false`) is dropped and rebuilt; a valid one issues no statement. An invalid
index under that name that is not a plain index on the column is left in place with a warning. One
node of a scaled-out deployment builds at a time, under a `pg_try_advisory_lock`. Until a build
finishes, scoped queries on that table may scan it.

Two concurrent index builds on one table, or a build and a concurrent drop, deadlock, and PostgreSQL
cancels one of them. So this reconciliation and the framework's search-index reconciliation take
turns under a shared advisory lock, on any node: each waits for the other rather than skip, and
holds no pooled connection while it waits. Before it builds or repairs an index, the thread also
waits while any other session — of any role — holds or awaits that table's
`SHARE UPDATE EXCLUSIVE` lock: for example a `CREATE INDEX CONCURRENTLY` or a concurrent drop run by
hand or by other tooling, or a manual `VACUUM`. Then the thread reads the index again, so an index
that the other build created under the same name is kept. A valid index needs no statement, so it
never waits. A table that stays busy for 30 minutes leaves its index for the next start, and a
search-index reconciliation that runs that long leaves all of them. Autovacuum is not waited for,
however long it runs: the build cancels it, as a build run by hand would, and waits for an
autovacuum that prevents wraparound without a deadlock. Only a build or drop that these locks do not
coordinate (run by hand or by other tooling), begun on the table while ours runs, can still collide
with it; if our index is the one left invalid, the next start repairs it.

The thread holds one pooled connection while it builds or waits for a busy table, and a build runs
with that connection's session settings. A `statement_timeout` or `lock_timeout` set for the
application's connections can cancel the build on a large table, and each start then drops the
invalid index and tries again. Lift those limits for the application role, or build that index once
by hand: drop the invalid one (`DROP INDEX CONCURRENTLY <name>`), then run the exact statement from
the warning about the cancelled build. A valid index is left alone.

A build also waits for transactions that write to that table, and for any transaction in the
database that still holds an older snapshot (REPEATABLE READ or SERIALIZABLE, an open cursor, a long
query). A session left idle in such a transaction stalls the build, and the builds queued after it,
until it ends; `idle_in_transaction_session_timeout` bounds that.

While a build runs, a schema change to that table waits for it to finish, and PostgreSQL queues
every read and write of the table behind the waiting change. So a deploy that alters the table
stalls both its own startup, where the schema change is applied, and the table until the build
ends. On a large table, let the build finish (the log shows `Built record-scope index …`) before
deploying a schema change to it.

On other databases the plain `CREATE INDEX IF NOT EXISTS` statements run during startup.
`onno.access.records.log-denials=true` logs every scope-based 404 at DEBUG on `su.onno.service.RecordAccess`.

## Verifying a policy

The framework ships a conformance suite (`RecordPolicyConformanceTest` in `onno-web-starter`): it
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
  survives thread hops (aggregate pools, batch fan-out, SSE fan-out, MCP).
- **404, not 403.** A record outside the scope is indistinguishable from a missing one, so ids can't
  be probed.
- **Write defaults to read.** The common case (owners change what they own) stays one line; read-only
  external access is an explicit `write(RecordScope.none())`.
- **Supersedes the one-off mechanisms.** `TagAccessPolicy` and CRM `readableWhen` keep working and run
  in addition; both are slated to move onto `RecordAccessPolicy`.
