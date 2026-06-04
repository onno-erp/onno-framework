# Agent Guide: Modeling Businesses With onec Framework

This project is a Java/Spring framework for modeling business processes as explicit business concepts:
catalogs, documents, tabular sections, registers, constants, enumerations, background jobs, UI metadata,
and future service boundaries.

The goal is not to clone 1C. The goal is to give a modern Java team, or an AI agent working with one,
a compact way to turn business documentation into a working, inspectable, evolvable application.

## Repository Operating Rules For AI Agents

This repository is intended to be edited by AI coding agents. Keep changes small, verifiable, and friendly
to future agents that may not have the full conversation context.

### Current Status

- The GitHub repository is private while licensing is undecided.
- Do not add a `LICENSE` file or license claims unless the user explicitly chooses one.
- Published coordinates currently use `group = "com.onec"` and `version = "0.1.0"`.
- Java 21 is required. The Gradle wrapper is the source of truth for builds.
- `onec-ui-starter` builds a bundled frontend with Node 20 via Gradle.

### Repo Map

| Path | Role |
| --- | --- |
| `onec-framework` | Core annotations, metadata, schema, posting, repository contracts, UI model, and shared types. |
| `onec-framework-starter` | Spring Boot auto-configuration for the core framework. |
| `onec-ui-starter` | Generic web UI controllers plus packaged frontend assets. |
| `onec-auth-starter` | Security and auth API auto-configuration. |
| `onec-print-starter` | Print/PDF rendering support. |
| `onec-mail-starter` | Mail templates, dispatchers, preview endpoints, and outbox relay. |
| `onec-kafka-starter` | Kafka/event transport helpers. |
| `onec-desktop-starter` | Desktop runtime support and bundled Tauri shell resources. |
| `onec-desktop-gradle-plugin` | Gradle plugin for native desktop packaging. |
| `onec-hospedajes-starter` | SES.HOSPEDAJES integration. |
| `onec-guesty-starter` | Guesty Open API integration. |
| `example` | Local example app and smoke-test consumer inside the multi-module build. Do not publish it. |

### Before Editing

1. Read the relevant module's `build.gradle.kts`.
2. Check whether the change belongs in core, a starter, the desktop plugin, the UI frontend, or the example app.
3. Prefer extending existing framework concepts over adding parallel mechanisms.
4. Keep public API changes intentional. If you change annotations, model base classes, repository contracts, or auto-configuration properties, update docs and tests in the same pass.
5. Preserve user changes in the working tree. Do not reset, checkout, or clean files unless the user explicitly asks.

### Build And Verification Commands

Use the narrowest useful command while iterating, then finish with the broader checks.

```bash
# Compile/check everything, including the example app and frontend packaging.
./gradlew clean check

# Verify artifacts can be consumed by external projects through Maven local.
./gradlew publishToMavenLocal

# Publish to GitHub Packages only when explicitly requested and credentials are available.
GITHUB_ACTOR=... GITHUB_TOKEN=... ./gradlew publish
```

For targeted iteration:

```bash
./gradlew :onec-framework:test
./gradlew :onec-framework-starter:compileJava
./gradlew :onec-ui-starter:buildFrontend
./gradlew :onec-ui-starter:compileJava
```

`publishToMavenLocal` is important: a build can pass with project dependencies but still fail while producing
sources, javadocs, POM metadata, or binary artifacts.

### Generated Files And Caches

Do not commit generated or local-only files:

- `.gradle/`
- `build/`
- `.kotlin/`
- `data/`
- `onec-ui-starter/src/main/frontend/dist/`
- `onec-ui-starter/src/main/frontend/node_modules/`
- `*.tsbuildinfo`

If a tool creates a cache that appears in `git status`, remove it unless it is intentionally part of the change.

### Publication And Consumption Expectations

