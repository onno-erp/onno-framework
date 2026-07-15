---
name: onno
description: >-
  Expert playbook for the onno-framework — the Java/Spring "model-the-business" ERP toolkit
  (catalogs, documents, tabular sections, accumulation/information registers, enumerations,
  constants, posting, schema migration, generic REST + DivKit UI, MCP, auth/RBAC). Use when working
  in the onno-framework or onno-enterprise repos, or building/modifying an ERP app that consumes
  su.onno:onno-* (su.onno.* packages) — i.e. anything touching @Catalog, @Document,
  @AccumulationRegister, @InformationRegister, @TabularSection, Postable/handlePosting, Ref<T>,
  Layout/Page/EntityView, or onno.* config. Covers modeling a business into framework concepts,
  posting/validation rules, diff-based schema migrations, the runtime API + auth recipe, the module
  map, and the rule to keep docs in sync with code.
---

# onno-framework

`onno-framework` models a business as **typed Java metadata** and generates the rest: database
schema, repositories, a type-safe query layer, a generic REST API, a server-driven (DivKit) UI, an
MCP tool surface for AI agents, and migration history. You never hand-write tables, DTOs, or CRUD
controllers. Behaviour that *is* code — posting, validation, lifecycle hooks, UI authoring — is
plain, compiler-checked Java, never string-mapped config.

Java packages are `su.onno.*`. Maven group is `su.onno` (open core, Apache-2.0);
commercial connectors are `su.onno.enterprise`. Desktop Gradle plugin id is `su.onno.desktop`.
Requires Java 21 and Spring Boot 3.4.x.

## Focused sibling skills

Use this skill for overview, routing, and cross-cutting work. For hands-on tasks, prefer the focused
skill that owns the surface:

| Task | Skill |
| --- | --- |
| Interviewing/modeling a business into concepts | `onno-modeling` |
| Running the discovery interview / deciding what to ask | `onno-modeling-interview` |
| Catalogs, enums, documents, registers | `onno-catalogs-enums`, `onno-documents-lines`, `onno-registers` |
| Rules, lifecycle defaults, schema migration | `onno-rules-lifecycle`, `onno-schema-migrations` |
| Layout/Page/EntityView/custom widgets/UI polish | `onno-ui` |
| Posting, rules, lifecycle hooks, register movements | `onno-posting` |
| Auth, REST/MCP/runtime verification, testing | `onno-auth-rbac`, `onno-runtime-api`, `onno-mcp`, `onno-testing-release` |
| Community integrations, starters, connectors, plugins | `onno-extensions`, `onno-connectors` |

## First principle: model the business before writing code

