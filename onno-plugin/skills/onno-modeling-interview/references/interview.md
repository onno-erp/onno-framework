# Business Modeling Interview Guide

## Table Of Contents

- How To Run The Interview
- Round 1: Business Shape
- Round 2: Actors And Master Data
- Round 3: Documents And Workflows
- Round 3A: Durable Processes And Human Tasks
- Round 4: Lines, Quantities, Money
- Round 5: Balances, Ledgers, History
- Round 6: Rules And Invariants
- Round 7: UI, Roles, And Daily Work
- Round 8: Integrations, Async Work, Boundaries
- Extraction Heuristics
- Confirmation Template
- Example: Retail Inventory
- Example: Service Business
- When To Stop Asking

## How To Run The Interview

Ask in rounds. Start broad, then narrow. Do not ask every question below; choose the smallest set
that reveals the model.

Good first message:

```text
I’ll model this as catalogs, documents, registers, rules, and possible future service boundaries.
First I need to understand how the business works.

1. What does the business sell, produce, deliver, or manage?
2. What is the most important workflow from start to finish?
3. Who are the main actors: customers, suppliers, employees, departments, partners?
4. What must the system track accurately for the business to trust it?
```

When the user answers, immediately reflect the model:

```text
So far I hear:
- Catalog candidates: Customers, Products, Warehouses
- Document candidates: Sales Order, Shipment, Payment
- Register candidates: Stock on Hand, Sales Revenue
- Likely contexts: Sales, Inventory, Finance

The main ambiguity is whether shipment and invoice are separate business events or one document.
```

Then ask the next focused questions.

## Round 1: Business Shape

Ask:

- What does the business sell, produce, deliver, rent, book, repair, or manage?
- What is the main workflow from the first customer request to completion?
- What is the painful part today: lost stock, unpaid invoices, scheduling, compliance, reporting?
- What decisions do managers make from this system?
- What reports would make the business feel under control?

Listen for:

- things sold/managed -> catalogs
- workflow verbs -> documents and lifecycle actions
- pain around balances -> accumulation registers
- pain around history/effective dates -> information registers
- management reports -> register dimensions and dashboard widgets

## Round 2: Actors And Master Data

Ask:

- What reference lists do users maintain?
- Which lists need codes or numbers?
- Which lists are hierarchical, grouped, or folder-like?
- Which fields are required for each list?
- Which records reference other records?
- Which records are people with roles, assignments, or login identity?
- Which records can be deactivated but must remain visible in history?

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "We maintain customers/suppliers/products/warehouses" | `@Catalog` |
| "Products belong to categories" | `Ref<ProductCategory>` or hierarchical catalog |
| "Categories can contain subcategories" | `@Catalog(hierarchical = true)` |
| "Only admins change employees" | Confirm non-admin readers; `ADMIN` is already the superuser bypass |
| "The picker needs phone/email under the name" | `EntityView.field(...).refSecondary(...)` |

`@AccessControl` is deny-by-default. Ask which non-admin roles may read and write; omit `ADMIN` from
the arrays because it always bypasses. Empty `writeRoles` inherits `readRoles`, so enumerate
non-admin writers explicitly when they differ.

Follow-up questions:

- What is the human display name for each record?
- Are codes manually entered or auto-generated?
- Can users merge or delete records?
- Are there external IDs from another system?
- Do any fields need secrecy or encryption?

## Round 3: Documents And Workflows

Ask:

- What documents do users create during the workflow?
- Who creates each document, and when?
- What statuses can each document have?
- Which actions move it from one status to another?
- Which documents are posted, approved, shipped, paid, cancelled, or closed?
- Which fields are calculated automatically?
- Which documents are legally/audit important and must preserve history?

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "A user creates an order" | `@Document` |
| "The order has products/quantities/prices" | `@TabularSection` rows |
| "Draft -> Approved -> Shipped -> Completed" | `@Enumeration` status + actions |
| "Total is sum of lines" | `BeforeWriteHandler` |
| "Can only post approved orders" | `Validated` or `BeforePostHandler` |
| "Unposted cancellation should not affect stock" | no movements in `handlePosting` |

Follow-up questions:

- Is this one document with statuses, or multiple business events?
- Does "approved" change balances, or only "posted/shipped"?
- Can a posted document be edited, unposted, or reversed?
- Are numbers auto-generated, prefixed, or imported from another system?
- Which line fields are entered by users and which are computed?

An enum status is enough for a local entity lifecycle. If work spans assigned people or objects and
must remember active steps, audit, timers, or parallel/sequential outcomes, model a durable typed
process instead.

## Round 3A: Durable Processes And Human Tasks

Ask:

- Does approval or fulfillment pass between several people or departments?
- Must the system remember who can claim, owns, delegates, or completed each step?
- Are typed decisions, sequential/parallel approvals, timers, or child processes needed?
- Does cancellation need a durable audit and assignment policy?

