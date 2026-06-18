# Building ERPs With onno-framework And AI Agents

This guide is for teams and AI coding agents using the published `onno-framework` libraries to build an ERP application in a separate project.

Use this file as the agent handoff document in the consuming ERP repo. It explains how to install the libraries, how to model business domains, and how to verify that the generated ERP surface is coherent.

> **Tip — install the `onno` skill.** For the best results with Claude Code, install the skill that
> packages this guidance as an always-available expert playbook (this framework's repo doubles as a
> plugin marketplace):
>
> ```text
> /plugin marketplace add onno-erp/onno-framework
> /plugin install onno@onno-framework
> ```
>
> It auto-loads when you model entities, write posting/validation, or call the runtime API.

## What The Framework Does

`onno-framework` lets an ERP project describe business concepts directly in Java:

- catalogs for master data
- documents for business transactions
- tabular sections for document line items
- registers for balances, turnover, and historical facts
- enumerations for closed state sets
- constants for singleton settings
- lifecycle hooks and rules for invariants
- Spring Boot starters for schema, repositories, generic APIs, UI, auth, mail, print, desktop, Kafka, and integrations

Do not start from database tables or controllers. Start from the business model. The framework turns that model into persistence, repositories, generic APIs, metadata, and UI surfaces.

## Add The Libraries

