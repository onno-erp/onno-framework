# Agent Guide: Modeling Businesses With onno Framework

This project is a Java/Spring framework for modeling businesses as explicit concepts: catalogs,
documents, tabular sections, registers, constants, enumerations, background jobs, typed process
definitions, UI metadata, and future service boundaries.

The goal is not to clone 1C. The goal is to give a modern Java team, or an AI agent working with one,
a compact way to turn business documentation into a working, inspectable, evolvable application.

## Repository Operating Rules For AI Agents

This repository is intended to be edited by AI coding agents. Keep changes small, verifiable, and friendly
to future agents that may not have the full conversation context.

### Current Status

- The framework is **open-core**. The modules in this repo are Apache-2.0 (see [`LICENSE`](LICENSE)
  and [`NOTICE`](NOTICE)); commercial vertical connectors live in the separate `onno-enterprise` repo.
- Published Maven coordinates use `group = "su.onno"` (Maven Central). The Java packages
  are still `su.onno.*` and the UI Gradle plugin id is `su.onno.ui` — only the publish
  coordinate changed. Releases are tag-driven (`vX.Y.Z`); the latest is in the git tags.
- Java 21 is required. The Gradle wrapper is the source of truth for builds.
- `onno-web-starter` builds the default UI shell from the top-level `onno-ui` npm package with Node 22.22
  via Gradle and bundles it into its jar.
- Schema reconciliation introspects only the database connection's current schema; same-named
  tables in other PostgreSQL schemas do not participate in the diff.
- The architecture reference is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); every `onno.*`
  property is in [docs/CONFIGURATION.md](docs/CONFIGURATION.md); hands-on playbooks live in the
  [`onno` skills](onno-plugin/skills/onno/SKILL.md), with focused entries for modeling, UI, posting,
  runtime verification, and extensions.

### Repo Map

| Path | Role |
| --- | --- |
| `onno-framework` | Core annotations, metadata, schema, posting, complete durable typed process graph/runtime/schema, repository contracts, UI model, and shared types. |
| `onno-framework-starter` | Spring Boot auto-configuration for the core framework, including the cross-node `ClusterEventBus` for horizontal scale-out (Postgres LISTEN/NOTIFY; no-op on H2). |
| `onno-web-starter` | The web layer, Java package `su.onno.web`: generic REST API, access control and record policies, commands/checks, picker options, comments/notifications/presence/tags/media, the SSE event stream, and serving the React app plus its `/api/ui` model. |
| `onno-ui` | npm package `@onno/ui`: the React shell, default screens, page building blocks and typed data hooks apps import. Built by `onno-web-starter` (default shell) and by apps through `su.onno.ui`. |
| `onno-crm-starter` | Composable inbox and messaging around explicitly bound host customer/employee catalogs. Host owns sales, business fields and navigation. |
| `onno-auth-starter` | Security and auth API auto-configuration: database (runtime-managed accounts), in-memory, OIDC/SSO, and resource-server (JWT) modes. |
| `onno-mcp-starter` | MCP server exposing the model + CRUD + register reads + posting as AI-agent tools. |
| `onno-import-starter` | CSV import (preview, mapping, upsert, dry-run) through the UI command path. |
| `onno-print-starter` | Print forms: typed `PrintForm<T>` beans fill Word/Excel templates; record ⋯ menu, list batch zip, admin template uploads, optional PDF. |
| `onno-ui-gradle-plugin` | Gradle plugin (`su.onno.ui`) that builds an app's own React UI (`src/main/ui`, a Vite app on `@onno/ui`) into its jar, generates `model.gen.ts` from the Java model, and seeds a starter UI (`onnoUiInit`). |
| `onno-archunit` | Published ArchUnit rules for onno apps (`su.onno.archunit.OnnoRules.all()`): posting outside `@Transactional`, controllers through the scoped service layer, framework-neutral domain classes, `Ref<T>` references, drained keyset lists. The framework's own `*ArchitectureTest`s use the same rules. |
| `example` | Local example app and smoke-test consumer inside the multi-module build. Do not publish it. |
| `community/` | Community integrations registry: `registry.json` (source of truth) + `registry.schema.json`. `INTEGRATIONS.md` is generated from it by the `generateIntegrationsDoc` Gradle task. |