Map local status/actions to the document. Map remembered coordination to `ProcessDefinition`,
`HumanTask`, `TaskAssignment`, typed outcomes, timers, fork/join, and subprocesses. A scheduled job is
for time-triggered work, not a substitute for human workflow.

## Round 4: Lines, Quantities, Money

Ask:

- Which documents have line items?
- What does each line reference?
- What quantities, prices, discounts, taxes, or amounts are on the line?
- Are units of measure needed?
- Can the same product appear more than once?
- Are line amounts rounded, taxed, discounted, or allocated?
- Are there serial numbers, lots, rooms, seats, vehicles, employees, or time intervals per line?

For rentals/bookings, explicitly ask whether capacity or an exact asset is reserved, interval
boundary/timezone rules, overlap, future availability, allocation timing, extensions, blocking
periods, and location changes. Documents plus ordinary Java query/domain logic may be safer than
assuming a current BALANCE register answers interval availability.

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "Invoice lines have product, quantity, price" | row extends `TabularSectionRow` |
| "Amount = quantity × price" | line calculation in `beforeWrite()` |
| "Tax is computed by region" | domain service or lifecycle calculation |
| "Each room guest must be tracked separately" | separate row fields or child document, depending lifecycle |

Warning signs:

- If a "line" needs its own lifecycle, assignments, comments, posting, or independent search, it may
  be a document or catalog instead of a tabular section.
- If a line should be reused across documents, it is not owned by one document.

## Round 5: Balances, Ledgers, History

Ask:

- What current balances must be correct right now?
- What should never go negative?
- What period totals are reported daily/monthly/yearly?
- What facts change over time and need "as of date" lookup?
- What dimensions do reports group by?
- Which document changes each balance or total?
- Which balances may legitimately be negative?
- Can users post or unpost backdated documents?
- Do later calculations depend on earlier balances?

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "Stock on hand by warehouse/product" | BALANCE accumulation register |
| "Cash balance by account" | BALANCE accumulation register |
| "Sales revenue by product/salesperson/month" | TURNOVER accumulation register |
| "Price by product/date" | information register |
| "Exchange rate by currency/date" | information register |

Follow-up questions:

- Is the report asking for current state or period activity?
- What are the dimensions: product, warehouse, customer, employee, department, project?
- What are the resources: quantity, amount, hours, points, weight?
- Which documents add receipts and which add expenses?

Map legitimate debt/overdraft to `allowNegative = true`. Use `PostingOrder.CHRONOLOGICAL` when
backdated changes must restore later order-dependent calculations.

## Round 6: Rules And Invariants

Ask:

- What must be true before saving?
- What must be true before posting?
- What should be prevented even if a user has permission?
- What exceptions are allowed and who can approve them?
- What values are defaulted?
- What values are derived and should not be edited?
- What data must remain after deletion?
- Which settings are truly global (legal company name, default currency/location, tax defaults),
  and which vary by company/location/date?

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "Every order needs a customer" | `BusinessRule.onField("customer", ...)` |
| "Cannot post with empty lines" | `Validated` |
| "Cannot ship more than stock" | BALANCE register negative guard |
| "New orders default to today and Draft" | `OnFillingHandler` + field initializer |
| "Only finance can delete posted invoices" | `@AccessControl` plus action/hook guard |

Follow-up questions:

- Is this a hard rule or a warning?
- Does it apply on save, on post, or on a specific action?
- Is the message user-facing and localized?
- Does the rule require looking up other records?

Map truly global singleton settings to `@Constant`; per-company/location/effective-dated settings
belong on catalogs or information registers.

## Round 7: UI, Roles, And Daily Work

Ask:

- Who uses the system every day?
- What should each role see in the sidebar?
- What is the first screen each role should land on?
- What list columns matter for scanning?
- What filters are used constantly?
- Which actions should be row buttons, detail buttons, or toolbar actions?
- Which forms need defaults, grouping, placeholders, hints, or custom widgets?
- What language should the UI use?

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "Managers see sales, warehouse sees receipts" | role/profile `Layout`s |
| "Orders board grouped by status" | `ListSpec.groupable`, filters, row styles |
| "Advance order from the row" | row `ActionSpec` |
| "Dashboard has revenue and open orders" | `Page` widgets |
| "Need a special timeline/chart" | custom widget |

Follow-up questions:

- Is this entity in nav, or reachable only from another screen?
- What are the default sort and filters?
- Which fields should be hidden from forms/lists/details?
- Which labels need localization?

## Round 8: Integrations, Async Work, Boundaries

Ask:

- Which external systems are involved?
- Which side is the source of truth for each object?
- Are integrations synchronous, webhooks, polling, files, or manual import?
- What must be idempotent?
- What failures should block the user, and what failures should be retried later?
- Which processes run in the background?
- Which business area could become a separate service first?

Map answers:

| Answer pattern | onno concept |
| --- | --- |
| "Call external API after posting" | `DocumentPostedEvent` listener |
| "Poll for status every night" | scheduled job |
| "Publish events to other services" | outbox/Kafka |
| "Connector wraps external API" | separate starter/extension |
| "Inventory owned by another team" | `context = "Inventory"` boundary |

