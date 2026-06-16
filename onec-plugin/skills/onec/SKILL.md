---
name: onec
description: >-
  Expert playbook for the onec-framework — the Java/Spring "model-the-business" ERP toolkit
  (catalogs, documents, tabular sections, accumulation/information registers, enumerations,
  constants, posting, schema migration, generic REST + DivKit UI, MCP, auth/RBAC). Use when working
  in the onec-framework or onec-enterprise repos, or building/modifying an ERP app that consumes
  io.github.onec-erp:onec-* (com.onec.* packages) — i.e. anything touching @Catalog, @Document,
  @AccumulationRegister, @InformationRegister, @TabularSection, Postable/handlePosting, Ref<T>,
  Layout/Page/EntityView, or onec.* config. Covers modeling a business into framework concepts,
  posting/validation rules, diff-based schema migrations, the runtime API + auth recipe, the module
  map, and the rule to keep docs in sync with code.
---

# onec-framework

`onec-framework` models a business as **typed Java metadata** and generates the rest: database
schema, repositories, a type-safe query layer, a generic REST API, a server-driven (DivKit) UI, an
MCP tool surface for AI agents, and migration history. You never hand-write tables, DTOs, or CRUD
controllers. Behaviour that *is* code — posting, validation, lifecycle hooks, UI authoring — is
plain, compiler-checked Java, never string-mapped config.

Java packages are `com.onec.*`. Maven group is `io.github.onec-erp` (open core, Apache-2.0);
commercial connectors are `com.onec.enterprise`. Desktop Gradle plugin id is `com.onec.desktop`.
Requires Java 21 and Spring Boot 3.4.x.

## First principle: model the business before writing code