Do not start from tables or controllers. Ask what the company does, what it tracks, what documents
move work forward, what balances matter, and what rules must never be violated. Translate nouns and
verbs into framework concepts, then implement one vertical slice end to end and iterate. The full
interview script and per-domain question sets live in [AGENTS.md](https://github.com/onno-erp/onno-framework/blob/main/AGENTS.md) — follow it
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

A reference to another entity is `Ref<T>` (`su.onno.types.Ref`) — a typed `(Class<T>, UUID)`,
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
changes. Controlled by `onno.schema.mode` (`apply` default / `plan` / `validate` / `off`) and
`onno.schema.allow-destructive` (gates drops + narrowing changes).

- **Renames keep data**: declare the former name with `previousNames` on `@Catalog`/`@Document`/
  `@Attribute`, or the engine treats it as drop + add.
- **Data migrations** (backfills, seeding, reshaping) are `AppMigration` beans: a `version()`
  string compared segment-wise and `migrate(MigrationContext)`. Each runs once per database, in
  version order, inside a transaction, recorded in `onno_schema_history`.

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
- **`Page`** — a composed route (dashboards): `compose(PageBuilder)` with `widget`/`text`/`list(entity)`/
  `constants`/`custom`, plus `actions(heading, …)` for a row of server-handled buttons (same `ActionSpec`
  DSL as entity actions).
- **`EntityView`** — per-entity `list(ListSpec)` columns/filters and `fields(EntityConfigBuilder)`
  hints. **An entity surface is only served if it has an `EntityView` for the active profile** (no
  view → `404`). That is *necessary but not sufficient* for nav presence: a view makes the entity
  reachable by direct route, but it appears in the sidebar only once a `Layout` section lists it.
  Beyond columns and field hints, `EntityView` also drives: custom **detail-action placement**
  (`f.action("post").primary()` / `.inMenu()` / `.hidden()`), catalog-side **related lists**
  (`f.relatedList("doctors", ClinicDoctor.class).via("clinic").display("doctor")` — the catalog
  analogue of a document `@TabularSection`), **Ref-picker secondary lines + avatars**
  (`f.field("client").refSecondary("phone")`, `.widget("avatar")`), and a **list map view**
  (`spec.map().lat("latitude").lng("longitude")`). The full per-method list is in
  [reference/cheatsheet.md](reference/cheatsheet.md).

Do not put UI placement or field-display hints on domain annotations; author them with
`Layout`, `Page`, and `EntityView` beans. The widget DSL and `config(key,value)` reference are in
[onno-ui-starter/README.md](https://github.com/onno-erp/onno-framework/blob/main/onno-ui-starter/README.md).

## Make the first pass production-grade — don't ship the scaffold

A model that *compiles* is not a model that's *done*. The framework generates a working UI from bare
metadata, but the default output is generic — English chrome, blank New forms, raw field names, no
formatting. Close that gap in the same pass you model the business, not "later":

- **Localize to the business's language — never leave it half-English.** Decide the app's language
  from the user's prompt and domain terms, then carry it through *every* surface, because they
  localize independently:
  - entity `title=` and attribute `displayName=` → the domain nouns in that language;
  - enum values → `@Enumeration(title="…")` for the type label and `@EnumLabel("…")` on each constant
    (the constant stays an ASCII Java identifier; the label is what users see);
  - list-filter dropdowns → `filter(f).options(Map<value,label>)` (query matches the value, UI shows
    the label) — don't expose raw stored values; for assignees/people use
    `ListSpec.Option.withAvatar(value,label,url)` so the filter and action menus reuse profile photos;
  - field + **system-column** labels (`code`/`description`/`number`/`date`/`posted`) → field-hint
    `.label("…")` (the only path that relabels the built-in columns);
  - shell strings (buttons, login, tabs, toasts, empty states) → `onno.ui.messages.*` overrides in
    config. There is a full default key set; override the ones users read.

  Mixed-language UI ("Sign in" over a Russian catalog of "Контрагенты") is the #1 tell of an
  unfinished first pass.

- **Seed defaults so the New form opens populated, not blank.** Implement `OnFillingHandler.onFilling()`
  to pre-fill a new instance — status = the initial enum, `date`/`period` = now, sensible
  `quantity = 1`, a default counterparty or warehouse, etc. This runs on the generic create path, so
  the rendered New form shows those values. Use `@Constant` for global defaults and plain Java field
  initializers for fixed constants. A blank form the user must fill from scratch is a missed default.
  For `Ref` defaults and cross-navigation, the New form also prefills from query params
  (`…/new?room=<uuid>&startsAt=2026-07-16T19:00` — keys are write-path field names, `Ref`/enum
  values UUIDs, temporals ISO).

- **Author field hints — don't hand back an auto-dumped form.** In `fields(EntityConfigBuilder)`, set
  `.order()`/`.group()`/`.width()` for layout, `.widget()` (`switch`, `textarea`, `avatar`/`gallery`,
  `map`), `.format()` (`currency:EUR`, `dd-MM-yyyy`, `percent`), `.placeholder()`/`.hint()`, and
  `hideInList/Form/Detail()` to suppress noise. A money column rendered as a bare number, or a status
  shown as a dropdown of UUIDs, reads as unfinished.

- **Model the real nouns — never keep placeholder/scaffold names.** Name attributes after what the
  business actually tracks (`invoiceNumber`, `checkInDate`), not generic stand-ins (`name`, `field1`,
  or leftover demo fields copied from an example like a "subscription" sample). Delete any scaffolded
  sample entity you didn't deliberately model. Every attribute should map to a thing the user named in
  the interview; if you can't say which, it shouldn't be there.

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
media, import, MCP, etc.) are cataloged in [docs/ARCHITECTURE.md](https://github.com/onno-erp/onno-framework/blob/main/docs/ARCHITECTURE.md);
the JSON read contract (`{col}_display`/`{col}_ref`, `__SECRET_SET__` redaction, list vs get) is in
[docs/HEADLESS_READ_API.md](https://github.com/onno-erp/onno-framework/blob/main/docs/HEADLESS_READ_API.md).

## Three behaviours you will hit

- **Upserts are decided by `isNew`.** Base objects default `isNew = true`; the framework resets it
  to `false` after every load. So "load, mutate, save" UPDATEs. If you build an entity by hand
  (new instance, set a known id keyed on an external system) and want an UPDATE, call `setNew(false)`
  yourself, or the save INSERTs and you get a duplicate-key error.
- **Per-entity RBAC is deny-by-default.** A catalog/document/register is invisible and uneditable
  unless its `@AccessControl(readRoles=…, writeRoles=…)` grants the caller; the `ADMIN` role is a
  superuser. This applies to the REST API, the UI, and the MCP tools alike.
- **Deletion is soft — and the raw repository finders return the tombstones.** "Delete" sets
  `deletionMark = true` and the row stays in the table; the UI/REST read layer hides it, but
  `CatalogRepository`/`DocumentRepository` `findAll()`/`findById()`/`findByCode()`/`findByNumber()`
  **still return deletion-marked rows** (so `RefResolver` can resolve a `Ref<T>` to a deleted target,
  and restore/admin can reach them). **Business logic must never count a deleted row** — auth/login
  admission, posting, totals, validation, picker option lists. Use the soft-delete-aware finders —
  `findAllActive()`, `findActiveById(id)`, `findActiveByCode(code)`/`findActiveByNumber(number)`,
  `findActiveByDateBetween(...)` — or filter `!isDeletionMark()` yourself. Reaching for the bare
  `findAll()` in business code is the single most common way a "deleted" record silently keeps taking
  effect (e.g. a deleted employee still admitted to log in). A **boot-time guardrail**
  (`onno.repository.deletion-check` = `warn` default / `strict` / `off`) scans every catalog/document
  repository and flags any *consumer-declared* finder that returns entities without being
  deletion-scoped (e.g. a custom `findByExternalNumber`); a finder that genuinely must see tombstones
  declares `@IncludesDeleted` to opt out. So write a custom finder as
  `findByExternalNumberAndDeletionMarkFalse` (or annotate `@IncludesDeleted`), not bare `findByExternalNumber`.

## Modules & config

Core (`onno-framework` + `-starter`), plus opt-in starters: `onno-ui-starter`, `onno-auth-starter`,
`onno-mcp-starter`, `onno-import-starter`, `onno-cluster-starter` (cross-node live-UI sync for
horizontal scale-out), `onno-kafka-starter`, `onno-mail-starter`, `onno-print-starter`,
`onno-desktop-starter` (+ Gradle plugin). Each integration starter is gated by
`onno.<module>.enabled` (default on). The full module map is in
[docs/ARCHITECTURE.md](https://github.com/onno-erp/onno-framework/blob/main/docs/ARCHITECTURE.md); every `onno.*` property is in
[docs/CONFIGURATION.md](https://github.com/onno-erp/onno-framework/blob/main/docs/CONFIGURATION.md).

Minimal app config: point the scan at your packages (or rely on the `@SpringBootApplication`
package), give it a datasource, and seed a user. The core scan property is **`onno.scan-packages`**,
not `onno.base-packages` (that name only exists for mail/print template scanning).

```yaml
spring:
  datasource: { url: "jdbc:h2:file:./data/app", driver-class-name: org.h2.Driver, username: sa }
onno:
  auth:
    users: [{ username: admin, password: admin, roles: [ADMIN] }]
```

`onno.auth.mode` picks the auth model: **`IN_MEMORY`** (default — the `users` list above, session
cookie, JSON `/api/auth/login`), **`OIDC`** (server-side OpenID Connect auth-code login; provider
preset `onno.auth.oidc.provider` = `KEYCLOAK` / `ZITADEL` / `CUSTOM`, with token-claim → role mapping
under `onno.auth.oidc.roles.*`), or **`RESOURCE_SERVER`** (stateless bearer-token validation). The
SPA's `/api/auth/me` advertises the available login methods, so the login screen renders the right
password form and/or SSO buttons automatically. Full auth config is in
[onno-auth-starter/README.md](https://github.com/onno-erp/onno-framework/blob/main/onno-auth-starter/README.md)
and [docs/CONFIGURATION.md](https://github.com/onno-erp/onno-framework/blob/main/docs/CONFIGURATION.md).

## Building a commercial connector (onno-enterprise)

A connector is a Spring Boot **auto-configuration starter that wraps an external API** — it does
**not** define framework metadata; the host app does. The pattern and idioms (typed HTTP client with
token-refresh + backoff, `RefResolver` to map `Ref<T>` to external schemas, reacting to domain state
changes, stateful audit ledgers, one `onno.<connector>.enabled` switch) are in
[reference/connectors.md](reference/connectors.md).

## Working agreement

Standing rules — follow them without being re-told:

- **Edit in a worktree, never the main checkout**; finish with a PR (the default flow here is
  PR-and-merge once checks pass).
- **Run things yourself** — start the app, hit the endpoint, take the screenshot, run the migration.
  Don't hand the user commands to run or ask permission for routine, reversible steps.
- **Place work in the right repo**: `onno-framework` = open-core primitives; `onno-enterprise` =
  commercial connectors and paid login providers; `onno-cloud` = control plane
  (tenants/licensing/registry/billing). Building an app on the framework? Framework bugs get filed
  as issues (below), not patched in-app.
- **Before debugging a "known" symptom, read
  [docs/GOTCHAS.md](https://github.com/onno-erp/onno-framework/blob/main/docs/GOTCHAS.md)** — the
  documented traps (auth public-paths, SSE named events, wire-contract casing, SPA fallback…) cover
  most first-session failures. Run/verify recipes:
  [docs/RUNNING.md](https://github.com/onno-erp/onno-framework/blob/main/docs/RUNNING.md);
  consuming/upgrading released artifacts:
  [docs/CONSUMING.md](https://github.com/onno-erp/onno-framework/blob/main/docs/CONSUMING.md); SPA
  design system: `onno-ui-starter/src/main/frontend/DESIGN.md`.
- **Iterate on UI with dev mode** (devtools + `./gradlew -t <app>:classes`, `touch .onno-reload`),
  not full rebuilds.

## Verify your work

```bash
./gradlew clean check                         # compile + test everything incl. example + frontend
./gradlew publishToMavenLocal                 # verify consumable artifacts (sources/javadoc/POM)
./gradlew :onno-framework:test                # narrow iteration
./gradlew :onno-ui-starter:buildFrontend      # frontend-only changes
```

`publishToMavenLocal` matters: a build can pass with project deps but still fail producing sources,
javadocs, or POM metadata.

## Report framework bugs

When you build an app *on* the framework, separate a **framework bug** from your own modeling
mistake. A framework bug is reproducible behaviour that contradicts these docs or the framework's
contract — a generated endpoint returns the wrong shape, a valid annotation is ignored, posting
corrupts a register, a migration drops data it shouldn't, an `onno.*` property has no effect. A
modeling mistake is yours to fix (missing `EntityView`, wrong `context`, save+post in one
transaction).

When you hit a genuine framework bug, don't silently work around it — **surface it and file an
issue** so it gets fixed upstream:

- Core framework → [`onno-erp/onno-framework`](https://github.com/onno-erp/onno-framework/issues)
- Commercial connectors (Guesty / SES.HOSPEDAJES / Tochka) → the `onno-erp/onno-enterprise` repo.

Reduce it to the smallest repro first, **check for duplicates**, and — because filing is a public,
outward action — **confirm with the user before creating it** (or hand them the command if you
can't). Then:

```bash
gh issue list --repo onno-erp/onno-framework --search "<keywords>"     # dedupe first
gh issue create --repo onno-erp/onno-framework --title "<concise symptom>" --body "$(cat <<'EOF'
**Version:** su.onno:onno-framework-starter:<version>
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
| a `@ConfigurationProperties` field (any `onno.*`) | `docs/CONFIGURATION.md` + the owning module README |
| a REST endpoint or its contract | `docs/ARCHITECTURE.md` (endpoint table), `docs/HEADLESS_READ_API.md`, the module README |
| the module set / open-core boundary | `README.md`, `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/licensing/MODULE-SPLIT-PLAN.md` |
| auth modes / endpoints | `docs/ARCHITECTURE.md`, `docs/CONFIGURATION.md`, `onno-auth-starter/README.md`, `onno-ui-starter/README.md` |
| a documented gotcha's behaviour | `docs/GOTCHAS.md` (and the skill that repeats it) |
| run/dev-mode/verify mechanics | `docs/RUNNING.md` |
| publishing, registry, licensing of artifacts | `docs/CONSUMING.md` |
| a reusable SPA component, token, or UI convention | `onno-ui-starter/src/main/frontend/DESIGN.md` |
| a shipped roadmap item | move it from "next work" to "current state" in `ROADMAP.md` |

Verify a doc claim against the code before repeating it — several historical docs referenced a
non-existent `/api/ui/metadata/manifest` and an `onno.base-packages` property. When a doc and the
code disagree, the code wins; fix the doc.