Follow-up questions:

- What external IDs must be stored?
- Are retries safe?
- Is there an audit log for submissions?
- What data cannot leave the system?

## Extraction Heuristics

Use this pass after each answer.

1. Underline nouns.
   - Stable nouns -> catalogs.
   - Event nouns -> documents.
   - Quantities/money/state over time -> registers.
   - Closed categories -> enums.

2. Underline verbs.
   - Create/approve/ship/pay/cancel/post -> document actions/lifecycle.
   - Add/remove/consume/earn/spend -> register movements.
   - Recalculate/sync/import/export -> jobs/integrations.
   - Claim/delegate/approve across people -> typed process/human task.

3. Ask "who owns this?"
   - Same team/app -> same monolith context.
   - Different team/system -> context boundary or connector.

4. Ask "does history matter?"
   - Yes, event happened -> document.
   - Yes, value effective by date -> information register.
   - No, current editable fact -> catalog attribute or constant.

5. Ask "is balance as-of-date needed?"
   - Yes -> BALANCE register.
   - No, only period totals -> TURNOVER register.

## Confirmation Template

Before coding, send a compact confirmation:

```text
Here is the first model slice I’ll implement:

Catalogs:
- Customers: name, phone, email
- Products: SKU, name, category, price
- Warehouses: name

Enums:
- OrderStatus: Draft, Confirmed, Shipped, Completed, Cancelled

Documents:
- SalesOrder: customer, status, total, items(product, quantity, unitPrice, amount)
- GoodsReceipt: warehouse, supplier, items(product, quantity, cost)

Registers:
- Stock: BALANCE by warehouse/product, quantity
- Sales: TURNOVER by product/customer, quantity and amount

Rules/posting:
- SalesOrder computes line amounts and total before save.
- Posting confirmed orders reduces Stock and writes Sales.
- Stock cannot go negative.

UI:
- Sales and Inventory sections in sidebar.
- Orders list grouped/filterable by status.

Assumptions:
- Shipment is part of SalesOrder status, not a separate document yet.
- Payment is out of scope for this first slice.
```

If the assumptions are low-risk, proceed. If they change table shape/posting semantics, wait for
confirmation.

## Example: Retail Inventory

User says:

```text
We sell books online. We receive books from suppliers into a warehouse, then customer orders reserve
and ship them. Managers need stock by book and revenue by day. Orders can be new, confirmed, shipped,
completed, or cancelled.
```

Extract:

- `Book`, `Supplier`, `Warehouse`, `Customer` -> catalogs
- `OrderStatus` -> enum
- `GoodsReceipt` -> document with receipt lines
- `SalesOrder` -> document with order lines
- `Stock` -> BALANCE register by warehouse/book
- `Sales` -> TURNOVER register by book/day/customer
- `Sales`, `Inventory`, `Catalog` -> contexts
- posting: receipt adds stock; order removes stock and records sales
- rules: no empty order; no negative stock; a never-posted cancelled order writes no movements;
  cancelling an already-posted order requires explicit unpost, reversal, or compensation

Next questions:

```text
1. Does an order reserve stock before it ships, or only reduce stock when posted/shipped?
2. Can one order ship from multiple warehouses?
3. Are payments in scope for this slice, or should we stop at shipped/completed?
4. Which role can edit books, suppliers, and stock receipts?
```

## Example: Service Business

User says:

```text
We run a repair shop. Customers bring devices. A technician diagnoses, adds labor and parts, then
the customer pays. We need open jobs, technician hours, parts usage, and invoices.
```

Extract:

- `Customer`, `Device`, `Technician`, `Part` -> catalogs
- `RepairStatus`, `PaymentMethod` -> enums
- `RepairOrder` -> document with labor/parts lines or separate sections
- `Invoice` or payment fields -> document, depending workflow
- `PartsStock` -> BALANCE register by part/location
- `TechnicianHours` -> TURNOVER register by technician/job/date
- `Revenue` -> TURNOVER register
- `Service`, `Inventory`, `Finance` -> contexts

Next questions:

```text
1. Is the invoice a separate document, or is payment recorded on the repair order?
2. Are parts consumed during diagnosis, approval, or completion?
3. Can a repair have multiple technicians?
4. What statuses are terminal, and can a closed repair be reopened?
```

## When To Stop Asking

Stop discovery and implement when:

- the first workflow has a clear start/end
- catalogs and required fields are known
- documents and line items are known
- at least one register/posting rule is clear, or the slice is intentionally master-data-only
- roles and first UI nav are clear
- document-status versus durable-process decisions are clear
- remaining uncertainties are explicitly listed as assumptions

Keep asking if:

- a document vs catalog vs register decision is ambiguous
- posting semantics are unclear
- durable assignment/audit needs are unclear
- a balance might go negative but the exception rules are unknown
- the same term means different things to different roles
- an integration owns the source of truth
