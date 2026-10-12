# onno 3.4.0

Record-level access policies: one declarative, query-pushed-down rule for *which records* a user
may see and change, enforced on every generic surface. Apps with external users — customer portals,
client consoles — no longer need bespoke endpoints and a second SPA.

## Record-level access policies

```java
@Bean
RecordAccessPolicy tenantsByOwner() {
    return RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
            .read(RecordScope.eq("owner", Subject.recordId()))
            .defaults(d -> d.set("owner", Subject.recordId()));
}

@Bean
RecordAccessPolicy jobsByTenant() {
    return RecordAccessPolicy.forDocument(BuildJob.class).appliesTo("CUSTOMER")
            .read(RecordScope.via("tenant"));
}
```

- New core API in `su.onno.access`: `AccessSubject` (`User` / `system()`), `RecordScope`
  (`eq`, `in`, `isNull`, `via`, `and`, `or`, `not`, `all`, `none`), `Subject` placeholders
  (`recordId()`, `username()`, `attribute(name)`), `RecordAccessPolicy`, `AccessSubjectContributor`,
  `RecordPolicies` and an in-memory `RecordScopeEvaluator`.
- The scope is compiled into SQL and ANDed into every list, count, group, aggregate, chart, KPI,
  search, tree, related list and `get`. Out-of-scope records are 404, like missing ones.
- Writes: existing records must be in the write scope; created/updated records must still satisfy
  it (rolled back otherwise); written references must be readable; policy defaults fill creates.
- Also covered: actions (record loaded with the write scope; `enabledWhen` enforced server-side),
  batch commands, CSV import upserts, ref pickers, mentions, comments, tags, presence,
  notifications, task subject labels, SSE delivery, MCP tools and the CRM inbox's customer catalog.
- `RecordAccess` bean for application code (custom controllers, page actions, widget backends,
  `@McpTool`s): `require`, `can`, `filter`, `matches`, `clause`.
- Startup validation (unknown fields, `via` cycles, dead policies, …) and automatic indexes for scoped
  columns. New property `onno.access.records.log-denials`, `onno.access.records.create-indexes`.
- A conformance suite (leak test + route coverage guard) and an architecture guard ship with the
  UI starter.

See the [record access policies guide](RECORD_ACCESS_POLICIES.md).

## Security fixes

- **Ref display no longer leaks unreadable records.** `{field}_display` / `{field}_ref` were resolved
  for every viewer regardless of access. A ref whose target entity the viewer may not read (or whose
  record is outside the viewer's scope) now renders *restricted*: `{field}Restricted: true`, display
  `—`, id withheld; the UI shows a neutral "Restricted" chip. **This is a visible change even for
  apps without policies**: a user without read access to, say, `Employee` now sees assignee refs as
  restricted.
- **`enabledWhen` is enforced on the server.** A disabled row/detail action can no longer be run by
  replaying its request (409).
- Free-text search over references only looks through targets the viewer may read.

## Fixes

- The `AccessSubject` controller parameter is resolved ahead of Spring Data's projection resolver
  (which would otherwise bind any interface-typed parameter to an empty proxy).
- A ref-picker search without an option decorator no longer fails with a `NullPointerException`.
- List search over a self-referencing ref (`Employee.manager`) now qualifies the outer column.

## Known gaps

- **Media** (`/api/media/{key}`) is not record-bound and is served `Cache-Control: public`. Do not
  store customer-private files as media when external users exist.
- Field-level masking is not part of this release.
- Refs inside tabular-section rows that point at unreadable records are masked on read, but a full
  section replace on update rewrites them from what the client sends.

## Upgrade requirements

1. **UI starter query/command services take an `AccessSubject`.** `CatalogQueryService`,
   `DocumentQueryService`, `RegisterQueryService`, `InformationRegisterQueryService` methods take the
   subject as their first parameter; `CatalogCommandService` / `DocumentCommandService` take it in
   place of the `Principal`. Trusted code passes `AccessSubject.system()`; request code declares an
   `AccessSubject` controller parameter or resolves one with `AccessSubjectResolver`. The old
   signatures are removed, not overloaded, so a forgotten subject is a compile error.
2. `RefResolver.resolveAttributes(rows, attributes)` takes the subject too.
3. `ActionContext` gained a `subject` component (the old constructors remain); `McpToolContext`
   gained `subject`.
4. If users relied on seeing display names of refs into entities they cannot read, grant those
   roles read access (`@AccessControl(readRoles = …)`).
5. Clients that replay keyset cursors across users get a 400 for record-scoped users.

## 3.4.1

- **Profile-specific views now shape the record surface, not only the list.** An `EntityView` with
  `profile()` set used to change that profile's list columns while the record form, its field hints,
  `detail(...)` widgets, action placement and form validations still came from the default view — so
  an external-user persona (e.g. a customer console) was shown the back-office record form. The
  record surface (`/catalogs|documents/{name}/{id}`, `/new`, `/duplicate`) and the list's field
  metadata now resolve per profile with the same rule lists already used: the profile's own view
  wins as a whole, otherwise the default view applies. Apps without profile-specific views are
  unaffected. `ResolvedMetadataService.describeCatalog/describeDocument/actionOverrides` gained
  profile-taking overloads; the existing signatures keep returning the default view.