The reusable surface is the set of published modules under `com.onec:*:0.1.0`. External projects should consume
artifacts from `mavenLocal()` during development or GitHub Packages after publishing. Avoid requiring consumers
to use `includeBuild` except for local desktop-plugin development.

Spring Boot starters must expose auto-configuration through:

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

When adding a starter, include:

- `java-library`
- `maven-publish`
- `withSourcesJar()`
- `withJavadocJar()`
- a Spring Boot auto-configuration imports file if the module contributes auto-configured beans
- README or root README updates explaining how a consumer enables it

### Known Non-Blocking Warnings

- Deprecated annotation warnings for `@UiHint`, `@UiSection`, and `@DashboardWidget` are expected in old code.
  Do not add these annotations to new code.
- Javadoc currently emits many missing-comment warnings. Broken links are not acceptable because they can fail
  publication.
- The frontend npm audit may report moderate vulnerabilities. Do not run forced upgrades without checking the
  impact on the UI build and generated assets.

### AI-Friendly Change Style

- Prefer a vertical slice with tests over broad refactors.
- Add tests near the module that owns the behavior.
- For framework behavior, test core logic in `onec-framework` and Spring wiring in the relevant starter.
- For public API changes, include a tiny example or README snippet so future agents see intended usage.
- Name business concepts clearly. The UI manifest and generated APIs should read like the business domain, not
  like database plumbing.
