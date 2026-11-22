---
name: onno
description: >-
  Expert playbook for the onno-framework — the Java/Spring "model-the-business" ERP toolkit
  (catalogs, documents, tabular sections, accumulation/information registers, enumerations,
  constants, typed process definitions, posting, schema migration, generic REST API, the React
  UI package @onno/ui, commands, MCP, auth/RBAC). Use when working in the onno-framework or
  onno-enterprise repos, or building/modifying an ERP app that consumes su.onno:onno-* (su.onno.*
  packages) — i.e. anything touching @Catalog, @Document, @AccumulationRegister,
  @InformationRegister, @TabularSection, Postable/handlePosting, typed Ref references, Commands/
  Checks/PickerOptions beans, an app's src/main/ui (defineApp, entity customizations, pages), or
  onno.* config. Covers modeling a business into framework concepts,
  posting/validation rules, diff-based schema migrations, the runtime API + auth recipe, the module
  map, and the rule to keep docs in sync with code.
---

# onno-framework

`onno-framework` models a business as **typed Java metadata** and generates the rest: database
schema, repositories, a generic REST API, a default React UI for every entity (`@onno/ui`), an
MCP tool surface for AI agents, and migration history. You never hand-write tables, DTOs, or CRUD
controllers. Behaviour that *is* code — posting, validation, lifecycle hooks, commands — is
plain, compiler-checked Java; presentation is typed React in the app's `src/main/ui`, never
string-mapped config.

Java packages are `su.onno.*`. Maven group is `su.onno` (open core, Apache-2.0);
commercial connectors are `su.onno.enterprise`. UI Gradle plugin id is `su.onno.ui`.
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
| The app's UI: `src/main/ui`, `defineApp`, typed model, dev loop, localization, polish | `onno-ui` |
| How an entity's list and form present (columns, filters, fields, formats, command placement) | `onno-ui-entities` |
| Pages, navigation, widgets, data hooks, `onno://` links | `onno-ui-pages` |
| Commands (buttons, bulk actions, MCP tools), live checks, picker options, `IdentityLink` | `onno-commands` |
| Posting, rules, lifecycle hooks, register movements | `onno-posting` |
| Auth, REST/MCP/runtime verification, testing | `onno-auth-rbac`, `onno-runtime-api`, `onno-mcp`, `onno-testing-release` |
| Driving a running app to verify a change (feature map, `onno-drive` CLI, PR verification artifacts) | `onno-verify` |
| Community integrations, starters, connectors, plugins | `onno-extensions`, `onno-connectors` |

## First principle: model the business before writing code