Commercial connectors (e.g. `onno-tochka-starter`, `onno-yookassa-starter`, `onno-ozon-starter`) are licensed separately and live in the [onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo — not in this build. Authentication (including OIDC/SSO via `onno-auth-starter`) stays in the open-source core.

Community extensions (connectors, SPI implementations, UI add-ons, skills) are built by anyone as separate artifacts on the published core — see [docs/EXTENDING.md](docs/EXTENDING.md) for the contract and conventions, and [CONTRIBUTING.md](CONTRIBUTING.md) for how they get listed. When you build a connector for a user, you can suggest they publish and list it.

### Before Editing

1. Read the relevant module's `build.gradle.kts`.
2. Check whether the change belongs in core, a starter, a Gradle plugin, the UI frontend, or one of the example apps.
3. Prefer extending existing framework concepts over adding parallel mechanisms.
4. Keep public API changes intentional. If you change annotations, model base classes, repository contracts, or auto-configuration properties, update docs and tests in the same pass — see [Keeping docs in sync](#keeping-docs-in-sync).
5. Preserve user changes in the working tree. Do not reset, checkout, or clean files unless the user explicitly asks.

### Keeping Docs In Sync

The docs are part of the public surface. When you change code, update the affected docs **in the same
change** — stale docs are how every drift bug here started (e.g. a long-gone `/api/ui/metadata/manifest`
endpoint and an `onno.base-packages` property that never existed for the core scan). When a doc and the
code disagree, the code wins: fix the doc, don't propagate the claim.

**[docs/CONFIGURATION.md](docs/CONFIGURATION.md) is generated — do not hand-edit it.** Its tables are
rendered from each starter's `spring-configuration-metadata.json` (the `@ConfigurationProperties`
field Javadoc). To change a row, edit the property's Javadoc — or, for a default the processor can't
see, that module's `META-INF/additional-spring-configuration-metadata.json` — then run
`./gradlew generateConfigDocs`. Editorial prose around the tables lives in `docs/_config/`.
`./gradlew check` runs `checkConfigDocs` and **fails** if the committed file drifts from the metadata.

| If you change… | Update |
| --- | --- |
| an annotation, model base class, or lifecycle interface | this file, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and [`onno-plugin/skills/onno/reference/cheatsheet.md`](onno-plugin/skills/onno/reference/cheatsheet.md) |
| a `@ConfigurationProperties` field (any `onno.*`) | edit the field's **Javadoc**, then `./gradlew generateConfigDocs` (regenerates [docs/CONFIGURATION.md](docs/CONFIGURATION.md)); update the owning module README if it documents the property |
| a REST endpoint or its response contract | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/HEADLESS_READ_API.md](docs/HEADLESS_READ_API.md), and the module README |
| the module set or open-core boundary | [README.md](README.md), this file, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/licensing/MODULE-SPLIT-PLAN.md](docs/licensing/MODULE-SPLIT-PLAN.md) |
| an extension surface, naming/namespace conventions, or the community registry | [docs/EXTENDING.md](docs/EXTENDING.md), [README.md](README.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); regenerate [INTEGRATIONS.md](INTEGRATIONS.md) from [community/registry.json](community/registry.json) (`./gradlew generateIntegrationsDoc`) |
| the public UI API (`onno-ui/src/index.ts`, `public-types.ts`) or the UI build plugin | [onno-ui/README.md](onno-ui/README.md), [docs/UI_TOUR.md](docs/UI_TOUR.md), the "UI" section of this file |
| auth modes/endpoints, RBAC | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/CONFIGURATION.md](docs/CONFIGURATION.md), `onno-auth-starter/README.md`, `onno-web-starter/README.md` |
| schema/migration or posting behaviour | this file, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| a feature that was on the roadmap | move it from "next work" to "current state" in [ROADMAP.md](ROADMAP.md) |
| an `OnnoRules` rule or a module `*ArchitectureTest` | [`onno-archunit/README.md`](onno-archunit/README.md), the section of this file the rule enforces |
| an onno ESLint rule (`onno-ui/eslint-plugin/`) | [onno-ui/README.md](onno-ui/README.md#linting), the "UI" section of this file |
| a REST endpoint `onno-drive` calls | [`onno-plugin/skills/onno-verify/onno-drive.mjs`](onno-plugin/skills/onno-verify/onno-drive.mjs) and its test |
| `JdbcProcessEngine` token, join, cancel or migration logic | [docs/formal/](docs/formal/README.md): re-run `./gradlew checkFormalModels`, keep the variable mapping in sync |

The deepest hands-on guidance for modeling and editing the framework lives in the
[`onno` skills](onno-plugin/skills/onno/SKILL.md); keep the focused skills aligned with these docs too.

### Build And Verification Commands

Use the narrowest useful command while iterating, then finish with the broader checks.

```bash
# Compile/check everything, including the example app and frontend packaging.
./gradlew clean check

# Verify artifacts can be consumed by external projects through Maven local.
./gradlew publishToMavenLocal

# Real publishing is tag-driven CI only (Maven Central Portal + the cloud.onno.su/modules mirror);
# never publish from a workstation. See docs/CONSUMING.md and .github/workflows/publish-packages.yml.
```

For targeted iteration:

```bash
./gradlew :onno-framework:test
./gradlew :onno-framework-starter:compileJava
./gradlew :onno-web-starter:buildFrontend
# tsc over onno-ui/src, its tests, and the type-level tests of the public API
./gradlew :onno-web-starter:typecheckFrontend
# ESLint over onno-ui (typescript-eslint, rules of hooks, onno rules); part of `check`
./gradlew :onno-web-starter:lintFrontend
./gradlew :onno-web-starter:compileJava
# TLA+ model of process tokens (downloads tla2tools once; not part of `check`)
./gradlew checkFormalModels
```

Property-based tests (jqwik, `*Properties` classes) run in the normal `test`/`check`. When one
fails, jqwik prints a shrunk sample and replays it first on the next run. Turn a real counterexample
into an example-based regression test, or keep the property `@Disabled("bug: …")` with the minimal
case when the fix is not small.

`publishToMavenLocal` is important: a build can pass with project dependencies but still fail while producing
sources, javadocs, POM metadata, or binary artifacts.

### Intended Development Workflow

Default to **Vite hot reload plus a separate backend** for local development. The canonical guide
is [docs/RUNNING.md](docs/RUNNING.md#intended-development-workflow). Run commands at the repository root:

- `npm run dev` — frontend on `http://localhost:5173`, proxying the backend on 8091
  (`ONNO_BACKEND` overrides it). Reuse an existing backend for UI-only work.
- `npm run dev:backend` — example backend with DevTools, after stopping any packaged example
  already using port 8091.
- `npm run dev:watch` — continuous application Java/resource compilation and `model.gen.ts`
  generation; run beside `dev:backend` when editing Java/model code.

Use the Vite URL for pages, components, styles and linked `@onno/*` UI sources. Do not rebuild jars
or restart the backend for frontend-only edits. The backend-served UI is a built snapshot.
Development commands skip production frontend builds; normal Gradle verification and publishing
retain them. Packaged jars do not enable DevTools restarts, and dependency jar/classpath changes
may require restarting `dev:backend`. Preserve `example/data/bookstore`; do not start a second
backend against the same file database. Standalone consumer apps use their own Vite/backend
commands, not this repository's root scripts.

### Generated Files And Caches

Do not commit generated or local-only files:

- `.gradle/`
- `build/`
- `.kotlin/`
- `data/`
- `onno-ui/dist/`
- `onno-ui/node_modules/`
- `*.tsbuildinfo`

If a tool creates a cache that appears in `git status`, remove it unless it is intentionally part of the change.

### Publication And Consumption Expectations

The reusable surface is the set of published modules under `su.onno:*` on Maven Central.
External projects consume them with a plain `mavenCentral()` (no credentials), or from `mavenLocal()`
(`-SNAPSHOT`) during development. Avoid requiring consumers to use `includeBuild` except for local
Gradle-plugin development.

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

- Javadoc currently emits many missing-comment warnings. Broken links are not acceptable because they can fail
  publication.
- The frontend npm audit may report moderate vulnerabilities. Do not run forced upgrades without checking the
  impact on the UI build and generated assets. `CI` runs the same audits as the release workflow on main
  (`.github/scripts/npm-audit.mjs`, with its reviewed allowlist), so a new advisory surfaces there rather
  than on a release tag. `CI` does not run on pull requests by itself: start it for a branch with
  `gh workflow run CI --ref <branch>` (see [docs/CI_RUNNER.md](docs/CI_RUNNER.md)).

### AI-Friendly Change Style

- Prefer a vertical slice with tests over broad refactors.
- Add tests near the module that owns the behavior.
- For framework behavior, test core logic in `onno-framework` and Spring wiring in the relevant starter.
- For public API changes, include a tiny example or README snippet so future agents see intended usage.
- Name business concepts clearly. The UI model and generated APIs should read like the business domain, not
  like database plumbing.
- Keep generated framework behavior inspectable. Avoid magic string conventions when typed Java APIs already exist.

### Guardrails: Turn Corrections Into Checks

Prose is the weakest layer. When a review corrects an agent and the mistake is mechanically
detectable, add a check instead of another line here:

1. **Make it impossible** through API design (a typed builder, a sealed type, a startup check).
2. **Else make it a rule**: an ArchUnit rule in `onno-archunit` (`OnnoRules`, used by every module's
   `*ArchitectureTest` and by consumer apps) or a module-local architecture test; an ESLint rule in
   `onno-ui/eslint-plugin/` for the UI. The failure message must name the fix, because agents act
   on it.
3. **Verify behaviour** with properties (jqwik) for invariants and the TLA+ model in `docs/formal`
   for process-token semantics.
4. Only then write prose.

Accepted rule exceptions are frozen in a module's `src/test/resources/archunit_store`. Never
regenerate a store to make a new violation pass. Fix a lint error instead of disabling it; a
disable needs a `-- reason`. A store entry is keyed by the rule text, so editing a rule's
`because(...)` re-freezes its current violations — review the store diff when you do.

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

On PostgreSQL, list/ref-picker substring search (`q=`) is backed by a trigram index on the system
columns (`code`/`description`, document `number`). Other String attributes are searched by scan
unless they opt in with `@Attribute(searchIndex = true)` — do that only for fields users actually
search by on large tables; each trigram index costs disk and write throughput in proportion to the
column's text. `onno.schema.search-indexes=off` disables them all.

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

Tabular sections are line-item collections owned by a catalog or document.
Catalog sections use `catalog_<owner>_<section>` tables and the same form editor as documents.
REST/MCP writes accept section-name arrays; omitted sections stay unchanged, `[]` clears them.
Catalog headers and submitted sections commit or roll back together.

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

Each `@TabularSection` must use its own concrete `TabularSectionRow` class. Spring Data JDBC maps a
row class to one generated child table, so startup rejects the same concrete row class reused by
another catalog, document or section. Share common fields and behavior through a base row class, then create
a distinct concrete subclass for each section.

### Accumulation Registers

Accumulation registers are ledgers for quantities, money, or other accumulated balances and turnovers.

Use `BALANCE` when the current balance matters.

Examples:
- Stock on hand
- Cash balance
- Loyalty points
- Open reservations

Balance registers reject posting when a resource total for a dimension tuple touched by that post
would become negative. Existing negative balances on unrelated dimension tuples do not block the
post. For balances where debt or overdrafts are valid, opt that register out explicitly with
`allowNegative = true`:

```java
@AccumulationRegister(
        name = "Cash",
        type = AccumulationType.BALANCE,
        allowNegative = true,
        context = "Finance")
public class CashRegister extends AccumulationRecord {
    // dimensions and resources
}
```

For order-dependent ledgers such as moving-average inventory cost, opt into chronological
restoration explicitly:

```java
@AccumulationRegister(
        name = "InventoryCost",
        type = AccumulationType.BALANCE,
        postingOrder = PostingOrder.CHRONOLOGICAL,
        context = "Inventory")
public class InventoryCostRegister extends AccumulationRecord {
}
```

A backdated post/repost/unpost reverses later affected documents and reposts them in date order in one
serializable transaction. The backdated document's own movements are computed against the register
as of its date: later documents are reversed first, then its `handlePosting` runs again inside the
restoring transaction. Documents with identical timestamps are not re-sequenced among themselves
(known gap, see `ChronologicalPostingProperties`). Keep `handlePosting` deterministic and free of
external side effects.
Posting atomically claims an existing live, unposted document before writing movements. Core
`post(...)` rejects an already-posted document; use the atomic `repost(...)` operation when an
intentional recalculation is required. A posting `@DomainEvent` outbox row is written on the same
JDBI handle as the posting mutation, so both commit or roll back together. Repository save/delete
outbox rows likewise join the active Spring JDBC transaction. Unposting a draft or deleted
document is rejected.

Catalog/document repository deletes are soft: `delete(...)`, `deleteById(...)`, and bulk delete
methods run `BeforeDeleteHandler`, save `deletionMark = true`, and then publish delete events. A
posted document must be explicitly unposted before repository deletion. Ordinary non-onno Spring
Data aggregates retain physical deletes. Generic REST/import/MCP creates and updates run
`onFilling` (create), `beforeWrite`, rules, persistence, then `afterWrite`; validation previews never
run `afterWrite`.

**Who may post is a separate right from who may edit.** `@AccessControl(postRoles = …,
unpostRoles = …)` on a document narrows posting (and re-posting) or unposting to those roles *on
top of* write access; left empty, every writer keeps the right. Deleting a posted document unposts
it, so it needs the unpost right too. `ADMIN` always passes. The gates are enforced on the shared
command path (generic REST, MCP tools, built-in UI actions — the Post/Unpost buttons are hidden
from callers who lack them); trusted code calling `PostingService` directly is not gated, like typed
repositories. For a state-based veto ("a reconciled payment cannot be unposted") implement
`BeforeUnpostHandler.beforeUnpost()` — the counterpart of `BeforePostHandler` — and throw.

```java
@Document(name = "Invoices")
@AccessControl(readRoles = {"RECEPTION", "MANAGER"}, unpostRoles = "MANAGER")
public class Invoice extends DocumentObject implements Postable, BeforeUnpostHandler {
    @Override
    public void beforeUnpost() {
        if (paid) throw new ValidationException("A paid invoice cannot be unposted");
    }
}
```

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

An `@Enumeration` enum may be an accumulation-register `@Dimension`. Posting stores its
deterministic UUID in both movement and totals tables; typed filters and reads accept/return the enum
constant.

Use `Ref<T>` when a field has one target type. If the business meaning genuinely permits several
catalog/document types in the same field, use `PolyRef` with an explicit target allowlist:

```java
@Attribute(required = true)
@RefTargets({BankAccount.class, CashAccount.class, Payment.class})
private PolyRef settlementTarget;
```

The generated schema stores the concrete target type and UUID in one column; generated metadata,
forms, search, display resolution, and links preserve the selected type.

### Information Registers

Information registers store state or facts by dimensions, optionally over time.

Examples:
- Product prices by date
- Exchange rates
- Employee rates
- Supplier lead times
- Configuration settings per warehouse
- Status histories and audit trails (`Periodicity.SECOND`)

```java
@InformationRegister(name = "Prices", title = "Price list", periodicity = Periodicity.DAY)
public class PriceRegister extends InformationRecord {
    @Dimension
    private Ref<Product> product;

    @Resource(precision = 15, scale = 2)
    private BigDecimal price;
}
```

`title` is the display label — localizable, and free of the ASCII/URL-safe constraint on `name`
(which stays the route segment and access-check key). It falls back to `name` when omitted.

`periodicity` is `NONE|SECOND|MINUTE|HOUR|DAY|MONTH|QUARTER|YEAR`. Each write is floored to its
bucket and upserted on `(_period, dimensions…)`, so a second write in the same bucket with the same
dimensions replaces the first. Pick a bucket at least as fine as the rate the fact actually changes:
an audit trail on `DAY` keeps one entry per day, not one per change. `NONE` drops `_period` entirely
and keeps one current row per dimension tuple. When several facts must share one dimension tuple
within the finest bucket, model it as a catalog or document with its own timestamp instead.

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

Give the type a display `title` and each value a human/localized label with `@EnumLabel` — surfaced
in list cells, the dropdown, and `FieldDisplay` — **without** renaming the constants (their names
key the stored UUIDs and any importers/filters that map to them). An optional `color` paints the
value as a **status pill** (the colour of the spreadsheet cell it replaces) in list cells and the
form dropdown; it rides the logical read API as `FieldColor` and `enumValues[].color`, and
the client derives a readable dark/light text colour from it:

```java
@Enumeration(name = "Order Statuses", title = "Статусы заказов")
public enum OrderStatus {
    @EnumLabel(value = "Новый", color = "#F4C7C3") NEW,
    @EnumLabel(value = "Отгружен", color = "#0B8043") SHIPPED
}
```

An unlabelled constant displays as its name, and an uncoloured value renders as plain text, so adding
`@EnumLabel`/`color` is incremental.

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

### Business Processes And Human Tasks

Use a typed business process when work spans several people or business objects and must remember
which step is active. Do not turn a document into a process merely because it has a status:
documents record business events, posting changes registers, and processes coordinate durable work
over time.

Process mutations publish `ProcessTasksChangedEvent` after the durable transaction commits. The web
starter turns it into an audience-scoped `tasks-changed` SSE invalidation, and the built-in task
inbox refetches automatically. Publish process changes through `ProcessEngine`, not a controller-only
side channel, so programmatic starts/completions stay live too.

Step identity is a Java enum implementing `ProcessStepKey`; task, decision, and parallel outcomes
are enums; transitions connect typed node handles, never string names. Definitions are Spring beans
with an explicit stable key, positive version, and payload type. The two-argument constructor
defaults to version 1; use `super("stable-key", 2, Payload.class)` when an active definition evolves:

```java
enum PurchaseStep implements ProcessStepKey {
    MANAGER_APPROVAL, FINANCE_APPROVAL, COMPLETED, REJECTED
}

enum ApprovalOutcome {
    APPROVED, REJECTED
}

final class ApprovalTask implements HumanTask<PurchaseRequest, ApprovalOutcome> {
    @Override
    public Class<ApprovalOutcome> outcomeType() {
        return ApprovalOutcome.class;
    }

    @Override
    public TaskAssignment assignment(PurchaseRequest payload) {
        return TaskAssignment.roles("MANAGER");
    }
}

@Component
final class PurchaseApproval
        extends ProcessDefinition<PurchaseRequest, PurchaseStep> {

    PurchaseApproval() {
        super("purchase-approval", PurchaseRequest.class);
    }

    @Override
    public TaskAssignment startAssignment(PurchaseRequest payload) {
        return TaskAssignment.roles("MANAGER");
    }

    @Override
    protected void define(ProcessGraph<PurchaseRequest, PurchaseStep> graph) {
        var manager = graph.human(PurchaseStep.MANAGER_APPROVAL, new ApprovalTask());
        var finance = graph.human(PurchaseStep.FINANCE_APPROVAL, new ApprovalTask());
        var completed = graph.end(PurchaseStep.COMPLETED);
        var rejected = graph.end(PurchaseStep.REJECTED);

        graph.start().to(manager);
        manager.on(ApprovalOutcome.APPROVED).to(finance);
        manager.on(ApprovalOutcome.REJECTED).to(rejected);
        finance.on(ApprovalOutcome.APPROVED).to(completed);
        finance.on(ApprovalOutcome.REJECTED).to(rejected);
    }
}
```

Calling `definition.graph()` validates the graph once and seals it. Validation rejects missing
outcome branches, duplicate or blank persistent step keys, duplicate enum steps, cross-definition
connections, a missing start target, unreachable nodes, incomplete decision/fork/subprocess routes,
parallel branches that bypass their paired join, and cycles made only of immediate nodes. The
auto-configured `ProcessEngine` persists JSON payloads, version-pinned instances, execution tokens,
transitions, timers, subprocess links, and human work items in framework-managed tables.
Start work from ordinary Java with
`processes.start(definition, payload, new ProcessActor(username, roles))`. Candidates come from
`TaskAssignment.identities(Ref<?>...)` / `.actors(ProcessActorId...)` / `.roles(...)`; `ADMIN` is
the process superuser.

`HumanTask.assignment(payload)` is also the automatic-routing hook: it may call an injected
application service and return the selected employee reference with
`TaskAssignment.identities(employeeRef)`. The persisted owner is the identity catalog record id,
not its mutable login/email. For applications without an `IdentityLink` bean,
`ProcessActorId.of(externalSubject)` is the explicit stable-id fallback.

Override `HumanTask.subject(payload)` with a typed catalog/document `Ref<T>` when the task concerns
a business record. Add `subjectLabel(payload)` for a durable human snapshot such as an order
number. The engine persists both and the task inbox renders a direct link:

```java
@Override
public Ref<Order> subject(PurchaseRequest payload) {
    return payload.order();
}

@Override
public String subjectLabel(PurchaseRequest payload) {
    return "Order " + payload.orderNumber();
}
```

The authenticated UI/API exposes `GET /api/tasks`, claim, delegate, history, and complete commands,
and `@onno/ui` renders them with the `<TaskInbox />` component. A claimed task may be delegated by its assignee (or
`ADMIN`); the reason and assignee transfer are stored in an ordered audit trail. When
`@Bean IdentityLink.of(Employee.class, "email")` is configured, the inbox searches that catalog instead
of asking for a raw username. Application Java still owns the typed graph; the HTTP boundary accepts
an enum constant name because JSON has no Java enum type.

Use `graph.automatic(...)` for deterministic Java work that returns the next immutable payload,
`graph.decision(...)` for an exhaustive typed choice, `graph.timer(...)` for a durable wait,
`graph.parallel(forkStep, Branch.class, joinStep)` for structured fork/join, and
`graph.subprocess(...)` for a version-pinned typed child process. Automatic steps run in the process
transaction and receive a stable `ProcessExecutionContext.idempotencyKey()`; keep them deterministic
and write external effects through an outbox. The starter's JobRunr worker advances due timers and
completed subprocess waits; the database remains the source of truth.

`processes.cancel(instanceId, reason, actor)` atomically cancels live tokens, timers, descendants,
and open/claimed work. The caller policy comes from `cancellationAssignment(payload)` and `ADMIN`
remains the superuser. Never edit a graph in place while its instances are active: retain the old
definition bean, register the new version plus a `ProcessDefinitionMigration`, and call
`processes.migrate(instanceId, actor)`. A migration transforms the payload and maps every active
token id to a typed target step, so parallel work cannot be silently dropped. While parallel
branches are active, map their coordinator to a parallel fork with the same branch enum keys;
finish or cancel the instance before changing branch cardinality.

### Record-Level Access (External Users)

`@AccessControl` grants a role an *entity*; a `RecordAccessPolicy` bean narrows it to *records*.
Use it whenever external users (customers, clients, tenants) must only see their own data — do not
build bespoke endpoints for that.

```java
@Bean
RecordAccessPolicy tenantsByOwner() {
    return RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
            .read(RecordScope.eq("owner", Subject.recordId()))   // the IdentityLink record
            .defaults(d -> d.set("owner", Subject.recordId()));  // filled on create
}

@Bean
RecordAccessPolicy jobsByTenant() {
    return RecordAccessPolicy.forDocument(BuildJob.class).appliesTo("CUSTOMER")
            .read(RecordScope.via("tenant"));                    // visible when the tenant is
}
```

Scopes are a closed predicate tree (`eq`, `in`, `isNull`, `via`, `and`, `or`, `not`, `all`,
`none`) compiled into SQL; values are literals or `Subject.recordId()` / `Subject.username()` /
`Subject.attribute(name)` (contributed by `AccessSubjectContributor` beans). `write(...)` defaults to
the read scope; `write(RecordScope.none())` allows changes only through trusted code. `ADMIN` and
`exemptRoles(...)` are exempt; several applicable policies are OR'ed. Register policies scope by
dimensions. Startup fails on an invalid policy.

Every service-layer query/command method (`su.onno.service`, in `onno-framework-starter`) takes an `AccessSubject`. Request code receives it
as a controller parameter (`AccessSubject subject`), from `CommandContext.subject()` or
`McpToolContext.subject()`; only trusted background code passes `AccessSubject.system()`. **Typed
repositories are unscoped** — custom code serving a user from a repository must check with
`RecordAccess.require(subject, Type.class, id, AccessMode.READ)`. See
[docs/RECORD_ACCESS_POLICIES.md](docs/RECORD_ACCESS_POLICIES.md).

### Period Close

A period close is a change-prohibition date: documents dated on or before it cannot be created,
changed, delete-marked, posted, reposted or unposted. Use `PeriodCloseService` — do not hand-roll
date checks in `beforeWrite`.

```java
periods.close(PeriodScope.global(), LocalDate.of(2026, 10, 31), "admin", "October closed");
periods.close(PeriodScope.context("Finance"), LocalDate.of(2026, 11, 30), "admin", null);
periods.reopen(PeriodScope.global(), LocalDate.of(2026, 9, 30), "admin", "late invoice"); // reason required
```

Scopes are global, per `context`, per accumulation register, or per organization (the
`@PeriodCloseOrganization` field of a document); the date is inclusive. Closes are soft by default —
`@PeriodAdjustment` documents may still be written and posted into the period (audited) while every
other document stays locked; `close(scope, date, true, actor, reason)` is a hard close that admits
nothing until reopened. Enforcement is in core posting (including every later document a
`CHRONOLOGICAL` restoration would repost), repository saves and the generic UI/REST/import/MCP
command path; a rejection is a `PeriodClosedException` (HTTP 409, also from `posting-preview`).
`ADMIN` and `onno.period-close.manage-roles` close and reopen; `onno.period-close.override-roles`
(empty by default — `ADMIN` is not exempt unless listed) or an explicit
`periods.override(new PeriodOverride(actor, reason), ...)` bypass a close, and every close, reopen
and bypass is audited. Mark non-accounting documents `@PeriodCloseExempt`. The admin screen is the
`@onno/ui` component `<PeriodClose />`: register it as a page (for example
`{ path: "/period-close", component: PeriodClosePage, roles: ["ADMIN"] }`, gated on the model's
`features.periodClose`) and link it from navigation. See [docs/PERIOD_CLOSE.md](docs/PERIOD_CLOSE.md).

### Record Change History (Audit Log)

When the business needs to know who changed what and when (a medical record, a contract, a price),
opt the entity in on its annotation. Do not model a hand-written audit table or log register.

```java
@Document(name = "Visits", numberPrefix = "V-", history = true)
public class Visit extends DocumentObject { … }

@Catalog(name = "Patients", history = true)
public class Patient extends CatalogObject { … }
```

Every write of an opted-in record then appends to the framework-managed `onno_record_history`
table, in the write's own transaction: `CREATED` / `UPDATED` / `DELETED` (deletion mark) /
`RESTORED` with one row per changed field (old and new value, tabular-section rows compared by
line), plus `POSTED` / `UNPOSTED` for documents. It is recorded at the storage level, so the
generic command path (REST, UI, import, MCP, actions) and typed `repository.save(...)` /
`delete(...)` produce the same entries. `secret` attributes are recorded as changed without values.
Entities that have not opted in cost nothing; `onno.history.enabled=false` turns recording off
everywhere.

Attribution: a generic command is attributed to its `AccessSubject`. A typed repository save or
`PostingService` call is attributed to the request's authenticated user, else `system`; trusted code
acting for someone else binds the actor explicitly:

```java
HistoryActors.runAs(HistoryActor.named("lab-import"), () -> visits.save(visit));
```

The default record page shows a collapsed **History** panel (`@onno/ui`'s `<RecordHistory />`;
an authored `<RecordPage form={form} history={false}>` overrides it), and
`GET /api/history/{kind}/{name}/{id}?cursor=&limit=` returns the entries. Reading history needs exactly what reading the record needs (`@AccessControl`
plus `RecordAccessPolicy`); references the viewer may not read are masked. Trusted Java reads the
raw entries with `RecordHistory.list(recordId, before, limit)`. Writes that bypass both paths
(hand-written SQL against an entity table) are not recorded.

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
- `BeforePostHandler` / `BeforeUnpostHandler`
- validation in Java services
- `@AccessControl(postRoles = …, unpostRoles = …)` when posting is a different right from editing

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
- an application-owned relay draining the `onno_outbox` table

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
   real authenticated record and keyset-list endpoints; there is no anonymous manifest endpoint.
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

If it coordinates several human or automatic steps over time and must remember active work, use a
typed business process. A document status alone is enough for a local entity lifecycle.

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

Keep UI concerns out of domain classes. Navigation, pages and field presentation belong to the
React app (`src/main/ui`); behaviour belongs to `Commands`, `Checks` and `PickerOptions` beans.

Use getter references for fields in authored Java APIs: `row.enumValue(Order::getStatus,
OrderStatus.class)`, `check.dependsOn(ScheduleEvent::getStartsAt)`. String overloads remain explicit
escape hatches for runtime/dynamic metadata and dot paths (`"participants.employee"`). Command keys,
role names, stable process keys and labels are identifiers or data—not Java field references—and
intentionally remain strings. On the React side, field names are typed from `model.gen.ts`.

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
    private final ExternalRegistry registry;   // any application bean — full DI
    CheckInListener(ExternalRegistry registry) { this.registry = registry; }

    @EventListener
    void onPosted(DocumentPostedEvent event) {
        if (event.document() instanceof CheckIn checkIn) registry.register(checkIn);
    }
}
```

The domain `AfterPostHandler.afterPost()` hook still exists, but it has no Spring access — prefer the
event for anything that touches beans. Neither requires the outbox (that's only for cross-service
event streaming).

`PostingEngine` also publishes document `EntityChangedEvent` notifications
(`POSTED` / `UNPOSTED`), plus a wildcard register `CHANGED` notification so open register views
refresh, after its JDBI transaction commits, including calls made directly through
`PostingService`. Each successful operation publishes one of each; rolled-back operations publish
none. UI commands do not duplicate these notifications.

Post-commit callbacks (`AfterPostHandler.afterPost()`, `DocumentPostedEvent`/`DocumentUnpostedEvent`
listeners, these notifications) cannot fail a committed post: an exception is logged at ERROR and
the framework's remaining callbacks still run (Spring still skips later listeners of the same
event). Anything that must not be lost belongs in a `@DomainEvent` outbox
row, which commits with the posting.

### Print Forms

A print form (1C "печатная форма") is a `.docx`/`.xlsx` template plus a typed `PrintForm<T>` bean
from `onno-print-starter`. Templates use `{{key}}` placeholders and `{{table.column}}` rows that
repeat per item. The record ⋯ menu offers each form the viewer may use (`print-{key}` action key).
Keep values typed (`number`/`date`) so Excel cells stay numeric. List selections batch-print to a
zip; admins replace templates at runtime with `@onno/print`'s `<PrintTemplates />` (stored in
`onno_print_templates`); `spec.pdf()` / `?format=pdf` need a configured `PdfConverter` (Gotenberg or
LibreOffice). Do not build bespoke download controllers for printing.

```java
@Component
class OrderInvoice extends PrintForm<Order> {
    OrderInvoice() { super("invoice", Order.class); }   // classpath:/print/invoice.docx
    @Override protected void fill(Order o, TemplateModel m) {
        m.text("customer", o.getCustomer())
         .table("items", o.getItems(), (row, l) -> row.text("book", l.getBook()).number("qty", l.getQuantity()));
    }
}
```

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

### UI: React on `@onno/ui`, behaviour in Java

The server describes the business model and enforces every rule; it never describes screens. The UI
is the npm package **`@onno/ui`** (`onno-ui/`): a React shell that renders a default list and record
form for every readable entity, navigation grouped by bounded context, and the register reports. An
application with no UI code serves that default shell from `onno-web-starter`. An application that
wants its own pages adds `id("su.onno.ui")` (the `onno-ui-gradle-plugin`) and an ordinary Vite app in
`src/main/ui` — `./gradlew onnoUiInit` seeds one, `buildUi` packs it into the jar, and
`generateUiModel` writes `model.gen.ts`, the TypeScript types of the Java entities, so entity names,
fields and enum values type-check. The reference app is `example/src/main/ui`; the code tour is
[docs/UI_TOUR.md](docs/UI_TOUR.md); the package README is [onno-ui/README.md](onno-ui/README.md).

`main.tsx` registers entities, pages and navigation with `defineApp`. **Entity presentation lives
in one `EntityCustomization<E>` per entity**, its own file `entities/Orders.ts`
(`export const orders = { … } satisfies EntityCustomization<"Orders">`), registered as
`defineApp({ entities: { Orders: orders } })`. It applies wherever the entity shows — its default
list and form, embedded `<EntityList entity="Orders" />`, custom pages: `icon`, `list` (columns,
filters, grouping, calendar, timeline, …), `form`, `fields`, `rowStyle`, `commands`, `recordPanels`,
`comments`, `relations`. Selection and order are always explicit arrays (`list.columns`, `form.fields`); `fields`
is presentation only, keyed by field (`"items.book"` for line columns) — its key order selects and
orders nothing. Logical entity names are checked against the generated model.

- **Entity pages** exist only when the layout differs from the default: `{ list: "Orders",
  component: OrdersPage }` receives `ListPageProps<E>` (`list`, the server descriptor), `{ record:
  "Orders", component: OrderPage }` receives `RecordPageProps<E>` (`form`, the live values, typed
  setter, save method, flags and default layout). A page that would only configure columns or
  field formats belongs in the entity's customization instead. Pages pass only what differs there:
  `<EntityList list={list} overrides={…} />`, `<RecordPage form={form}>`,
  `<RecordFields form={form} fields={["customer", "note"]} overrides={…} />` (`fields` picks and
  orders; default the entity's `form.fields`), `<RecordLines form={form} section="items"
  overrides={…} />`, and arbitrary React components. `selectionToolbar` is the toolbar component
  and `selection` the selected ids. The framework retains validation, posting, saving, dirty
  guards, picker cascades and live refresh. New and duplicate forms use the same record page.
  Use `{ path: "/ops", component: SalesOpsPage }` for ordinary pages. There is no `definePage`.
- **Many-to-many** is a join catalog (a reference to each side plus what the link carries, e.g.
  `ClinicDoctor { clinic, doctor, role }`) — not a denormalized name field or a bespoke endpoint.
  Each side shows it with the entity's `relations` customization:
  `relations: { doctors: { entity: "Clinic Doctors", via: "clinic", columns: ["doctor", "role"] } }`.
  Relation editors must finish every cursor window before enabling replacement writes; a partial
  or failed read must never be submitted as the complete relation set.
  The rows render with the tabular-section editor and save with the record's Save: the form sends
  `@relations` and the server replaces the join rows in the record's transaction, through the
  ordinary write path. The example app's `Supplier Offers` is edited from both Suppliers and Books.
- **Replaceable UI SDK.** Default components and replacements should share public controllers,
  bindings and types. `useRecordForm` is the default record host's controller; injected record
  pages already have it. `useRecordField`/`RecordField` expose typed editor bindings and resolved
  picker context. Custom list renderers receive shared selection and scoped select-all callbacks.
  Export reusable behavior deliberately; do not require apps to import private `src/` paths or
  duplicate save/validation/refresh logic. See [docs/UI_SDK.md](docs/UI_SDK.md) for boundaries.
- **Icons** are imported Lucide components (`import { ClipboardList } from "@onno/ui/icons"`),
  passed as `icon: ClipboardList`, including command icons; never icon-name strings.
- **Navigation** supports section `actions` (ordinary React nodes), nested groups with recursive
  `items`, and branch `roles`. Denied destinations and empty groups are pruned. Active routes
  reveal ancestor groups. The mobile More menu uses the same tree; bottom tabs derive from its
  leaf destinations. Register `navigationComponent: AppNavigation` for a custom layout, composing
  `Navigation`, `NavigationSection`, `NavigationGroup`, `NavigationLink`, and `DefaultNavigation`.
  `NavigationProps` provides the resolved tree, active path, mobile flag and desktop header/footer.
  Custom links share model/page gates and the shell dispatcher. The default desktop rail also
  exposes `shell.navigation.actions`. See [onno-ui/README.md](onno-ui/README.md) for examples.
- **Login page**: `defineApp({ login: MyLogin })` replaces `/login` (the seeded `pages/LoginPage.tsx`
  starts as `<DefaultLogin {...props} />`) (a one-time code form, a lone
  "Sign in with Keycloak" button, …). It gets `LoginPageProps` and signs in with `useSignIn()`
  (`password`, `method(id, credentials)` / `challenge(id, input)` for a Java
  `su.onno.auth.spi.LoginMethod` bean, `redirect(provider)` for SSO); compose `LoginLayout`,
  `SignInMethods`, `PasswordLogin`, `SsoButtons`, `LoginButton`. A `LoginMethod` only names the
  user; the framework creates the session. Do not hand-roll a login controller.
- **Pages** are React components composed from `@onno/ui` exports: `Page`, `Row`, `EntityList`
  (controllable like an `<input>`: `where`, `defaultGroupBy`, `selection` + `onSelectionChange`, …),
  the widgets `Stat`, `Chart`, `Sparkline`, `Gauge`, `RecentRecords`, `Kanban`, `Calendar`,
  `EntityMap`, `TimeRange`, `Setting`, `TaskInbox`, `UserAccounts`, and the starters' packages
  (`@onno/print`'s `PrintTemplates`, `@onno/crm`'s `CrmInbox`). A page at `/` is home; a page at an
  entity route replaces its default screen; `roles` and `when` gate a page and its nav link.
- **Data** is typed per entity: `useRecords("Orders", { where, sort, limit })`, `useRecord`,
  `useAggregate`, `useBalance`, `useTurnover`, `useMovements`, `useInformationRegister`,
  `useConstant`, the write hooks, `useCommand("Orders", "cancel")`,
  `useCommandPresentations(kind, name)` (a surface that lists an entity's commands itself, like the
  CRM row menu, reads their icons and placements from it), and the measures
  `count`/`sum`/`avg`/`min`/`max` (`sum("Orders", "total", { posted: true })`). Which records is
  always a typed `where` object (`{ status: { in: [OrderStatus.NEW] }, total: { gte: 100 } }`),
  sent to the server as JSON. Everything refreshes from the server's live events.
- **Calendar and timeline** list views for date-ranged records are the entity's `list`
  customization (or `EntityList` props for one list): `calendar={{ start: "startsAt", end: "endsAt", mode: "week" }}` and
  `timeline={{ start: "startsAt", end: "endsAt", lane: "organizer", laneEntity: "Employees",
  slotMinutes: 30, startHour: 7, endHour: 22 }}`. They read the ordinary list feed with its search
  and filters, refresh live, open the record on click and — only with write access — save a drag as
  an ordinary record update, so `beforeWrite`, rules and record access apply. Do not build a bespoke
  scheduler or reschedule endpoint for one entity.
- **Links**: `openRecord("Orders", id)`, `openRoute(links.list("Orders", { where }))`; `onno://`
  links (`onno://documents/orders/<id>`, `…/new?customer=<id>`) name what to open for web and
  mobile clients alike. An open replaces the active workspace tab's screen and that tab's Back
  returns, as in a browser; `openRoute(link, { newWorkspaceTab: true })` opens a new tab (use it
  when several records open at once). A New form also seeds from field initializers on the Java class.
- Values handed to the UI are typed, never strings it parses: formats `{ money: "USD" }`,
  `{ date: "medium" }`; inputs `"textarea"`, `"image"`, `"color"`, …; icons are imported Lucide components. The package ships
  them through `@onno/ui/icons`: use `icon: Truck`, `<Truck />`, or `<Icon icon={Truck} />`.

Behaviour stays in Java, as Spring beans in `onno-framework` packages:

- **Commands** (`su.onno.command`) are an entity's behaviour beyond CRUD, declared by a
  `Commands<E>` bean and exposed on every surface — REST
  (`/api/commands/{kind}/{name}[/{key}[/defaults|/batch]]`), the UI and MCP (one tool per command).
  `c.command(key)` runs on one record (`ctx.id()`), `c.collectionCommand(key)` on the entity as a
  whole; `.title(...)`/`.title(row -> …)`, `.description(...)` (also the MCP tool description),
  `.group("Change status")` (a UI submenu), `.roles(...)` (on top of entity write rights; `ADMIN`
  passes), `.availableWhen(row -> …)`/`.enabledWhen(row -> …)` over a `RecordRow<E>`,
  `.input(key, i -> i.title(...).longText().required())` (also `.date()`, `.number()`,
  `.oneOf(...)`, `.reference(Target.class)`), `.inputRows(key, g -> g.column(...))` (read with
  `ctx.inputRows(key)`), `.defaults(ctx -> …)`, and `.run(ctx -> CommandResult.reload())` /
  `CommandResult.success("Cancelled").andRefresh()` / `.info|warning|error(message)`. A result
  carries meaning only (severity, message, details); the client picks toast or dialog.
  `CommandResult.open(Ref.of(Invoice.class, id))`, `.navigate("/route")` and `.andNavigate(route)`
  send the client on (`navigateTo`: app paths and `onno://` links only; command/external URLs are
  rejected), after feedback and refresh. Throw
  `CommandRejectedException` for a purposeful 422 rejection (an open input form shows it inline).
  `required()` inputs and row groups are enforced on the server before the handler runs, on every
  surface (a 422 whose `fieldErrors` name the missing inputs), so handlers need not re-check them.
  `c.dynamic(live -> …)` declares commands from live data. The server enforces write access, roles,
  `onno.web.read-only`, record scope and availability on every surface.
- **Live checks** are `Checks<E>` beans (`su.onno.validation`):
  `v.check(key, ctx -> List<Feedback>).dependsOn(Event::getStartsAt).debounce(...)`, run as the user
  types by `POST /api/checks/{kind}/{name}/{key}`. Advisory; save-time `Validated` rules stay
  authoritative.
- **Picker options** are `PickerOptions<E>` beans (`su.onno.picker`) bound to one reference field
  (`entity()` + `field()`, e.g. `"participants.employee"`) that badge, disable or hide candidates
  from the live form values.
- **Identity**: an `IdentityLink.of(Employee.class, "email")` bean (`su.onno.identity`) links a login
  to its catalog record — greeting, avatar, comment author, task routing, `Subject.recordId()`.

- **Lint** enforces the UI conventions: `onno/no-private-imports` (only `@onno/ui`, `@onno/ui/icons`
  and starter package roots, never `@onno/ui/src/...` or `lucide-react`), `onno/icon-component`
  (icons are imported Lucide components, never name strings), `onno/no-raw-api-fetch` (typed hooks
  or `api`, not `fetch("/api/...")`) and, inside the package, `onno/notify-caught-error` (a caught
  failure goes through `notifyError`, which the api layer has not already toasted). Apps enable
  them with `onno.configs.app` from `@onno/ui/eslint`.

Keep UI concerns out of domain classes; keep business rules out of React. The web layer's endpoints
and settings are in [onno-web-starter/README.md](onno-web-starter/README.md). Hands-on playbooks:
[`onno-ui-entities`](onno-plugin/skills/onno-ui-entities/SKILL.md),
[`onno-ui-pages`](onno-plugin/skills/onno-ui-pages/SKILL.md) and
[`onno-commands`](onno-plugin/skills/onno-commands/SKILL.md).

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
./gradlew :onno-framework:test
```

For changes that affect public artifacts, starter wiring, frontend packaging, or the example app, run:

```bash
./gradlew clean check
./gradlew publishToMavenLocal
```

For UI frontend-only changes, at minimum run:

```bash
./gradlew :onno-web-starter:testFrontend   # typecheckFrontend first, then Vitest
./gradlew :onno-web-starter:buildFrontend
./gradlew :onno-web-starter:processResources
```

### Inspecting a running app (read this before you curl)

Everything under `/api/**` is **authenticated** and most of it is **CSRF-protected**. Two things trip
up every agent:

1. **There is no anonymous metadata/manifest endpoint.** Older notes pointed at
   `/api/ui/metadata/manifest`; no controller serves that path, so it returns `404`. To introspect
   the model at runtime, hit the real generated endpoints below.
2. **The SPA fallback is navigation-only.** Unknown `/api/**` routes and missing assets return
   `404`; only HTML `GET` navigation under `onno.ui.path` falls back to the SPA shell.

**Prefer the driver over hand-rolled curl.** The [`onno-verify`](onno-plugin/skills/onno-verify/SKILL.md)
skill ships `onno-drive`, a dependency-free Node CLI:
`node onno-plugin/skills/onno-verify/onno-drive.mjs login --base http://localhost:8091 --user … --password …`
stores the session cookie and CSRF token. Run `onno-drive features` first: it prints the **feature
map** — every entity, command, register, process and setting under a route-shaped id such as
`documents/orders#cancel` — from `/api/ui/model`. Use those ids with the user and in PRs. Then drive
the change with `list/get/create/update/validate/post/unpost/preview-posting/command/balance/raw`,
and attach the outputs (feature id, before/after JSON, posting preview) to the PR's "Verification
artifacts" section. Custom React pages are not in the map; read the app's `src/main/ui/main.tsx`.
The curl recipe below documents what the driver does.

**Authenticate first (session cookie + CSRF), then call the API.** Login is a JSON POST that sets a
`JSESSIONID` session cookie — *not* HTTP Basic. Mutations (`POST`/`PUT`/`DELETE`) require the CSRF
token: it's delivered in a readable `XSRF-TOKEN` cookie and echoed back in the `X-XSRF-TOKEN` header.
Users come from `onno.auth.users[*]` in `in-memory` mode, or from the `onno_auth_user` table in
`database` mode (where that list only seeds an empty store once). The starter ships **no** default
credentials; the bundled example app configures `admin@onnobooks.local`/`admin` and
`manager@onnobooks.local`/`manager` for its demo. For a real app use `onno.auth.mode=database` so no
config-file password stays valid forever.

```bash
# 1. Log in — saves the session cookie and the XSRF-TOKEN cookie into the jar. Login itself is CSRF-exempt.
curl -sc cookies.txt -H 'Content-Type: application/json' \
     -d '{"username":"admin@onnobooks.local","password":"admin"}' http://localhost:8080/api/auth/login

# 2. Reads just need the session cookie:
curl -sb cookies.txt 'http://localhost:8080/api/list/catalogs/Books?limit=50'
curl -sb cookies.txt 'http://localhost:8080/api/list/documents/Orders?limit=50'

# 3. Mutations also need the CSRF header (value taken from the XSRF-TOKEN cookie):
XSRF=$(awk '/XSRF-TOKEN/{print $7}' cookies.txt)
curl -sb cookies.txt -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
     -d '{ …entity JSON… }' http://localhost:8080/api/catalogs/Books
```

`{name}` is the entity's **display name** (e.g. `Books`, `Orders`), *not* the Java class
name (`Book` → 404). The real endpoints (all under `/api`, served by `onno-web-starter`):

```text
GET    /api/list/catalogs/{name}?cursor=&limit=    collection window
GET    /api/catalogs/{name}/{id}                   one
POST   /api/catalogs/{name}                        create        (CSRF)
PUT    /api/catalogs/{name}/{id}                   update        (CSRF)
DELETE /api/catalogs/{name}/{id}                                 (CSRF)
GET    /api/list/documents/{name}?cursor=&limit=   collection window
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

For the exact JSON the read endpoints return — logical keys, `FieldDisplay`/`FieldRef` expansion,
storage compatibility, secret redaction, list vs get, inlined tabular sections — see
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

### CRM composition

Enable messaging with a typed `CrmCustomerBinding` for the host's existing catalog. Optional
`CrmAgentBinding` reuses its employee/identity catalog. No CRM customer/agent catalog, sales pipeline,
customer-stage model, EAV custom fields, business merge editor or navigation is installed. Fields
and CRUD remain ordinary host catalogs and their default screens. Provider imports call an explicit host
inbound-contact policy. Customer UUIDs in CRM are scoped to the persisted binding identity; do not
rebind a database to another catalog without migrating links. Workspace roles and host customer
permissions both apply. Personal chat groups require `CrmFeatures(true)`, and personal provider accounts require `CrmFeatures.defaults().withPersonalAccounts(true)` (which also closes generic CRM catalogs to ADMIN); conversation statuses
require optional `CrmStateConfiguration`. Sales models and metrics remain host-owned.

The host owns consolidation, audit and any reversal. Its transaction may call
`CrmContactService.transferLinks` before deleting the source; that command moves only CRM links and
canonical redirects while retaining provider destinations. It has no automatic undo or HTTP merge
surface. Customer comments, sales records and other host references stay the host's responsibility.
See `onno-crm-starter/README.md` for the full contract.

Standard catalog/document tables may opt into visual selection with the list customization
`selectionCheckboxes: true`. Reuse the host Checkbox and the existing selection/batch
command state; do not add a second bulk-action backend. The header checkbox and ⌘A/Ctrl+A select
all matching records through the scoped feed, including unloaded rows and collapsed groups. Query
changes or clearing selection cancel pending select-all; failures preserve the previous selection.
Shift-range selection follows loaded rows.

### CRM workspace authorization

`CrmInboxWorkspace` beans define team inbox views independently of `Inbox` channel accounts. Keep
provider routing on `Conversation.inbox`; workspace selection predicates never mutate it. With
workspace beans, use scoped CRM endpoints and pass `workspace` on commands. Non-admin generic
conversation/message reads are intentionally denied by `EntityAccessPolicy`. Contacts remain
shared master data; filter related conversation collections and authorize every affected chat
before a merge. Keep event invalidations content-free for workspace subscribers.

The local Gmail adapter groups email threads by canonical customer within its own mailbox.
Startup consolidates earlier thread-per-chat imports transactionally: messages and comments move,
thread routes remain intact, superseded conversations are soft-deleted, and source/target IDs are
recorded in `onno_crm_gmail_grouping`. Replies retain the thread selected when queued.


The Instagram adapter in `onno-crm-channels-starter` owns the opt-in `onno.crm.channels.instagram.enabled`
connection, private token-file reads, and additive `onno_crm_ig_*` tables. `Channel.INSTAGRAM` is
append-only; preserve existing enum constants. Provider-visible polling messages deduplicate by
account/message ID and route by account/peer, preserving contact canonicalization. Polling imports
only messages at or after the account's persisted UTC activation cutoff (`onno_crm_ig_sync`), never
history; never reset or overwrite that cutoff in code. Polling is head-first and bounded per poll, and
per-thread watermarks/cursors/checkpoint advance only after message inserts commit. Outbound sends
are queued transactionally and enforce a 24-hour reply window; uncertain sends require explicit
retry. This adapter does not configure public webhooks or publish the Meta app.

Packaged channels now live in `onno-crm-channels-starter` (Apache-2.0). Use canonical `onno.crm.channels.<provider>.*` keys and preserve private external credentials during migration. WhatsApp accepts only signed raw-body webhooks, scopes them to its WABA/number, deduplicates message IDs and queues replies after commit. `DeliveryStatus.READ` is additive. Adopter guidance lives in the three `onno-crm-*` skills.

Selection toolbar extensions: an entity's `list.selection` component (React) is mounted when rows are
selected and the viewer has write access. It receives `ids` and `complete()` (clear selection and
reload after success). Commands must enforce their own server-side authorization.

Record tags are a web-starter feature: render `@onno/ui`'s `<EntityTags kind name id />` (for example
in a `recordPanels` component) or `EntityTagMenu`.
Use stable tag definitions/assignments through `TagService` and `/api/tags`, not delimiter-based
fields for new tagging features. TagAccessPolicy adds record-level authorization.

Customer stages are ordinary host fields. Optional CRM conversation-status choices and transitions
come from application `CrmStateConfiguration` code. The host owns pages and action contributions.

CRM channels use extensible string keys with connector-owned `CrmChannelDefinition` metadata
(`GET /api/crm/channels/types`); no fixed channel enumeration or Channels page is installed.
`<CrmConnections setups={[channelSetups]} />` takes provider setup forms as components; bundled provider
setup and branding live in the channels starter (`@onno/crm-channels`). The inbox's columns, sort and
filters are `<CrmInbox list={…} />` props. Status bindings read an existing host
catalog or enumeration through `CrmStateConfiguration.catalog(...)`/`.enumeration(...)`, with no
CRM status table or mirrored records. Channel keys and status UUIDs are breaking storage changes.

Priority is optional: bind a host catalog/enum with `CrmPriorityBinding.catalog(...)` or
`.enumeration(...)`; use `.options()` in an ordinary priority filter. No priority enum, default or
implicit column/filter is installed. The priority UUID is scoped to that host source.
Assignment, close/reopen, status-change and manual identity-linking HTTP actions are not bundled.
Host `Commands<Conversation>` beans declare ordinary record commands; extension buttons receive
their descriptors and call `context.execute(key, inputs)`. The scoped API is
`/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions` (GET `[{id, title, visible,
enabled, group, inputs}]`, POST `/{key}` with `{inputs:{...}}`). It checks workspace write access, application
read-only mode, command roles and the commands' `availableWhen`/`enabledWhen` rules. CRM adds no business-field mutations or automatic history
messages for these actions. Hosts own the handler and any desired history records. Connector code
can still use the low-level identity-link service for provider routing.

### Personal CRM connection privacy

The `@onno/crm` `<CrmConnections>` component uses `CrmAccountProvider` descriptors and durable
`CrmConnectionAccounts` ownership. Gmail supports multiple shared/personal accounts; other bundled
adapters remain shared-only. Personal ownership restricts scoped APIs even for other administrators.
Generic CRM catalogs/exports/events are denied once personal accounts exist. The deny-only
`EntityAccessPolicy.includesAdministrators()` opt-in preserves default ADMIN behavior for existing
policies while allowing these privacy boundaries. Keep docs, permission tests and OAuth isolation
tests aligned when extending providers.

The CRM's presentation is the props of `@onno/crm`'s components (`CrmInbox`, `CrmConnections`,
`CrmContactDetails`, `CrmConversationPage`, `CrmConversation`, `CrmContactPage`); keep it apart from
workspace authorization — the components neither register routes nor grant access. To rebuild a
CRM screen, compose the exported parts (`ConversationRow`, `ChatTimeline`, `CrmComposer`, …) over
the hooks (`useCrmConversation`, `useCrmComposer`) or swap one part with `CrmInbox`'s `components`;
add business entries to the chat with `timelineSources`, never by forking `CrmInbox.tsx`.

Telegram business accounts use the `telegram-business` managed provider and personal/shared account
ownership. Never infer CRM ownership from a Telegram connection event: require the expiring /start
proof from the matching Telegram user. Keep business_connection_id on every outbound text/upload,
recheck rights, and never fall back to sending as the ordinary bot.

CRM file-only messages rely on conditional text-or-attachments validation: keep the Body attribute
optional. Gmail/Telegram ingest provider file bytes through CrmAttachments; never persist token-bearing
provider download URLs. Filesystem media metadata lives under an unservable .metadata directory.


## Collection pagination implementation

List export uses the audited `EntityListExport` helper: grouped catalog/document windows must reuse
the query services' scoped predicates, and register exports must pass the same `where` as their feed.
Keep the export role gate, row cap, secret exclusion and prepared-file journal in place. The web
controller writes bounded windows in one repeatable-read snapshot before sending an attachment;
`RecordPolicyConformanceTest` covers the download path as well as ordinary JSON reads.

Forward collection windows use `su.onno.query.KeysetWindow` with typed tuples issued by
`su.onno.query.Cursor`. Keep access predicates in SQL before LIMIT. Register balances seek by
the dimension primary key; do not invent an `_id` for totals. Numeric offset pagination and legacy response aliases are removed. Process assignment JSON has a transactional `onno_process_work_candidates`
projection and resumable backfill; keep it in sync when creating assignments. Notification target
columns are a normalized projection of internal record links, backfilled at startup, and allow
record-policy filtering before pagination. Wire contracts live in docs/HEADLESS_READ_API.md.


All collection transports now use the context-bound v2 Cursor codec (100 default, 500 maximum).
Validate cursor JDBC value types before executing seek comparisons; malformed positions restart,
foreign contexts are 400. Preserve SQL DATE values, not String approximations. CRM projections
must share the Spring transaction/connection with repository callbacks. SDK register reads expose
continuations; accounting widgets consume all pages. Every new collection endpoint returns
`{rows, nextCursor, hasMore}` (wrap with `su.onno.ui.KeysetWindow.envelope`); never add a
bespoke `entries`/`items`/`before` shape. A Java method returning a plain `List` must stay
complete: implement it as `KeysetWindow.drain(c -> page(..., c, KeysetWindow.MAX_LIMIT))`, never
as the first window's `.rows()`. Declare ordering columns the schema makes `NOT NULL` with `KeysetWindow.Order.notNull(...)`
and give them one direction: the window then emits a bare ORDER BY and a row-value seek that a
matching composite index serves (nullable columns need a `CASE` NULLS-LAST key no index can use).
Every tuple cursor needs a unique trailing tiebreaker (id), and
its feed context must include only filter values, never transport params (`cursor`, `limit`).
The cursor-only collection contract
and bounded-selector exceptions are specified only in docs/HEADLESS_READ_API.md.
