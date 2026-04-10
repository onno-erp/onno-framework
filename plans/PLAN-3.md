# Plan #3: Accumulation Registers + Posting Engine

## Context

Documents exist (Plan #2), now they need to *do* something. In 1C, posting a document writes movement records to accumulation registers (think: stock ledger, sales totals). This is the core business logic mechanism.

## Scope

- `@AccumulationRegister` annotation — `name`, `AccumulationType` (BALANCE / TURNOVER)
- `@Dimension`, `@Resource`, `@RegisterAttribute` field-level annotations
- `@RegisterMovement` (repeatable) on documents — declares which registers a document posts to
- `RegisterRecord`, `AccumulationRecord` base classes
- `RegisterMovementCollection<T>`, `PostingContext`
- `Postable` interface — `handlePosting(PostingContext context)`
- `BeforePostingHandler`, `AfterPostingHandler` lifecycle interfaces
- `PostingEngine` — orchestrates: beforePosting → handlePosting → write records → update totals → afterPosting
- `AccumulationRegisterManager<T>` — `getBalance()`, `getTurnover()`, `getRecords()`
- Schema generation for movement tables + totals tables
- Unposting: deactivate records, reverse totals

## Key Design Questions (to resolve when starting)

- Totals maintenance: UPSERT on post, or batch recalculation?
- Concurrent posting: row-level locking on totals?
- Should unposting call `handlePosting` in reverse or just deactivate records?

## Example

```java
@Document(name = "Invoice")
@RegisterMovement(register = SalesRegister.class)
public class Invoice extends DocumentObject implements Postable {
    @Override
    public void handlePosting(PostingContext context) {
        var movements = context.getMovements(SalesRegister.class);
        for (var line : items) {
            var record = movements.add();
            record.setProduct(line.getProduct());
            record.setQuantity(line.getQuantity());
        }
    }
}
```

## Verification
1. Post a document, verify register records created with correct values
2. Check accumulation totals updated (balance/turnover)
3. Unpost, verify records deactivated and totals reversed
4. Full cycle: create catalog items → create document → post → query register
