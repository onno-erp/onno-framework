# Period close

A period close is a change-prohibition date ("закрытие периода"): a document dated **on or before**
the close date cannot be created, changed, delete-marked, posted, reposted or unposted. With no
period closed nothing is rejected, so the feature costs nothing until an administrator uses it.

```java
@Autowired PeriodCloseService periods;

periods.close(PeriodScope.global(), LocalDate.of(2026, 10, 31), "admin", "October closed");
periods.close(PeriodScope.context("Finance"), LocalDate.of(2026, 11, 30), "admin", null);
periods.close(PeriodScope.register("Cash"), LocalDate.of(2026, 11, 30), "admin", null);
periods.close(PeriodScope.organization(clinicRef), LocalDate.of(2026, 11, 30), true, "admin", "audited"); // hard

periods.reopen(PeriodScope.global(), LocalDate.of(2026, 9, 30), "admin", "late supplier invoice");
periods.reopen(PeriodScope.context("Finance"), null, "admin", "audit correction"); // fully open
```

## Scopes

| Scope | Applies to |
| --- | --- |
| `PeriodScope.global()` | every document |
| `PeriodScope.context("Finance")` | documents declared with `context = "Finance"`; when posting, also documents that move a register declared in that context |
| `PeriodScope.register("Cash")` | posting, reposting and unposting documents that move (or already moved) the accumulation register named `Cash` |
| `PeriodScope.organization(ref)` | documents whose `@PeriodCloseOrganization` field holds that organization (a `Ref`, UUID or string; stored by id) |

Several scopes can cover one document; the latest close date names the period in the rejection.

Multi-organization scope is optional: mark the field that names a document's organization (legal
entity, branch, clinic) and close per organization. Documents without the field are covered only by
global, context and register closes. Both the stored and the written organization are checked, so a
document can be moved neither out of nor into a closed organization.

```java
@PeriodCloseOrganization
@Attribute(displayName = "Organization")
private Ref<Organization> organization;
``` The close date is inclusive:
closing through `2026-10-31` rejects a document dated `2026-10-31T23:59:59` and accepts
`2026-11-01T00:00`. Both the stored date and the date being written are checked, so a document can
be moved neither out of nor into a closed period.

Annotate an operational document whose date is not an accounting date (a calendar event, a chat
message) with `@PeriodCloseExempt`; it is never checked.

## Soft and hard closes, adjustment documents

A close is **soft** by default. A soft-closed period stays locked for ordinary documents but still
admits:

- **Adjustment documents** — document types annotated `@PeriodAdjustment` (a correction, a
  revaluation) may be created, changed, posted and unposted inside the period. Each such operation
  is audited as `ADJUSTMENT` with the signed-in user. Every other document stays locked, so allowing
  adjustments never allows arbitrary edits; restrict who may write the adjustment type with
  `@AccessControl`. A backdated adjustment whose `CHRONOLOGICAL` restoration would repost an
  ordinary document inside a closed period is still rejected.
- **Overrides** (below).

A **hard** close (`close(scope, date, true, actor, reason)`, or **Close: hard** on the page) admits
nothing — no adjustments, no overrides — until it is reopened. A hard close of any applicable scope
wins over soft closes of other scopes. Turning a hard close soft is a reopen (reason required):
`reopen(scope, sameDate, actor, reason)`; `close` refuses it. A reopen always leaves a soft close.

## Where it is enforced

The check lives below controllers, in three places that together cover UI, REST, import, MCP, jobs
and application services:

- **`PostingEngine`** — `post`, `repost`, `unpost`, inside the posting transaction. Chronological
  restoration (`PostingOrder.CHRONOLOGICAL`) checks every later document it would repost, so a
  backdated change in an open context is rejected when its cascade would recalculate a document
  closed by another context or register. The whole restoration rolls back.
- **Saves** — `OnnoBeforeConvertCallback`, including the soft delete, on repository saves and on the
  generic command path (`DocumentCommandService` create/update/delete serving the UI, REST, CSV
  import and MCP saves through the same callbacks). A generic delete is checked before its
  `BeforeDeleteHandler` runs.

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

- **Audit** — every close (with its soft/hard state), reopen, adjustment and bypassed operation is a row in `onno_period_close_audit`
  (`periods.audit(limit)`, `GET /api/period-close/audit`): who, when, scope, previous and new date,
  reason, and for an override the operation and document.

## Administration UI and API

The admin screen is `@onno/ui`'s `<PeriodClose />` component: the closes in force, **Close
period** and **Reopen period** forms (manage roles only) and the recent audit trail. The web starter
serves only the API and reports `features.periodClose` in `/api/ui/model` when the service is wired.
An app registers the component as an ordinary page and links it from navigation:

```tsx
// pages/PeriodClosePage.tsx
import { Page, PeriodClose } from "@onno/ui";

export function PeriodClosePage() {
  return (
    <Page title="Period close">
      <PeriodClose />
    </Page>
  );
}

// main.tsx
{ path: "/period-close", component: PeriodClosePage, label: "Period close", icon: Lock,
  roles: ["ADMIN"], when: (model) => model.features.periodClose === true },
```

The forms offer `global`, every bounded `context:<name>` and every accumulation `register:<name>`
by default. There is no organization picker: pass organization scopes (`organization:<id>`) through
the `scopes` prop, which replaces the default list. `title` and `auditRows` (the audit rows shown)
are optional.

```text
GET  /api/period-close                 closes in force (+ label, soft/hard state) + canManage (any signed-in user)
GET  /api/period-close/audit?limit=100 audit trail                                 (manage roles)
POST /api/period-close/close           {scope, closedThrough, hard?, reason?}      (manage roles, CSRF)
POST /api/period-close/reopen          {scope, closedThrough?, reason}             (manage roles, CSRF)
```

`scope` is `global`, `context:<name>`, `register:<name>` or `organization:<id>`. With the MCP starter,
the read-only `period_close_status` tool returns the same closes to agents.

## Storage and limits

`PeriodCloseService` owns two tables it creates on startup (`onno_period_close`,
`onno_period_close_audit`); they are outside the schema reconciler. Close dates are read from the
database on every guarded operation, so a close on one node applies on all. Close and reopen lock
their scope's row (`SELECT … FOR UPDATE`), so concurrent changes to one scope serialize without lost
updates. Set `onno.period-close.enabled=false` to drop the service and every check.

Not covered yet: direct `RegisterRepository` writes outside posting, information registers,
explicit date ranges (a close is a cutoff date), MCP tools for closing and reopening, and a
guard that blocks an in-flight posting whose check passed just before a concurrent close
committed (the guard does not lock the close row).
