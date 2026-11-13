# Period close

A period close is a change-prohibition date ("закрытие периода"): a document dated **on or before**
the close date cannot be created, changed, delete-marked, posted, reposted or unposted. With no
period closed nothing is rejected, so the feature costs nothing until an administrator uses it.

```java
@Autowired PeriodCloseService periods;

periods.close(PeriodScope.global(), LocalDate.of(2026, 10, 31), "admin", "October closed");
periods.close(PeriodScope.context("Finance"), LocalDate.of(2026, 11, 30), "admin", null);
periods.close(PeriodScope.register("Cash"), LocalDate.of(2026, 11, 30), "admin", null);

periods.reopen(PeriodScope.global(), LocalDate.of(2026, 9, 30), "admin", "late supplier invoice");
periods.reopen(PeriodScope.context("Finance"), null, "admin", "audit correction"); // fully open
```

## Scopes

| Scope | Applies to |
| --- | --- |
| `PeriodScope.global()` | every document |
| `PeriodScope.context("Finance")` | documents declared with `context = "Finance"`; when posting, also documents that move a register declared in that context |
| `PeriodScope.register("Cash")` | posting, reposting and unposting documents that move (or already moved) the accumulation register named `Cash` |

Several scopes can cover one document; the latest close date wins. The close date is inclusive:
closing through `2026-10-31` rejects a document dated `2026-10-31T23:59:59` and accepts
`2026-11-01T00:00`. Both the stored date and the date being written are checked, so a document can
be moved neither out of nor into a closed period.

Annotate an operational document whose date is not an accounting date (a calendar event, a chat
message) with `@PeriodCloseExempt`; it is never checked.

## Where it is enforced

The check lives below controllers, in three places that together cover UI, REST, import, MCP, jobs
and application services:

- **`PostingEngine`** — `post`, `repost`, `unpost`, inside the posting transaction. Chronological
  restoration (`PostingOrder.CHRONOLOGICAL`) checks every later document it would repost, so a
  backdated change in an open context is rejected when its cascade would recalculate a document
  closed by another context or register. The whole restoration rolls back.
- **Repository saves** — `OnnoBeforeConvertCallback`, including the soft delete.
- **The generic command path** — `DocumentCommandService` create/update/delete, which serves the UI,
  REST, CSV import and MCP.

A rejection throws `PeriodClosedException`, naming the closed period and the remediation:

```text
Period is closed through 2026-10-31 for all documents: cannot post Sales Orders dated 2026-10-15.
Use a date after 2026-10-31, or ask an administrator to reopen the period.
```

Over HTTP it is `409` with `{error: "period-closed", message, scope, closedThrough, operation,
documentDate}`. `GET /api/documents/{name}/{id}/posting-preview` reports it the same way, so the
user learns the post would be rejected before trying.

## Roles, override and audit

- **Close / reopen** — `ADMIN`, plus the roles in `onno.period-close.manage-roles`. A reopen needs
  a reason; `close` refuses to move a date back.
- **Override** — signed-in users holding a role from `onno.period-close.override-roles` may change
  a closed period without reopening it. The list is empty by default: nobody is exempt, not even
  `ADMIN` (list `ADMIN` to exempt administrators). Trusted background code is never exempt
  implicitly; wrap it explicitly:

  ```java
  periods.override(new PeriodOverride("month-end-job", "revaluation"), () -> posting.repost(document));
  ```

- **Audit** — every close, reopen and bypassed operation is a row in `onno_period_close_audit`
  (`periods.audit(limit)`, `GET /api/period-close/audit`): who, when, scope, previous and new date,
  reason, and for an override the operation and document.

## Administration UI and API

The UI starter registers a page at `/period-close` showing the closes in force, **Close period** and
**Reopen period** buttons (manage roles only) and the recent audit trail. Navigation is curated, so
link it from a layout:

```java
spec.section("Finance").page(PeriodClosePage.ROUTE, "Period close", "lock");
```

```text
GET  /api/period-close                 closes in force + canManage     (any signed-in user)
GET  /api/period-close/audit?limit=100 audit trail                     (manage roles)
POST /api/period-close/close           {scope, closedThrough, reason?} (manage roles, CSRF)
POST /api/period-close/reopen          {scope, closedThrough?, reason} (manage roles, CSRF)
```

`scope` is `global`, `context:<name>` or `register:<name>`.

## Storage and limits

`PeriodCloseService` owns two tables it creates on startup (`onno_period_close`,
`onno_period_close_audit`); they are outside the schema reconciler. Close dates are read from the
database on every guarded operation, so a close on one node applies on all. Set
`onno.period-close.enabled=false` to drop the service and every check.

Not covered yet: per-organization scope, soft-closed (warn-only) periods, explicit adjustment
documents, direct `RegisterRepository` writes outside posting, information registers, and an MCP
tool for closing. The check does not lock the close row, so an operation already past its check
when a close commits still completes.