Do not start from tables or controllers. Ask what the company does, what it tracks, what documents
move work forward, what balances matter, and what rules must never be violated. Translate nouns and
verbs into framework concepts, then implement one vertical slice end to end and iterate. The full
interview script and per-domain question sets live in [AGENTS.md](https://github.com/onno-erp/onno-framework/blob/main/AGENTS.md) — follow it
when a user asks you to model a business.

## Reuse the official CRM module

For unified messaging, use `su.onno:onno-crm-starter` with explicit `CrmCustomerBinding` and
optional `CrmAgentBinding` beans pointing to existing host catalogs. Do not create duplicate CRM
customers or require inheritance from a CRM model. Customer fields, stages, forms, sales pipelines
and metrics are ordinary host onno models.
The module supplies conversations, channel identities, delivery and `@onno/crm` screens; the host supplies
workspace roles, permissions, pages, navigation and an optional inbound-contact resolution policy.
Read the CRM module README for current binding and command contracts.

## Concept → annotation cheat sheet

| If it is… | Use | Base class |
| --- | --- | --- |
| a stable list users choose from (Products, Customers, Warehouses) | `@Catalog` | `CatalogObject` |
| a fixed list controlled by code (OrderStatus) | `@Enumeration` (on a Java `enum`) | — |
| a business event with a date + number (Invoice, Shipment) | `@Document` | `DocumentObject` |
| a repeated line inside a document (invoice lines) | `@TabularSection` field | row `extends TabularSectionRow` |
| a current balance (Stock, Cash) | `@AccumulationRegister(type = BALANCE)` | `AccumulationRecord` |
| period activity totals (Revenue, Hours) | `@AccumulationRegister(type = TURNOVER)` | `AccumulationRecord` |
| historical facts by dimension over time (Prices by date) | `@InformationRegister` | `InformationRecord` |
| one global setting (CompanyName) | `@Constant` | plain class with one field |
| scheduled/async work | `@ScheduledJob(cron=…)` / `@Scheduled` | — |
| durable multi-step coordination with typed human-task outcomes | `ProcessDefinition<P,S>` + `HumanTask<P,O>` | — |
| something another team/service owns | `context = "…"` on the annotation | — |

A reference to another entity is `Ref<T>` (`su.onno.types.Ref`) — a typed `(Class<T>, UUID)`,
stored as a UUID column, resolved with `RefResolver`. Use it instead of embedding whole objects.
When one field legitimately targets several entity types, use `PolyRef` with an explicit
`@RefTargets({BankAccount.class, CashAccount.class, Payment.class})` allowlist. Do not replace a
single-target `Ref<T>` with `PolyRef`.
`BALANCE` registers reject negative resource totals by default; declare `allowNegative = true` only
for a balance whose domain permits debt or overdrafts. Set `postingOrder = CHRONOLOGICAL` only on
registers whose later documents must be restored after a backdated post/unpost (for example
moving-average inventory cost).

The exact attributes and defaults of every annotation, the fields each base class carries, and all
lifecycle interfaces are in [reference/cheatsheet.md](reference/cheatsheet.md). Read it before
writing model classes — guessing an attribute name or default wastes a build cycle.

The `su.onno.process` API uses enum step keys and outcomes, typed node handles, exhaustive route
validation, stable versioned definitions/step keys, and explicit typed migrations. Core persists
token-based instances, timers, nested parallel fork/join, subprocess links, transitions, and
candidate/assigned work items; the UI starter exposes inspectable definitions/instances plus the
authenticated inbox, claim, delegate, complete, cancel, and migrate loop.

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

```

```ts
// 4. src/main/ui/entities/SalesOrders.ts — optional: every readable entity already has a list and
//    form; customize only what differs (registered in main.tsx: entities: { "Sales Orders": salesOrders }).
import type { EntityCustomization } from "@onno/ui";

export const salesOrders = {
  list: { columns: ["number", "date", "customer", "total"], sort: "-date" },  // which columns, in order
  fields: { total: { format: { money: "USD" }, hideInForm: true } },          // how fields look
} satisfies EntityCustomization<"Sales Orders">;
```

Implement in this order: catalogs + enums → documents + tabular sections → registers + posting →
constants/jobs/integrations → commands (`Commands<E>`) → UI (`src/main/ui`: entity
customizations, pages, navigation) → tests. Then verify against a running app (see below).
Summarize what you modeled and your assumptions.

## Posting & rules — the two gotchas that bite everyone

- **Save the document and let it commit, then post.** The posting engine claims an existing unposted
  row before movements persist; an uncommitted/missing document is rejected. Do not rely on an
  ambient Spring `@Transactional` save+post boundary because posting owns its JDBI transaction.
- **Give every tabular section its own concrete row class.** Share common behavior through a base
  row class; never reuse one concrete `TabularSectionRow` in multiple sections.
- **Core posting an already-posted document is rejected before movements are persisted.** Use the
  atomic `repost(...)` operation for an intentional recalculation; retrying `post(...)` cannot
  double-count totals. Unpost rejects drafts and deleted rows.
- **React to a post with a Spring `@EventListener` on `DocumentPostedEvent`** (full DI), not from
  inside `handlePosting` (normal posting stages movements before transactional persistence, and
  preview/restoration may replay it). The
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

## UI — defaults from the model, React for everything else

There is no Java UI DSL. With zero UI code an app already gets navigation (entities grouped by their
annotation `context`), a home page, and a list + record form for every entity the user can read —
RBAC (`RoleGrants`, record policies) decides what exists for whom. The server describes data
and permissions, never screens. Write UI only for what differs. The UI is the npm package
`@onno/ui`; an app's UI is an ordinary Vite app in `src/main/ui` that imports it:

- **Set up:** apply `id("su.onno.ui")`, run `./gradlew onnoUiInit` (seeds `package.json`,
  `vite.config.ts` = `plugins: [onno()]`, `main.tsx`, `pages/LoginPage.tsx`, `pages/HomePage.tsx`, …;
  the default login and home are wired explicitly so you extend them by editing those files). The build generates
  `model.gen.ts` from the Java entities (`generateUiModel`) and runs `vite build` into the jar
  (`buildUi`); `npm run dev` in `src/main/ui` hot-reloads against the running backend.
- **`main.tsx`** is one `defineApp({ entities, pages, navigation, extensions })` call. How an
  entity presents is one `EntityCustomization<E>` per entity in `entities/` (columns via
  `list.columns`, form fields via `form.fields`, presentation in `fields`). Entity screens with a
  different layout are TSX pages registered as `{ list: "Orders", component: OrdersPage }` /
  `{ record: "Orders", component: OrderPage }` (`ListPageProps<E>` / `RecordPageProps<E>`);
  ordinary pages are `{ path, component, label, icon, roles, when }`;
  `navigation: [{ title, icon, items: ["Orders", "/ops"] }]`. Icons are imported Lucide components.
- **Everything is typed from the model:** entity names, fields (`"items.unitPrice"`), records
  (`RecordOf<"Orders">`), enum constants (`OrderStatus.NEW`), roles. Formats, inputs, widths, icons
  and `where` predicates are typed values (`{ money: "USD" }`, `"textarea"`, `{ posted: true }`),
  never strings the UI parses.
- **Pages** compose `Page`, `Row`, `EntityList` and widgets (`Stat`, `Chart`, `TimeRange`,
  `Calendar`, `Kanban`, `RecentRecords`, `Setting`, `TaskInbox`) over typed measures
  (`count("Orders", { open: true })`, `sum("Orders", "total", { posted: true })`); components read
  data with live hooks (`useRecords`, `useBalance`, `useCommand`, …) and open things with
  `onno://` links (`openRoute(links.record("Orders", id))`).
- **Feature components:** `EntityList` takes `calendar`/`timeline` props for date-ranged records
  (drag saves as an ordinary record update); record pages show `<RecordHistory />` for entities with
  `history = true`; `<PeriodClose />` is the period-close admin screen; `TimePicker`/`InstantPicker`
  edit `LocalTime`/`Instant` (instants display in `onno.ui.time-zone`).
- **Behaviour stays in Java** and works on every surface (UI, REST, MCP): `Commands<E>`
  (`su.onno.command`), `Checks<E>` (`su.onno.validation`), `PickerOptions<E>` (`su.onno.picker`),
  `IdentityLink` (`su.onno.identity`). The entity's `commands` customization only decides how a
  command is offered (menu, row button, primary button, icon).

Skills: `onno-ui` (overview, set-up, dev loop, localization), `onno-ui-entities`, `onno-ui-pages`,
`onno-commands`. Reference app: `example/src/main/ui` and `example/src/main/java/com/example/`.

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
  - list filters → the entity customization's `list.filters` (`{ field, label, options:
    [{ value, label }] }` for fixed choices, `optionsFrom: "Employees"` for a catalog's records —
    enum choices and labels come from the model); don't expose raw stored values;
  - field + **system-column** labels (`code`/`description`/`number`/`date`/`posted`) → `fields:
    { number: { label: "№" } }` in the customization (the only path that relabels the built-in
    columns), `list.labels` for list-only headers;
  - page titles, nav section titles, command titles (Java `title(...)`) in the language too;
  - chrome strings (buttons, login, tabs, toasts, empty states) → `onno.ui.locale` (built-in
    bundle) and `onno.ui.messages.<key>` overrides in config.

  Mixed-language UI ("Sign in" over a Russian catalog of "Контрагенты") is the #1 tell of an
  unfinished first pass.

- **Seed defaults so the New form opens populated, not blank.** Use Java field initializers for
  values that must appear on the initially rendered blank form. Use null-guarded
  `OnFillingHandler.onFilling()` for create/save defaults and normalization — it runs on write paths,
  not on the first blank-form render. Use `@Constant` for global defaults. A blank form the user must
  fill from scratch is a missed default.
  For `Ref` defaults and cross-navigation, open a prefilled New form:
  `openRecord("Reservations", "new", { room: roomId, startsAt: "2026-07-16T19:00" })` or
  `links.newRecord(...)` (keys are field names, `Ref`/enum values UUIDs, temporals ISO).

- **Customize fields — don't hand back an auto-dumped form.** In the entity's `fields`
  customization set `order`/`group`/`width: "half"` for layout, `input` (`"switch"`, `"textarea"`,
  `"image"`, `"avatar"`, `"color"`, `"map"`), `format` (`{ money: "EUR" }`, `{ percent: { decimals:
  1 } }`, `{ date: "medium" }`), `placeholder`/`hint`, and `hidden`/`hideInList`/`hideInForm` to
  suppress noise. A money column rendered as a bare number reads as unfinished.

- **Model the real nouns — never keep placeholder/scaffold names.** Name attributes after what the
  business actually tracks (`invoiceNumber`, `checkInDate`), not generic stand-ins (`name`, `field1`,
  or leftover demo fields copied from an example like a "subscription" sample). Delete any scaffolded
  sample entity you didn't deliberately model. Every attribute should map to a thing the user named in
  the interview; if you can't say which, it shouldn't be there.

## Inspecting a running app

Most `/api/**` routes are authenticated; the documented bootstrap allowlist (theme/config/branding,
login/me/CSRF and the login screen by default) is public. Cookie-mode mutations are
**CSRF-protected**; resource-server bearer mode disables CSRF. Two traps:

1. **There is no anonymous manifest endpoint.** `/api/ui/metadata/manifest` does not exist. Introspect the model via
   the real generated endpoints or the MCP `describe_metadata` tool.
2. **The SPA fallback is navigation-only.** Unknown `/api/**` routes and missing assets return
   `404`; only HTML `GET` navigation under `onno.ui.path` falls back to `index.html`.

`{name}` is the annotation's logical `name` (`Books`, `SalesOrders`), not the Java class name or
localized `title`. Collection reads use `/api/list/catalogs|documents/{name}`. Login
is a JSON POST that sets a session cookie; mutations need the CSRF token from the `XSRF-TOKEN`
cookie echoed in `X-XSRF-TOKEN`:

```bash
curl -c jar.txt http://localhost:8080/api/config                       # get XSRF-TOKEN cookie
curl -b jar.txt -c jar.txt -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin@onnobooks.local","password":"admin"}'
curl -b jar.txt 'http://localhost:8080/api/list/catalogs/Books?limit=50'      # reads need the session
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)                        # mutations need the token
curl -b jar.txt -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d '{ …entity JSON… }' http://localhost:8080/api/catalogs/Books
```

The real endpoints (auth `/api/auth/login|logout|me`, catalogs/documents/registers, posting,
media, import, MCP, etc.) are cataloged in [docs/ARCHITECTURE.md](https://github.com/onno-erp/onno-framework/blob/main/docs/ARCHITECTURE.md);
the JSON read contract (logical `FieldDisplay`/`FieldRef` companions, storage compatibility,
`__SECRET_SET__` redaction, list vs get) is in
[docs/HEADLESS_READ_API.md](https://github.com/onno-erp/onno-framework/blob/main/docs/HEADLESS_READ_API.md).

## Three behaviours you will hit

- **Upserts are decided by `isNew`.** Base objects default `isNew = true`; the framework resets it
  to `false` after every load. So "load, mutate, save" UPDATEs. If you build an entity by hand
  (new instance, set a known id keyed on an external system) and want an UPDATE, call `setNew(false)`
  yourself, or the save INSERTs and you get a duplicate-key error.
- **Access is one `RoleGrants` bean.** Once declared, a catalog/document/register is invisible and uneditable
  unless a role the caller holds is granted it (`read`/`write`/`post`/`unpost`); there is no
  superuser, and without the bean the app runs open. This applies to the REST API, the UI, and the MCP tools alike.
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

Core (`onno-framework` + `-starter`, which also carries the cross-node live-UI bus for
horizontal scale-out), plus opt-in starters: `onno-web-starter`, `onno-auth-starter`,
`onno-mcp-starter`, `onno-import-starter`, `onno-print-starter` (Word/Excel print forms via
`PrintForm<T>` beans), `onno-ui-gradle-plugin`, and the npm packages `@onno/ui`, `@onno/crm`, `@onno/print`.
Optional starters use their documented `onno.<module>.enabled` switches where provided. The full
module map is in
[docs/ARCHITECTURE.md](https://github.com/onno-erp/onno-framework/blob/main/docs/ARCHITECTURE.md); every `onno.*` property is in
[docs/CONFIGURATION.md](https://github.com/onno-erp/onno-framework/blob/main/docs/CONFIGURATION.md).

Minimal app config: point the scan at your packages (or rely on the `@SpringBootApplication`
package), give it a datasource, and seed a user. The core scan property is
**`onno.scan-packages`**, not `onno.base-packages`.

```yaml
spring:
  datasource: { url: "jdbc:h2:file:./data/app", driver-class-name: org.h2.Driver, username: sa }
onno:
  auth:
    mode: database   # accounts in the application DB, managed at runtime
    users: [{ username: admin, password: change-me-on-first-login, roles: [ADMIN] }]  # first-boot seed only
```

`onno.auth.mode` picks the auth model: **`DATABASE`** (recommended — accounts in the table
`onno_auth_user`, managed at runtime through `/api/auth/users` and the `<UserAccounts />` /
`<ChangePassword />` components; the `users` list only seeds an empty store once,
and seeded accounts must change their password at first sign-in), **`IN_MEMORY`** (default — the
`users` list is the account set for as long as the config file says so; development and demos only),
**`OIDC`** (server-side OpenID Connect auth-code login; provider
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
  design system: `onno-ui/DESIGN.md`.
- **Use Vite as the intended edit loop.** In this repository, `npm run dev` at the root serves
  the frontend on 5173 against the existing backend on 8091. Add `npm run dev:backend` and
  `npm run dev:watch` in separate terminals for application Java/model edits. Do not rebuild jars
  for UI-only changes. Standalone consumer apps run their own `npm run dev` in `src/main/ui` and
  regenerate their model with `./gradlew generateUiModel`. See
  [Running & verifying](../../../docs/RUNNING.md#intended-development-workflow).

## Verify your work

```bash
./gradlew clean check                         # compile + test everything incl. example + frontend
./gradlew publishToMavenLocal                 # verify consumable artifacts (sources/javadoc/POM)
./gradlew :onno-framework:test                # narrow iteration
./gradlew :onno-web-starter:testFrontend       # frontend-only: typecheckFrontend, then Vitest
./gradlew :onno-web-starter:buildFrontend      # frontend-only changes
```

`publishToMavenLocal` matters: a build can pass with project deps but still fail producing sources,
javadocs, or POM metadata.

## Report framework bugs

When you build an app *on* the framework, separate a **framework bug** from your own modeling
mistake. A framework bug is reproducible behaviour that contradicts these docs or the framework's
contract — a generated endpoint returns the wrong shape, a valid annotation is ignored, posting
corrupts a register, a migration drops data it shouldn't, an `onno.*` property has no effect. A
modeling mistake is yours to fix (missing `RoleGrants` grant, wrong `context`, save+post in one
transaction).

When you hit a genuine framework bug, don't silently work around it — **surface it and file an
issue** so it gets fixed upstream:

- Core framework → [`onno-erp/onno-framework`](https://github.com/onno-erp/onno-framework/issues)
- Commercial connectors (Tochka / YooKassa / Ozon / …) → the `onno-erp/onno-enterprise` repo.

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
| auth modes / endpoints | `docs/ARCHITECTURE.md`, `docs/CONFIGURATION.md`, `onno-auth-starter/README.md`, `onno-web-starter/README.md` |
| a documented gotcha's behaviour | `docs/GOTCHAS.md` (and the skill that repeats it) |
| run/dev-mode/verify mechanics | `docs/RUNNING.md` |
| publishing, registry, licensing of artifacts | `docs/CONSUMING.md` |
| a reusable SPA component, token, or UI convention | `onno-ui/DESIGN.md` |
| a shipped roadmap item | move it from "next work" to "current state" in `ROADMAP.md` |

Verify a doc claim against the code before repeating it — several historical docs referenced a
non-existent `/api/ui/metadata/manifest` and an `onno.base-packages` property. When a doc and the
code disagree, the code wins; fix the doc.
