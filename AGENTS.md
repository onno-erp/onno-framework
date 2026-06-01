# Agent Guide: Modeling Businesses With OneC Framework

This project is a Java/Spring framework for modeling business processes as explicit business concepts:
catalogs, documents, tabular sections, registers, constants, enumerations, background jobs, UI metadata,
and future service boundaries.

The goal is not to clone 1C. The goal is to give a modern Java team, or an AI agent working with one,
a compact way to turn business documentation into a working, inspectable, evolvable application.

## First Principle

Model the business before writing code.

Ask what the company does, what it tracks, what documents move work forward, what balances matter,
what rules must never be violated, and which parts may later become separate services.

Avoid starting from database tables or controllers. In this framework, tables and generic APIs are generated
from the business model.

## Core Concepts

Use these framework concepts when translating a business into code.

### Catalogs

Catalogs are relatively stable reference data.

Examples:
- Products
- Customers
- Warehouses
- Employees
- Vehicles
- Price types
- Cost centers

Use:

```java
@Catalog(name = "Products", codeLength = 9, codePrefix = "P-")
public class Product extends CatalogObject {
    @Attribute(required = true)
    private String name;
}
```

Use hierarchical catalogs when users organize entities into folders or parent-child trees:

```java
@Catalog(name = "Product Groups", hierarchical = true)
public class ProductGroup extends CatalogObject {
}
```

### Documents

Documents are business events or transactions. They usually have a date, number, lifecycle, and line items.

Examples:
- Sales Order
- Invoice
- Goods Receipt
- Payment
- Shipment
- Work Order
- Timesheet

Use:

```java
@Document(name = "Sales Orders", numberPrefix = "SO-")
public class SalesOrder extends DocumentObject implements BeforeWriteHandler, Postable {
    @Attribute(required = true)
    private Ref<Customer> customer;

    @TabularSection(name = "items")
    private List<SalesOrderLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        // validate and compute derived fields
    }

    @Override
    public void handlePosting(PostingContext context) {
        // write register movements
    }
}
```

### Tabular Sections

Tabular sections are line-item collections owned by a document.

Examples:
- Invoice lines
- Shipment items
- Timesheet rows
- BOM components

Use:

```java
public class SalesOrderLine extends TabularSectionRow {
    @Attribute(required = true)
    private Ref<Product> product;

    @Attribute(precision = 15, scale = 3)
    private BigDecimal quantity;
}
```

### Accumulation Registers

Accumulation registers are ledgers for quantities, money, or other accumulated balances and turnovers.

Use `BALANCE` when the current balance matters.

Examples:
- Stock on hand
- Cash balance
- Loyalty points
- Open reservations

Use `TURNOVER` when period totals matter but current balance does not.

Examples:
- Sales revenue
- Produced quantity
- Logged hours

```java
@AccumulationRegister(name = "Stock", type = AccumulationType.BALANCE, context = "Inventory")
public class StockRegister extends AccumulationRecord {
    @Dimension
    private Ref<Warehouse> warehouse;

    @Dimension
    private Ref<Product> product;

    @Resource(precision = 15, scale = 3)
    private BigDecimal quantity;
}
```

### Information Registers

Information registers store state or facts by dimensions, optionally over time.

Examples:
- Product prices by date
- Exchange rates
- Employee rates
- Supplier lead times
- Configuration settings per warehouse

```java
@InformationRegister(name = "Prices", periodicity = Periodicity.DAY)
public class PriceRegister extends InformationRecord {
    @Dimension
    private Ref<Product> product;

    @Resource(precision = 15, scale = 2)
    private BigDecimal price;
}
```

### Enumerations

Use enumerations for closed sets of business states or categories.

Examples:
- OrderStatus
- PaymentMethod
- ShipmentType
- Priority

```java
@Enumeration(name = "Order Statuses")
public enum OrderStatus {
    NEW, APPROVED, SHIPPED, CANCELLED
}
```

### Constants

Use constants for singleton business settings.

Examples:
- Company name
- Default currency
- Default warehouse
- Tax registration number

```java
@Constant(name = "CompanyName")
public class CompanyName {
    private String value;
}
```

### Background Jobs

Use background jobs for scheduled or asynchronous work.

Examples:
- Nightly reports
- Price recalculation
- External system sync
- Aging reports

```java
@ScheduledJob(name = "DailySalesReport", cron = "0 0 2 * * *")
public class DailySalesReportJob implements BackgroundTask {
    @Override
    public void execute() {
    }
}
```

### Contexts And Future Services

Use `context` on annotations to mark bounded contexts. At first, contexts live in one monolith.
Later, they become good split points for services.

Examples:
- Sales
- Inventory
- Procurement
- Finance
- Manufacturing
- HR

```java
@Document(name = "Sales Orders", context = "Sales")
public class SalesOrder extends DocumentObject {
}

@AccumulationRegister(name = "Stock", context = "Inventory")
public class StockRegister extends AccumulationRecord {
}
```

Do not split into services too early. First create explicit boundaries in the model.

## Conversation Flow With The User

When a user asks to model a business, interview them before coding unless they already provided detailed docs.

Start with a short explanation:

> I’ll model this as catalogs, documents, registers, rules, and possible future service boundaries. First I need to understand how the business works.

Then ask focused questions.

### Round 1: What The Business Does

Ask:
- What does the business sell, produce, deliver, or manage?
- Who are the main actors: customers, suppliers, employees, departments, partners?
- What are the main objects the business tracks?
- What is the most important workflow from start to finish?

Listen for nouns and verbs:
- Nouns often become catalogs, documents, registers, or enum values.
- Verbs often become documents, lifecycle transitions, or posting rules.

### Round 2: Master Data

Ask:
- What reference lists do users maintain?
- Which lists need codes or numbers?
- Which lists are hierarchical?
- Which fields are required?
- Which fields reference other lists?

Likely outputs:
- `@Catalog`
- `@Attribute`
- `Ref<T>`
- `hierarchical = true`
- `codePrefix`

### Round 3: Business Transactions

Ask:
- What documents do users create?
- What line items do those documents contain?
- What statuses can each document have?
- What happens when a document is approved, posted, shipped, paid, cancelled, or closed?
- Which fields are calculated automatically?

Likely outputs:
- `@Document`
- `@TabularSection`
- `BeforeWriteHandler`
- `Postable`
- `@Enumeration`

### Round 4: Balances, Ledgers, And History

Ask:
- What quantities or money balances must be tracked?
- What should never go negative?
- What history must be preserved?
- What reports ask for balance as of a date?
- What reports ask for turnover between dates?

Likely outputs:
- `@AccumulationRegister(type = BALANCE)`
- `@AccumulationRegister(type = TURNOVER)`
- `@Dimension`
- `@Resource`
- posting movements in `handlePosting`

### Round 5: Rules And Invariants

Ask:
- What must be true before saving?
- What must be true before posting?
- What should be prevented?
- What values are derived?
- What are common exceptions?

Likely outputs:
- `BeforeWriteHandler`
- `BeforePostHandler`
- validation in Java services
- eventually declarative rule metadata

### Round 6: Integrations, Scale, And Boundaries

Ask:
- Which parts of the business have different teams or ownership?
- Which workflows are high volume?
- Which processes could run asynchronously?
- Which external systems are involved?
- If this grows, what should become a separate service first?

Likely outputs:
- annotation `context`
- background jobs
- outbox events
- `onec-kafka-starter` configuration
- remote reference resolution plans

## Modeling Workflow

Follow this order.

1. Write a short business summary.
2. Extract candidate catalogs, documents, registers, enums, constants, jobs, and contexts.
3. Confirm ambiguous concepts with the user.
4. Implement catalogs and enums first.
5. Implement documents and tabular sections.
6. Implement registers and posting logic.
7. Implement constants, jobs, and integrations.
8. Run tests.
9. Inspect `/api/ui/metadata/manifest` to verify the model is coherent.
10. Summarize what was modeled and what assumptions were made.

## Classification Heuristics

Use these heuristics when turning docs into framework objects.

If it is a stable list users choose from, use a catalog.

If it is a fixed list controlled by code, use an enumeration.

If it records a business event with a date and number, use a document.

If it is a repeated line inside a document, use a tabular section.

If it stores current balances, use a balance accumulation register.

If it stores period activity totals, use a turnover accumulation register.

If it stores historical facts by dimensions, use an information register.

If it is one global setting, use a constant.

If it happens later or repeatedly, use a background job.

If another team or service owns it, mark a context.

## Implementation Rules

Prefer framework concepts over hand-written CRUD.

Use normal Java services for complex domain logic. The framework should not hide ordinary Java.

Keep generated-framework code and business-specific code cleanly separated.

Use `Ref<T>` for lightweight references instead of embedding whole objects.

Put calculations in lifecycle hooks only when they belong to the entity lifecycle.

Put reusable policies in Spring services.

Keep posting logic explicit and testable.

When a document affects another context, prefer emitting an event or writing to an owned register through a clear boundary.

Do not create premature microservices. Mark contexts first, then split when load, team ownership, or deployment needs justify it.

Keep UI concerns out of domain classes. Sidebar placement, dashboard widgets, and per-field display hints belong in an `OneCUiConfigurer`, not on the entity itself. Do not add `@UiSection`, `@UiHint`, or `@DashboardWidget` to new code — they are deprecated.

## Code Patterns

### Catalog

```java
@Catalog(name = "Customers", codePrefix = "C-", context = "Sales")
@Getter
@Setter
public class Customer extends CatalogObject {
    @Attribute(required = true, length = 200)
    private String name;

    @Attribute(length = 50)
    private String taxId;
}
```

### Document With Lines

```java
@Document(name = "Sales Orders", numberPrefix = "SO-", context = "Sales")
@Getter
@Setter
public class SalesOrder extends DocumentObject implements BeforeWriteHandler, Postable {
    @Attribute(required = true)
    private Ref<Customer> customer;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal total;

    @TabularSection(name = "items")
    private List<SalesOrderLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        total = items.stream()
                .map(SalesOrderLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public void handlePosting(PostingContext context) {
        var sales = context.movements(SalesRegister.class);
        for (SalesOrderLine line : items) {
            sales.addReceipt(r -> {
                r.setProduct(line.getProduct());
                r.setQuantity(line.getQuantity());
                r.setAmount(line.amount());
            });
        }
    }
}
```