Do not start from tables or controllers. Ask what the company does, what it tracks, what documents
move work forward, what balances matter, and what rules must never be violated. Translate nouns and
verbs into framework concepts, then implement one vertical slice end to end and iterate. The full
interview script and per-domain question sets live in [AGENTS.md](https://github.com/onec-erp/onec-framework/blob/main/AGENTS.md) — follow it
when a user asks you to model a business.

## Concept → annotation cheat sheet

| If it is… | Use | Base class |
| --- | --- | --- |
| a stable list users choose from (Products, Customers, Warehouses) | `@Catalog` | `CatalogObject` |
| a fixed list controlled by code (OrderStatus) | `@Enumeration` (on a Java `enum`) | — |
| a business event with a date + number (Invoice, Shipment) | `@Document` | `DocumentObject` |
| a repeated line inside a document (invoice lines) | `@TabularSection` field | row `extends TabularSectionRow` |
| a current balance that must not go negative (Stock, Cash) | `@AccumulationRegister(type = BALANCE)` | `AccumulationRecord` |
| period activity totals (Revenue, Hours) | `@AccumulationRegister(type = TURNOVER)` | `AccumulationRecord` |
| historical facts by dimension over time (Prices by date) | `@InformationRegister` | `InformationRecord` |
| one global setting (CompanyName) | `@Constant` | plain class with one field |
| scheduled/async work | `@ScheduledJob(cron=…)` / `@Scheduled` | — |
| something another team/service owns | `context = "…"` on the annotation | — |

A reference to another entity is `Ref<T>` (`com.onec.types.Ref`) — a typed `(Class<T>, UUID)`,
stored as a UUID column, resolved with `RefResolver`. Use it instead of embedding whole objects.

The exact attributes and defaults of every annotation, the fields each base class carries, and all
lifecycle interfaces are in [reference/cheatsheet.md](reference/cheatsheet.md). Read it before
writing model classes — guessing an attribute name or default wastes a build cycle.

## Canonical vertical slice

```java
// 1. Catalog (master data)
@Catalog(name = "Customers", codePrefix = "C-", context = "Sales")
@Getter @Setter
public class Customer extends CatalogObject {
    @Attribute(required = true, length = 200) private String name;
}

// 2. Document with lines, validation, and posting
@Document(name = "Sales Orders", numberPrefix = "SO-", context = "Sales")
@Getter @Setter
public class SalesOrder extends DocumentObject implements BeforeWriteHandler, Validated, Postable {
    @Attribute(required = true) private Ref<Customer> customer;
    @Attribute(precision = 15, scale = 2) private BigDecimal total;
    @TabularSection(name = "items") private List<SalesOrderLine> items = new ArrayList<>();

    @Override public void beforeWrite() {
        total = items.stream().map(SalesOrderLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    @Override public List<BusinessRule> rules() {
        return List.of(new BusinessRule("items-required", "Add at least one line",
                () -> items != null && !items.isEmpty()));
    }
    @Override public void handlePosting(PostingContext context) {
        var sales = context.movements(SalesRegister.class);
        for (var line : items) sales.addReceipt(r -> {
            r.setProduct(line.getProduct()); r.setQuantity(line.getQuantity()); r.setAmount(line.amount());
        });
    }
}

// 3. Register the posting writes into
@AccumulationRegister(name = "Sales", type = AccumulationType.TURNOVER, context = "Sales")
@Getter @Setter
public class SalesRegister extends AccumulationRecord {
    @Dimension private Ref<Product> product;
    @Resource(precision = 15, scale = 3) private BigDecimal quantity;
    @Resource(precision = 15, scale = 2) private BigDecimal amount;
}

// 4. EntityView — REQUIRED for the entity surface to be served (no view → 404; the view layer is
//    the allowlist). A view alone does NOT put the entity in the sidebar — a Layout section must
//    list it (nav is curated). EntityView is NOT generic; it names its target via entity().
@Component
public class CustomerView implements EntityView {
    @Override public Class<?> entity() { return Customer.class; }
    @Override public void list(ListSpec spec) { spec.column("code", "Code"); spec.column("name", "Name"); }
    @Override public void fields(EntityConfigBuilder f) { f.field("name").order(10).width("full"); }
}
```

Implement in this order: catalogs + enums → documents + tabular sections → registers + posting →
constants/jobs/integrations → UI (`Layout`/`Page`/`EntityView`) → tests. Then verify against a
running app (see below). Summarize what you modeled and your assumptions.

## Posting & rules — the two gotchas that bite everyone

- **Posting runs in its own JDBI transaction**, not enlisted in an ambient Spring `@Transactional`.
  **Save the document (let it commit), then post.** Wrapping save+post in one `@Transactional`
  method silently leaves register movements written but `_posted = false`.
- **React to a post with a Spring `@EventListener` on `DocumentPostedEvent`** (full DI), not from
  inside `handlePosting` (that runs in the posting transaction and should only write movements). The
  domain `AfterPostHandler.afterPost()` hook exists but has no Spring access — prefer the event for
  anything that touches beans:

  ```java
  @Component
  class CheckInListener {
      private final ExternalRegistry registry;
      CheckInListener(ExternalRegistry registry) { this.registry = registry; }
      @EventListener void onPosted(DocumentPostedEvent e) {
          if (e.document() instanceof CheckIn c) registry.register(c);
      }
  }
  ```

Validation is typed Java: implement `Validated`, return `BusinessRule`s whose condition is a
`BooleanSupplier` over the entity's fields. Rules run before write and before posting. There is no
string-mapped declarative posting or rule grammar — posting is `Postable.handlePosting`, rules are
`BusinessRule`, both compiler-checked.

## Schema migrations — derived from metadata, diffed at boot

The schema is reconciled from the model at startup; there are no migration files for structural
changes. Controlled by `onec.schema.mode` (`apply` default / `plan` / `validate` / `off`) and
`onec.schema.allow-destructive` (gates drops + narrowing changes).

- **Renames keep data**: declare the former name with `previousNames` on `@Catalog`/`@Document`/
  `@Attribute`, or the engine treats it as drop + add.
- **Data migrations** (backfills, seeding, reshaping) are `AppMigration` beans: a `version()`
  string compared segment-wise and `migrate(MigrationContext)`. Each runs once per database, in
  version order, inside a transaction, recorded in `onec_schema_history`.

```java
@Catalog(name = "Counterparties", previousNames = "Suppliers")
public class Counterparty extends CatalogObject {
    @Attribute(length = 50, previousNames = "phone") private String phoneNumber;
}
```

## UI authoring — beans, never annotations

UI is authored as Spring beans on the *view* side, never as annotations on domain classes:

- **`Layout`** — nav structure, shell style, branding, persona (`profile()`), `roles`, viewport.
  **Nav is curated:** an entity shows in the sidebar only if a `spec.section(...)` lists it; there is
  no auto-listing of unclaimed catalogs.
- **`Page`** — a composed route (dashboards): `compose(PageBuilder)` with `widget`/`text`/`list`/`custom`.
- **`EntityView`** — per-entity `list(ListSpec)` columns/filters and `fields(EntityConfigBuilder)`
  hints. **An entity surface is only served if it has an `EntityView` for the active profile** (no
  view → `404`). That is *necessary but not sufficient* for nav presence: a view makes the entity
  reachable by direct route, but it appears in the sidebar only once a `Layout` section lists it.

Do **not** add `@UiHint`, `@UiSection`, or `@DashboardWidget` to new code — they are deprecated.
The widget DSL and `config(key,value)` reference are in
[onec-ui-starter/README.md](https://github.com/onec-erp/onec-framework/blob/main/onec-ui-starter/README.md).

## Inspecting a running app

Everything under `/api/**` is **authenticated** and mutations are **CSRF-protected**. Two traps:

1. **There is no anonymous manifest endpoint.** `/api/ui/metadata/manifest` does not exist (the only
   `/manifest` is the desktop shell's). Introspect the model via the real generated endpoints or the
   MCP `describe_metadata` tool.
2. **Unknown non-`/api` paths return the SPA `index.html` with HTTP 200**, not 404 — a wrong path
   *looks* like success. Test API URLs, not page URLs.

`{name}` is the entity's **display name** (`Properties`, `Sales Orders`), not the class name. Login
is a JSON POST that sets a session cookie; mutations need the CSRF token from the `XSRF-TOKEN`
cookie echoed in `X-XSRF-TOKEN`:

```bash
curl -c jar.txt http://localhost:8080/api/config                       # get XSRF-TOKEN cookie
curl -b jar.txt -c jar.txt -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"…"}'
curl -b jar.txt http://localhost:8080/api/catalogs/Properties          # reads need the session
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)                        # mutations need the token
curl -b jar.txt -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d '{ …entity JSON… }' http://localhost:8080/api/catalogs/Properties
```

The real endpoints (auth `/api/auth/login|logout|me`, catalogs/documents/registers, posting,
media, import, MCP, etc.) are cataloged in [docs/ARCHITECTURE.md](https://github.com/onec-erp/onec-framework/blob/main/docs/ARCHITECTURE.md);
the JSON read contract (`{col}_display`/`{col}_ref`, `__SECRET_SET__` redaction, list vs get) is in
[docs/HEADLESS_READ_API.md](https://github.com/onec-erp/onec-framework/blob/main/docs/HEADLESS_READ_API.md).

## Two more behaviours you will hit

- **Upserts are decided by `isNew`.** Base objects default `isNew = true`; the framework resets it
  to `false` after every load. So "load, mutate, save" UPDATEs. If you build an entity by hand
  (new instance, set a known id keyed on an external system) and want an UPDATE, call `setNew(false)`
  yourself, or the save INSERTs and you get a duplicate-key error.
- **Per-entity RBAC is deny-by-default.** A catalog/document/register is invisible and uneditable
  unless its `@AccessControl(readRoles=…, writeRoles=…)` grants the caller; the `ADMIN` role is a
  superuser. This applies to the REST API, the UI, and the MCP tools alike.

## Modules & config

Core (`onec-framework` + `-starter`), plus opt-in starters: `onec-ui-starter`, `onec-auth-starter`,
`onec-mcp-starter`, `onec-import-starter`, `onec-kafka-starter`, `onec-mail-starter`,
`onec-print-starter`, `onec-desktop-starter` (+ Gradle plugin). Each integration starter is gated by
`onec.<module>.enabled` (default on). The full module map is in
[docs/ARCHITECTURE.md](https://github.com/onec-erp/onec-framework/blob/main/docs/ARCHITECTURE.md); every `onec.*` property is in
[docs/CONFIGURATION.md](https://github.com/onec-erp/onec-framework/blob/main/docs/CONFIGURATION.md).

Minimal app config: point the scan at your packages (or rely on the `@SpringBootApplication`
package), give it a datasource, and seed a user. The core scan property is **`onec.scan-packages`**,
not `onec.base-packages` (that name only exists for mail/print template scanning).

```yaml
spring:
  datasource: { url: "jdbc:h2:file:./data/app", driver-class-name: org.h2.Driver, username: sa }
onec:
  auth:
    users: [{ username: admin, password: admin, roles: [ADMIN] }]
```

## Building a commercial connector (onec-enterprise)

A connector is a Spring Boot **auto-configuration starter that wraps an external API** — it does
**not** define framework metadata; the host app does. The pattern and idioms (typed HTTP client with
token-refresh + backoff, `RefResolver` to map `Ref<T>` to external schemas, reacting to domain state
changes, stateful audit ledgers, one `onec.<connector>.enabled` switch) are in
[reference/connectors.md](reference/connectors.md).

## Verify your work

```bash
./gradlew clean check                         # compile + test everything incl. example + frontend
./gradlew publishToMavenLocal                 # verify consumable artifacts (sources/javadoc/POM)
./gradlew :onec-framework:test                # narrow iteration
./gradlew :onec-ui-starter:buildFrontend      # frontend-only changes
```

`publishToMavenLocal` matters: a build can pass with project deps but still fail producing sources,
javadocs, or POM metadata.

## Report framework bugs

When you build an app *on* the framework, separate a **framework bug** from your own modeling
mistake. A framework bug is reproducible behaviour that contradicts these docs or the framework's
contract — a generated endpoint returns the wrong shape, a valid annotation is ignored, posting
corrupts a register, a migration drops data it shouldn't, an `onec.*` property has no effect. A
modeling mistake is yours to fix (missing `EntityView`, wrong `context`, save+post in one
transaction).

When you hit a genuine framework bug, don't silently work around it — **surface it and file an
issue** so it gets fixed upstream:

- Core framework → [`onec-erp/onec-framework`](https://github.com/onec-erp/onec-framework/issues)
- Commercial connectors (Guesty / SES.HOSPEDAJES / Tochka) → the `onec-erp/onec-enterprise` repo.

Reduce it to the smallest repro first, **check for duplicates**, and — because filing is a public,
outward action — **confirm with the user before creating it** (or hand them the command if you
can't). Then:

```bash
gh issue list --repo onec-erp/onec-framework --search "<keywords>"     # dedupe first
gh issue create --repo onec-erp/onec-framework --title "<concise symptom>" --body "$(cat <<'EOF'
**Version:** io.github.onec-erp:onec-framework-starter:<version>
**What I did:** <minimal model snippet / steps>
**Expected (per docs/contract):** …
**Actual:** <observed — the error or wrong response>
**Minimal repro:** <smallest catalog/document/posting that triggers it>
EOF
)"
```

Report the filed issue (with its URL) in your summary. If you're unsure whether it's a framework bug
or your own modeling, say so in the issue — a tight, reproducible repro beats a vague report.

## Keep the docs in sync — required

When you change anything in the public surface, update the docs in the **same** change. This is not
optional housekeeping — stale docs are how every drift bug in this repo started. Specifically:

| If you change… | Update |
| --- | --- |
| an annotation / base class / lifecycle interface | `AGENTS.md`, [reference/cheatsheet.md](reference/cheatsheet.md), `docs/ARCHITECTURE.md` |
| a `@ConfigurationProperties` field (any `onec.*`) | `docs/CONFIGURATION.md` + the owning module README |
| a REST endpoint or its contract | `docs/ARCHITECTURE.md` (endpoint table), `docs/HEADLESS_READ_API.md`, the module README |
| the module set / open-core boundary | `README.md`, `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/licensing/MODULE-SPLIT-PLAN.md` |
| auth modes / endpoints | `docs/ARCHITECTURE.md`, `docs/CONFIGURATION.md`, `onec-auth-starter/README.md`, `onec-ui-starter/README.md` |
| a shipped roadmap item | move it from "next work" to "current state" in `ROADMAP.md` |

Verify a doc claim against the code before repeating it — several historical docs referenced a
non-existent `/api/ui/metadata/manifest` and an `onec.base-packages` property. When a doc and the
code disagree, the code wins; fix the doc.
