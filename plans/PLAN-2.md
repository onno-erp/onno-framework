# Plan #2: Documents + Tabular Sections

**Status: Next**

## Context

With Catalog CRUD proven (Plan #1), this plan adds the second core 1C concept: Documents. Documents are transactional records (invoices, receipts, etc.) that own tabular sections (line items). This also introduces lifecycle hooks and transactions.

## Scope

- `@Document` annotation with `name`, `numberLength`
- `@TabularSection` annotation for child table fields
- `DocumentObject` base class — `number`, `date`, `posted`, `deletionMark`
- `TabularSectionRow` base class — `lineNumber`
- `DocumentDescriptor`, `TabularSectionDescriptor` in metadata layer
- Schema generation for document tables + child tables (FK to parent)
- `DocumentPersistence` — CRUD with tabular section handling (delete-all + re-insert on save)
- `DocumentManager` — `create()`, `save()`, `delete()`, `findByNumber()`, `findByDateRange()`
- `BeforeWriteHandler` / `AfterWriteHandler` lifecycle interfaces — called automatically by manager
- `@Transactional` via Spring for multi-table writes (document + tabular sections in one transaction)
- `Ref<T>` resolution — lazy load referenced object from InfoBase

## Key Design Decisions

- **Spring `@Transactional`** for document + tabular section saves — no custom Transaction wrapper
- Lifecycle hooks detected via `instanceof` check in manager
- Tabular sections use delete-all + re-insert strategy on save (simple, correct)

## Example

```java
@Document(name = "Invoice")
public class Invoice extends DocumentObject implements BeforeWriteHandler {
    @Attribute private Ref<Customer> customer;
    @TabularSection private List<InvoiceLine> items;

    @Override
    public void beforeWrite() { /* validation */ }
}
```

## Verification
1. Create document with tabular section rows, save, reload, verify rows persisted
2. Lifecycle hooks fire in correct order
3. Transaction rollback on failure leaves no partial data