### Register

```java
@AccumulationRegister(name = "Sales", type = AccumulationType.TURNOVER, context = "Sales")
@Getter
@Setter
public class SalesRegister extends AccumulationRecord {
    @Dimension
    private Ref<Product> product;

    @Resource(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Resource(precision = 15, scale = 2)
    private BigDecimal amount;
}
```

Posting is always typed Java: implement `Postable.handlePosting(PostingContext)` (as above). There is no string-mapped declarative posting rule — a posting rule is code, type-checked and refactorable.

### Business Rules

Validation is typed Java, not string expressions. Implement `Validated` and return named `BusinessRule`s whose condition is a `BooleanSupplier` over the entity's fields. Rules run before write and before posting; the first failure throws with the rule's message (or `Business rule failed: {name}` if none).

```java
public class Invoice extends DocumentObject implements Validated {
    @Override
    public List<BusinessRule> rules() {
        return List.of(
            new BusinessRule("items-required", "Add at least one line",
                    () -> items != null && !items.isEmpty()),
            new BusinessRule("total-positive", "Total must be positive",
                    () -> total != null && total.signum() > 0));
    }
}
```

The compiler checks the field references, the IDE refactors through them, and there is no expression grammar to learn or parser to maintain.

### UI Authoring (Layout / Page / EntityView)

UI is authored as Java classes registered as Spring beans — never as annotations on domain classes. Three kinds:

- **`Layout`** — navigation structure + shell (nav presentation) + persona. The default layout (`profile() == null`) is the back-office shell; one per persona declares its roles and curated sections. `configure(LayoutSpec)`: `spec.shell().nav(NavStyle.SIDEBAR)`, `spec.section("Sales").icon("euro").catalog(Customer.class).document(Invoice.class)`.
- **`Page`** — a route whose content you compose (e.g. a dashboard): `compose(PageBuilder)` with `b.title(...)`, `b.widget(...)`, `b.text(...)`, `b.custom(...)`.
- **`EntityView`** — per-entity list columns (`list(ListSpec)`) and field hints (`fields(EntityConfigBuilder)`). An entity is only visible if it has an `EntityView` (the view layer is the allowlist).

Field-hint methods on `FieldHintBuilder` (used inside `EntityView.fields`): `order(int)`, `group(String)`, `width(String)`, `widget(String)`, `hideInList()`, `hideInForm()`, `hideInDetail()`, plus explicit `visibleInList(bool)`/`visibleInForm(bool)`/`visibleInDetail(bool)`. Only set what differs from the default.

## Questions To Ask For Common Domains

### Retail Or Wholesale

Ask:
- What products are sold?
- Are there warehouses or stores?
- Do you track stock by batch, serial number, expiration date, or location?
- What documents exist: purchase order, goods receipt, sales order, shipment, return?
- Can stock go negative?
- How are prices chosen?

Likely model:
- Catalogs: Product, Customer, Supplier, Warehouse
- Documents: GoodsReceipt, Sale, Return
- Registers: Stock, Sales
- Information registers: Prices

### Manufacturing

Ask:
- What is produced?
- What raw materials are consumed?
- Is there a bill of materials?
- What documents start and finish production?
- Do you track work centers, batches, scrap, labor?

Likely model:
- Catalogs: Item, WorkCenter, BOM
- Documents: ProductionOrder, MaterialIssue, ProductionReceipt
- Registers: Inventory, WIP, ProductionOutput

### Services Business

Ask:
- What services are sold?
- How is work requested, assigned, delivered, and approved?
- Is time tracked?
- Are invoices generated from approved work?

Likely model:
- Catalogs: Customer, Service, Employee, Project
- Documents: WorkOrder, Timesheet, Invoice
- Registers: BillableHours, Revenue

### Finance Or Accounting Adjacent

Ask:
- What money movements are tracked?
- Which accounts or wallets exist?
- What approvals are needed?
- Which balances must reconcile?

Likely model:
- Catalogs: Counterparty, Account, CostCenter
- Documents: Payment, Receipt, JournalEntry
- Registers: CashBalance, Payables, Receivables

## Output Format For The User

After interviewing, summarize like this:

```text
I understand the business as:
- ...

I propose this model:
- Catalogs: ...
- Documents: ...
- Registers: ...
- Enumerations: ...
- Constants: ...
- Background jobs: ...
- Contexts: ...

Key assumptions:
- ...

Next I will implement the first vertical slice:
- ...
```

Then implement the vertical slice. Do not ask for every detail up front. Get enough to model a useful first pass, then iterate.

## Verification

After implementation, run:

```bash
./gradlew test
```

For a running app, inspect:

```text
GET /api/ui/metadata/manifest
```

For documents that post movements, inspect:

```text
GET /api/ui/documents/{name}/{id}/posting-preview
```

The manifest should make sense to a human and an agent. If it does not, improve names, contexts, required fields, refs, and register semantics before adding more code.
