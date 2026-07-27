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
- `handlePosting(PostingContext)` should only write register movements.
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

## Negative Balance Policy

`@AccumulationRegister(type = BALANCE)` rejects a post if any resource total would become negative.
This safe default fits inventory and other constrained balances. Declare `allowNegative = true` on
an individual balance register when its domain permits debt or overdrafts:

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
earlier movements, such as moving-average inventory cost:

```java
@AccumulationRegister(
        name = "InventoryCost",
        type = AccumulationType.BALANCE,
        postingOrder = PostingOrder.CHRONOLOGICAL)
class InventoryCost extends AccumulationRecord {
}
```

A backdated post/unpost then reverses later affected documents newest-first and reposts them
oldest-first in one serializable transaction. Balance queries performed from `handlePosting` see the
restoration transaction. The dependency closure crosses other chronological registers touched by
those documents. Keep posting deterministic and free of external side effects; restored documents
do not re-emit `DocumentPostedEvent`.

## Rules And Defaults

`Validated.rules()` runs before write and before posting. Use named `BusinessRule`s with clear user
messages. For field-specific errors use `BusinessRule.onField(field, message, condition)`.

`OnFillingHandler.onFilling()` seeds new instances for the generic New form and repository persist
path. Make it idempotent and guard on null so imports/seeders are not clobbered.

## Soft Delete

Deletion marks rows instead of removing them. Raw repository methods can return tombstones. For
business logic, use active finders such as `findAllActive()`, `findActiveById(...)`,
`findActiveByCode(...)`, `findActiveByNumber(...)`, or filter `!isDeletionMark()`.

## References

Read `../onno/reference/cheatsheet.md` before changing posting or lifecycle APIs. Use `onno-runtime`
to verify posting through the authenticated API or MCP tools.
