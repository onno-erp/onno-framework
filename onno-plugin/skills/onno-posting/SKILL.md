---
name: onno-posting
description: >-
  Implement onno-framework posting, validation, business rules, register movements, lifecycle
  hooks, and post/unpost side effects. Use when writing Postable.handlePosting, BeforeWriteHandler,
  BeforePostHandler, Validated BusinessRule rules, accumulation register balance/turnover logic,
  negative-balance checks, DocumentPostedEvent listeners, soft-delete-aware business logic, or
  debugging save/post transactional behavior.
---

# onno Posting And Rules

Posting is typed Java. There is no string-mapped posting rule or validation expression grammar.

## Golden Rules

- Save the document and let it commit, then post. Do not wrap save + post in one Spring
  `@Transactional` method.
- Posting atomically claims an existing live, unposted document. A repeated core `post(...)` is
  rejected before movements are persisted; use atomic `repost(...)` for an intentional recalculation.
- A posting `@DomainEvent` outbox row is inserted on the posting transaction's JDBI handle. An
  outbox failure rolls back the posting; a posting rollback cannot leave a phantom event.
- `handlePosting(PostingContext)` should only stage register movements. Normal posting invokes it
  before transactional persistence; preview and chronological restoration may replay it.
- For external APIs, notifications, or other bean-backed side effects, listen for
  `DocumentPostedEvent` / `DocumentUnpostedEvent` with a Spring `@EventListener`.
- Lifecycle hooks run on domain objects created by reflection; they do not have Spring dependency
  injection.
- Business logic must ignore soft-deleted rows unless it is explicitly doing restore/admin/ref
  resolution work.

## Posting Shape

```java
@Document(name = "Sales Orders", numberPrefix = "SO-", context = "Sales")
public class SalesOrder extends DocumentObject implements BeforeWriteHandler, Validated, Postable {
    @TabularSection(name = "items")
    private List<SalesOrderLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        total = items.stream().map(SalesOrderLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(new BusinessRule("items-required", "Add at least one line",
                () -> items != null && !items.isEmpty()));
    }

    @Override
    public void handlePosting(PostingContext context) {
        var sales = context.movements(SalesRegister.class);
        for (var line : items) {
            sales.addReceipt(r -> {
                r.setProduct(line.getProduct());
                r.setQuantity(line.getQuantity());
                r.setAmount(line.amount());
            });
        }
    }
}
```

Use `addReceipt` and `addExpense` on the typed register repository returned by
`context.movements(RegisterClass.class)`.

`PostingService.preview` runs hooks/rules and stages movements without persistence. It does not
claim the document, run the final negative-balance guard, or simulate chronological restoration, so
preview success is not a guarantee that post succeeds. Direct/core repeated `post` rejects;
`PostingService.repost` reverses the old movements and writes the recalculated movements in one
transaction, preserving the old posting on failure. The generated REST/MCP post command selects
that atomic operation for an already-posted document. Unpost rejects drafts and deleted documents.

An accumulation-register `@Dimension` may be an `@Enumeration` enum. Set the enum constant normally
inside the movement callback; posting stores its deterministic UUID in both movement and totals
tables, and register filters/typed reads convert it in both directions.

## Negative Balance Policy

`@AccumulationRegister(type = BALANCE)` rejects a post if any resource total on a dimension tuple
touched by that post would become negative. A stale negative balance on an unrelated tuple does not
block otherwise valid work. This safe default fits inventory and other constrained balances.
Declare `allowNegative = true` on an individual balance register when its domain permits debt or
overdrafts:

```java
@AccumulationRegister(
        name = "Cash",
        type = AccumulationType.BALANCE,
        allowNegative = true)
class CashRegister extends AccumulationRecord {
}
```

The policy is per register and is ignored for `TURNOVER` registers.

## Chronological Restoration

Use `postingOrder = PostingOrder.CHRONOLOGICAL` when a movement depends on balances produced by all
earlier movements, such as moving-average inventory cost, or when historical/as-of balances must
remain nonnegative under backdated post/repost/unpost:

```java
@AccumulationRegister(
        name = "InventoryCost",
        type = AccumulationType.BALANCE,
        postingOrder = PostingOrder.CHRONOLOGICAL)
class InventoryCost extends AccumulationRecord {
}
```

A backdated post/repost/unpost then reverses later affected documents newest-first and reposts them
oldest-first in one serializable transaction. Balance queries performed from `handlePosting` see the
restoration transaction. The dependency closure crosses other chronological registers touched by
those documents. Keep posting deterministic and free of external side effects; restored documents
do not re-emit `DocumentPostedEvent`.

## Rules And Defaults

`Validated.rules()` runs before write and before posting. Use named `BusinessRule`s with clear user
messages. For field-specific errors use `BusinessRule.onField(field, message, condition)`.

Use Java field initializers for defaults that must appear on the initially rendered New form.
`OnFillingHandler.onFilling()` runs on create/save paths. Make it idempotent and guard on null so
imports/seeders are not clobbered.

## Soft Delete

Deletion marks rows instead of removing them. Raw repository methods can return tombstones. For
business logic, use active finders such as `findAllActive()`, `findActiveById(...)`,
`findActiveByCode(...)`, `findActiveByNumber(...)`, or filter `!isDeletionMark()`.
Repository delete methods run `BeforeDeleteHandler` and save the tombstone; they refuse to delete a
posted document until it is explicitly unposted.

## References

Read `../onno/reference/cheatsheet.md` before changing posting or lifecycle APIs. Use `onno-runtime`
to verify posting through the authenticated API or MCP tools.