Released modules are on **Maven Central** under the `su.onno` group. Consumers need no
credentials and no custom repository — just `mavenCentral()`. (The Java packages are still `su.onno.*`,
so your imports don't change; only the Maven coordinate uses `su.onno`.) Pin the version in
one place and pick the latest release from
[Maven Central](https://central.sonatype.com/namespace/su.onno):

```kotlin
val onnoVersion = "<latest>"   // https://central.sonatype.com/namespace/su.onno

repositories {
    mavenCentral()
}

dependencies {
    implementation("su.onno:onno-framework-starter:$onnoVersion")
    implementation("su.onno:onno-ui-starter:$onnoVersion")

    runtimeOnly("com.h2database:h2")
}
```

To consume an unreleased build, run `./gradlew publishToMavenLocal` in the framework repo and add
`mavenLocal()` (it publishes `-SNAPSHOT`).

Optional starters (same group and `$onnoVersion`):

```kotlin
implementation("su.onno:onno-auth-starter:$onnoVersion")    // security: in-memory, OIDC/SSO, JWT
implementation("su.onno:onno-mcp-starter:$onnoVersion")     // MCP server for AI agents
implementation("su.onno:onno-import-starter:$onnoVersion")  // CSV import
implementation("su.onno:onno-print-starter:$onnoVersion")   // PDF / print
implementation("su.onno:onno-mail-starter:$onnoVersion")    // transactional email
implementation("su.onno:onno-kafka-starter:$onnoVersion")   // outbox → Kafka
implementation("su.onno:onno-desktop-starter:$onnoVersion") // native desktop
```

Commercial vertical connectors ship from the separately licensed
[onno-enterprise](https://github.com/onno-erp/onno-enterprise) repository under the
`su.onno.enterprise` group (Guesty, SES.HOSPEDAJES, Tochka), versioned independently of the core:

```kotlin
implementation("su.onno.enterprise:onno-guesty-starter:<enterprise-version>")
implementation("su.onno.enterprise:onno-hospedajes-starter:<enterprise-version>")
```

## Minimal Application Config

Use Java 21 and Spring Boot 3.4.x.

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/app
    driver-class-name: org.h2.Driver
    username: sa
    password:

onno:
  scan-packages:
    - com.acme.erp
  auth:
    users:
      - { username: admin, password: admin, roles: [ADMIN] }
```

`onno.scan-packages` (note: **not** `onno.base-packages`) points at the package where catalogs,
documents, registers, constants, jobs, layouts, pages, and entity views live. It is optional — if you
omit it, the framework scans from your `@SpringBootApplication` package. The `onno.base-packages` name
only exists on the mail and print starters (`onno.mail.base-packages` / `onno.print.base-packages`)
for template scanning.

## Recommended Project Layout

```text
src/main/java/com/acme/erp/
  AcmeErpApplication.java
  sales/
    catalogs/
    documents/
    registers/
    ui/
  inventory/
    catalogs/
    documents/
    registers/
    ui/
  finance/
    catalogs/
    documents/
    registers/
    ui/
  shared/
    enums/
    constants/
```

Use packages to reflect bounded contexts. Good ERP code should read like the business.

## Agent Workflow

When an AI agent is asked to build an ERP, it should work in this order:

1. Interview the user about the business.
2. Extract candidate catalogs, documents, registers, enumerations, constants, jobs, and contexts.
3. Confirm only the ambiguous parts.
4. Implement one vertical slice end to end.
5. Add UI layout and entity views for the slice.
6. Add tests for metadata scanning, rules, posting, and any custom services.
7. Run the app and inspect the generated API and UI (via `/api/catalogs|documents|registers/{name}` or the MCP `describe_metadata` tool).
8. Summarize assumptions and the next slice.

Do not ask for every detail before coding. Get enough to build a useful first pass, then iterate.

## Questions The Agent Should Ask

Start with:

```text
I will model this ERP as catalogs, documents, registers, rules, and UI views.
First I need to understand the business workflow.
```

Ask:

- What does the business sell, produce, deliver, rent, or manage?
- Who are the main actors: customers, suppliers, employees, departments, partners?
- What master data lists do users maintain?
- What documents move work forward?
- What line items belong inside each document?
- What statuses can each document have?
- What quantities, money balances, or historical facts must be tracked?
- What must never happen?
- What external systems are involved?
- Which business areas could later become separate services?

Listen for nouns and verbs:

- nouns become catalogs, documents, registers, constants, or enum values
- verbs become documents, lifecycle transitions, posting logic, jobs, or integrations

## Modeling Rules

Use a catalog when users maintain a stable list:

```java
@Catalog(name = "Customers", codePrefix = "C-", context = "Sales")
@Getter
@Setter
public class Customer extends CatalogObject {
    @Attribute(required = true, length = 200)
    private String name;
}
```

Use a document for a business event or transaction:

```java
@Document(name = "Sales Orders", numberPrefix = "SO-", context = "Sales")
@Getter
@Setter
public class SalesOrder extends DocumentObject implements BeforeWriteHandler, Postable {
    @Attribute(required = true)
    private Ref<Customer> customer;

    @TabularSection(name = "items")
    private List<SalesOrderLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        // validate and calculate derived fields
    }

    @Override
    public void handlePosting(PostingContext context) {
        // write register movements explicitly
    }
}
```

Use tabular sections for line items:

```java
@Getter
@Setter
public class SalesOrderLine extends TabularSectionRow {
    @Attribute(required = true)
    private Ref<Product> product;

    @Attribute(precision = 15, scale = 3)
    private BigDecimal quantity;
}
```

Use accumulation registers for balances or turnover:

```java
@AccumulationRegister(name = "Stock", type = AccumulationType.BALANCE, context = "Inventory")
@Getter
@Setter
public class StockRegister extends AccumulationRecord {
    @Dimension
    private Ref<Warehouse> warehouse;

    @Dimension
    private Ref<Product> product;

    @Resource(precision = 15, scale = 3)
    private BigDecimal quantity;
}
```

Use information registers for historical facts by dimensions:

```java
@InformationRegister(name = "Prices", periodicity = Periodicity.DAY, context = "Sales")
@Getter
@Setter
public class PriceRegister extends InformationRecord {
    @Dimension
    private Ref<Product> product;

    @Resource(precision = 15, scale = 2)
    private BigDecimal price;
}
```

Use enumerations for closed sets:

```java
@Enumeration(name = "Order Statuses")
public enum OrderStatus {
    NEW,
    APPROVED,
    SHIPPED,
    CANCELLED
}
```

Use constants for singleton settings:

```java
@Constant(name = "CompanyName")
public class CompanyName {
    private String value;
}
```

## UI Rules

Do not put UI layout annotations on new domain classes.

Use Java UI configurer classes:

- `Layout` for navigation, shell style, sections, and persona-specific menus
- `Page` for dashboards and custom screens
- `EntityView` for list columns and field-level hints

An entity should only appear in the UI when it has an `EntityView`. Treat views as the UI allowlist.

Prefer this:

```java
@Component
public class CustomerView implements EntityView {   // not generic — name the target via entity()
    @Override
    public Class<?> entity() {
        return Customer.class;
    }

    @Override
    public void list(ListSpec spec) {
        spec.column("code", "Code");
        spec.column("name", "Name");
    }

    @Override
    public void fields(EntityConfigBuilder fields) {
        fields.field("name").order(10).width("full");
    }
}
```

Avoid adding these deprecated annotations to new code:

- `@UiHint`
- `@UiSection`
- `@DashboardWidget`

## Posting And Rules

Posting logic should be explicit, typed Java in `Postable.handlePosting`.

Validation should be explicit Java:

```java
public class Invoice extends DocumentObject implements Validated {
    @Override
    public List<BusinessRule> rules() {
        return List.of(
            new BusinessRule("items-required", "Add at least one line",
                    () -> items != null && !items.isEmpty()),
            new BusinessRule("total-positive", "Total must be positive",
                    () -> total != null && total.signum() > 0)
        );
    }
}
```

Keep complex reusable policies in Spring services instead of hiding them in annotations.

## First Vertical Slice Template

For most ERPs, the first slice should include:

- one bounded context
- two to four catalogs
- one document with line items
- one enum for document status
- one register affected by posting
- one layout section
- entity views for all visible entities
- one happy-path test
- one rule/posting failure test

Example first slices:

- rentals: Property, Guest, Booking, BookingStatus, Occupancy register
- wholesale: Product, Customer, Warehouse, SalesOrder, Stock register
- services: Customer, Employee, Project, Timesheet, BillableHours register
- manufacturing: Item, WorkCenter, ProductionOrder, Inventory register

## Verification In Consuming ERP Apps

Run:

```bash
./gradlew clean check
```

If the app consumes local framework artifacts, refresh them first in the framework repo:

```bash
./gradlew publishToMavenLocal
```

Then in the ERP app:

```bash
./gradlew clean check
```

For a running app, introspect through the real generated endpoints. Everything under `/api/**` is
**authenticated** (log in with a JSON `POST /api/auth/login` to get a session cookie) and `{name}` is
the entity's **display name** (e.g. `Sales Orders`), not the Java class name. **There is no anonymous
`/api/ui/metadata/manifest` endpoint** — use the generated endpoints directly, or the MCP
`describe_metadata` tool from `onno-mcp-starter` for a model overview:

```text
GET /api/catalogs/{name}                       list a catalog
GET /api/documents/{name}                      list a document
GET /api/documents/{name}/{id}/posting-preview dry-run the movements a post would write
GET /api/registers/{name}/balance              register balances
```

(See [AGENTS.md](AGENTS.md#inspecting-a-running-app-read-this-before-you-curl) for the full auth +
CSRF recipe and [docs/HEADLESS_READ_API.md](docs/HEADLESS_READ_API.md) for the JSON shape.)

The generated API and screens should read like the business to a business user:

- names should be business terms
- contexts should match business areas
- visible entities should have `EntityView`s (the view layer is the UI allowlist)
- refs should point to meaningful catalogs/documents
- registers should clearly represent balances, turnover, or historical facts

If the generated surface reads like tables, plumbing, or implementation detail, improve the model
before adding more features.

## Output Format For AI Agents

After analysis, the agent should respond with:

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

I will implement the first vertical slice:
- ...
```

After implementation, summarize:

```text
Implemented:
- ...

Verified:
- ...

Assumptions:
- ...

Next useful slice:
- ...
```

## Things Agents Should Not Do

- Do not build hand-written CRUD controllers before checking whether the generic framework surface already covers the use case.
- Do not model everything as catalogs.
- Do not use strings for business rules when typed Java is clearer.
- Do not create microservices first. Mark contexts first, then split later if ownership, scale, or deployment needs justify it.
- Do not add licensing text unless the project owner has chosen a license.
- Do not commit credentials, generated frontend builds, local databases, Gradle caches, Node modules, or IDE build output.