- Keep generated framework behavior inspectable. Avoid magic string conventions when typed Java APIs already exist.

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
9. Verify the model is coherent against the running app — see [Verification](#verification) for the
   real authenticated endpoints (`/api/catalogs/{name}`, `/api/documents/{name}`, etc.); there is no
   anonymous manifest endpoint.
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

Keep UI concerns out of domain classes. Sidebar placement belongs in `Layout` beans, dashboard widgets belong in `Page` beans, and per-field display hints belong in `EntityView` or `Layout` configuration. Do not add `@UiSection`, `@UiHint`, or `@DashboardWidget` to new code — they are deprecated.

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

**Reacting to a post (external integrations).** When something must happen *after* a document posts —
call an external API, send a notification, register travelers with an authority — don't try to do it
inside `handlePosting` (that runs in the posting transaction and should only write movements) and don't
reach for a service-locator. The framework publishes a Spring `DocumentPostedEvent` (and
`DocumentUnpostedEvent`) after the post commits; handle it with an ordinary `@EventListener` bean,
which has full dependency injection:

```java
@Component
class CheckInListener {
    private final HospedajesService hospedajes;
    CheckInListener(HospedajesService hospedajes) { this.hospedajes = hospedajes; }

    @EventListener
    void onPosted(DocumentPostedEvent event) {
        if (event.document() instanceof CheckIn checkIn) hospedajes.registrar(checkIn);
    }
}
```

The domain `AfterPostHandler.afterPost()` hook still exists, but it has no Spring access — prefer the
event for anything that touches beans. Neither requires the Kafka outbox (that's only for cross-service
event streaming).

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

After implementation, choose the verification level that matches the change.

For narrow Java changes, run the owning module's tests:

```bash
./gradlew :onec-framework:test
./gradlew :onec-mail-starter:test
```

For changes that affect public artifacts, starter wiring, frontend packaging, or the example app, run:

```bash
./gradlew clean check
./gradlew publishToMavenLocal
```

For UI frontend-only changes, at minimum run:

```bash
./gradlew :onec-ui-starter:buildFrontend
./gradlew :onec-ui-starter:processResources
```

### Inspecting a running app (read this before you curl)

Everything under `/api/**` is **authenticated** and most of it is **CSRF-protected**. Two things trip
up every agent:

1. **There is no anonymous metadata/manifest endpoint.** Older notes pointed at
   `/api/ui/metadata/manifest`; no controller serves that path, so it falls through to the SPA and you
   get `index.html` (HTTP 200, HTML body) — which looks like success but isn't. To introspect the
   model at runtime, hit the real generated endpoints below.
2. **Unknown `/api/**`-adjacent paths return the SPA, not a 404.** If a call returns HTML, you have the
   wrong path or aren't authenticated — not a working endpoint.

**Authenticate first (session cookie + CSRF), then call the API.** Login is a JSON POST that sets a
`JSESSIONID` session cookie — *not* HTTP Basic. Mutations (`POST`/`PUT`/`DELETE`) require the CSRF
token: it's delivered in a readable `XSRF-TOKEN` cookie and echoed back in the `X-XSRF-TOKEN` header.
Users come from `onec.auth.users[*]` (there are **no** default credentials).

```bash
# 1. Log in — saves the session cookie and the XSRF-TOKEN cookie into the jar. Login itself is CSRF-exempt.
curl -sc cookies.txt -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"…"}' http://localhost:8080/api/auth/login

# 2. Reads just need the session cookie:
curl -sb cookies.txt http://localhost:8080/api/catalogs/Properties
curl -sb cookies.txt http://localhost:8080/api/documents/Reservations

# 3. Mutations also need the CSRF header (value taken from the XSRF-TOKEN cookie):
XSRF=$(awk '/XSRF-TOKEN/{print $7}' cookies.txt)
curl -sb cookies.txt -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
     -d '{ …entity JSON… }' http://localhost:8080/api/catalogs/Properties
```

`{name}` is the entity's **display name** (e.g. `Properties`, `Reservations`), *not* the Java class
name (`Property` → 404/SPA fallback). The real endpoints (all under `/api`, served by `onec-ui-starter`):

```text
GET    /api/catalogs/{name}                       list
GET    /api/catalogs/{name}/{id}                   one
POST   /api/catalogs/{name}                        create        (CSRF)
PUT    /api/catalogs/{name}/{id}                   update        (CSRF)
DELETE /api/catalogs/{name}/{id}                                 (CSRF)
GET    /api/documents/{name}                       list
GET    /api/documents/{name}/{id}                  one
POST   /api/documents/{name}                       create        (CSRF)
POST   /api/documents/{name}/{id}/post             post          (CSRF)
POST   /api/documents/{name}/{id}/unpost           unpost        (CSRF)
GET    /api/documents/{name}/{id}/posting-preview  dry-run the movements a post would write
GET    /api/registers/{name}/movements
GET    /api/registers/{name}/balance
GET    /api/registers/{name}/turnover?from=…&to=…
GET    /api/auth/me                                current principal (handy auth smoke-test)
```

The generated API and screens should read like the business domain. If they don't, improve names,
contexts, required fields, refs, and register semantics before adding more code.

For the exact JSON the read endpoints return — column-name keys, `*_display`/`*_ref` expansion,
secret redaction, list vs get, inlined tabular sections — see
[docs/HEADLESS_READ_API.md](docs/HEADLESS_READ_API.md).

### Writing sync / import code (upsert + posting)

Two framework behaviors you *will* hit the moment you write import or sync code:

- **Upserts are handled on load.** `CatalogObject`/`DocumentObject`/`AccumulationRecord` default
  `isNew = true`. The framework resets it to `false` after every load (an `AfterConvertCallback`), so
  the natural "load, mutate, `repository.save(...)`" does an UPDATE. If you build an entity *by hand*
  (new instance, then set its known id — e.g. keyed on an external system's id) and want an UPDATE,
  call `setNew(false)` yourself; otherwise the save attempts an INSERT and you get a duplicate-key
  error.
- **Posting is its own transaction.** `post(...)` runs on a separate JDBI transaction, *not* enlisted
  in any ambient Spring `@Transactional`. Do **not** wrap save+post in one `@Transactional` method —
  the document row won't be committed yet, the `_posted` update runs on a different connection that
  can't see it, and you silently end up with register movements but `_posted = false`. **Save (let it
  commit), then post.**
