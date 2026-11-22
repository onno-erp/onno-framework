# Onno Books — the onno-framework example app

A small **bookstore back-office**, and the reference application for
[onno-framework](../README.md). The business is declared as typed Java metadata under
`com.example`; the framework generates the schema, REST API, and a default React admin UI (a list
and form for every readable entity, navigation grouped by bounded context) from it. Nothing here hand-writes tables, DTOs, or CRUD controllers.

It is deliberately **small and end-to-end** — one coherent domain, the core starters
(framework + web + auth, plus CRM and print), every feature wired so it actually works rather than half-scaffolded.

## What's modeled

| Area | Concept |
| --- | --- |
| **Orders** | `Order` document — a customer, lines (`OrderLine`: book × qty × price), a status, and an assignee. Posting draws stock and records the sale. |
| Order lifecycle | `OrderStatus` enum: `NEW → CONFIRMED → SHIPPED → COMPLETED`, plus `CANCELLED`. Rendered as **colored status pills** (`@EnumLabel(color = …)`); row actions advance or cancel an order. |
| Catalog (master data) | `Book` (title, author, ISBN, category, supplier, price, cover image), `BookCategory`. |
| Inventory | `StockReceipt` document → `BookStock` (BALANCE register). Receiving raises stock; an order lowers it. Because the register is `BALANCE`, the engine **refuses to post an oversold order**. |
| Reports | `BookSales` (TURNOVER register) — units and revenue by book and seller, written when an order is posted. |
| People | `Customer`, `Supplier`, `Employee` (`Position` enum). Employees double as the login identity directory. |
| Settings | one `@Constant` (store name), editable on the admin-only Settings page (`/settings`). |
| Dashboard | admin-only home page — KPI tiles, an orders-by-status pie, revenue over time, recent orders. |
| Comments | a per-order message thread with replies, reactions, `@` mentions, and `#` document references. |

## The UI: React for screens, Java for behaviour

| Where | What |
| --- | --- |
| `src/main/ui/main.tsx` | The UI entry point: one `defineApp({ entities, pages, navigation })` call. |
| `src/main/ui/pages/` | One component per page: the dashboard at `/`, Sales Ops (`/ops`), the Book Sales report (`/registers/book_sales`), Suppliers (`/catalogs/suppliers`), My tasks, Team, Settings, the Support inbox, Pipeline. |
| `src/main/ui/entities/` | One `EntityCustomization` per entity (`orders.ts`, `books.ts`, …): list columns, grouping, subtotals, filters, the Customers map, the Books shelf view, the Orders row tint, form field layout and inputs, picker cascades, command placement, the conversations panel under a customer. |
| `src/main/ui/components/` | The app's own components: `OrdersBoard`, `OrderActivity` (data through `useRecords`), `BookTiles` (the Books "Shelf" view), `CustomerConversations`, `SelectedOrders`. |
| `src/main/ui/lib/` | Shared measures (`count`/`sum` over orders and sales) and module checks (CRM, sales). |
| `com.example.commands.OrderCommands` | `Commands<Order>`: advance/cancel, "Change status" and "Assign" groups, request approval, input-form and feedback demos — on the UI, REST and MCP alike. |
| `com.example.checks.ScheduleEventChecks` | `Checks<ScheduleEvent>`: the live schedule-conflict check on the event form. |
| `com.example.pickers.EmployeeAvailability` | `PickerOptions<ScheduleEvent>`: badges each participant available/unavailable for the chosen time. |
| `com.example.IdentityConfiguration` | `IdentityLink`: a login is linked to its `Employee` row by email. |
| `com.example.print` | Print forms: a Word invoice and an Excel pick list on the Orders record menu. |
| `application.yaml` → `onno.ui.branding` | App name, logos, favicon and primary colours. |

The UI is an ordinary Vite app in `src/main/ui` on `@onno/ui`. The `su.onno.ui` Gradle plugin
generates `model.gen.ts` from the Java model and builds the app into the jar (`./gradlew
:example:buildUi`); the server serves it in place of the default shell. A new project gets the same
layout from `./gradlew onnoUiInit`. For hot reload, run the backend and `npm run dev` in
`src/main/ui` (after one `npm install` at the repository root).

## Two roles

The demo automatically signs anonymous visitors in as `manager@onnobooks.local`: home is the Orders
board and there is no Employees or Settings nav. The password never needs to reach the browser for
this flow. `admin@onnobooks.local`/`admin` gets the dashboard as home, Employees under People, and
Settings. The UI decides what to show with page `roles`/`permission` and `useHasRole("ADMIN")`; the
server enforces access on every call (the `RoleGrants` bean in `com.example.access.AccessConfiguration`
— `Employee` writes are ADMIN-only, and the settings API needs `settings.edit`, which only ADMIN is
granted; `ADMIN` is an ordinary app role here, not a superuser). Remove `onno.auth.demo.auto-login-username` before any non-demo use.

## Running it

Requires **JDK 21**.

```bash
./gradlew :example:bootRun                       # http://localhost:8080
./gradlew :example:bootRun --args='--server.port=8090'   # or pick another port
```

The example temporarily permits every iframe parent (`frame-ancestors *`). To narrow that policy for
the hosted HTTPS demo, set an exact parent origin and enable cross-site secure cookies:

```bash
ONNO_DEMO_FRAME_ANCESTORS=https://www.example.com \
ONNO_DEMO_COOKIE_SAME_SITE=none \
ONNO_DEMO_COOKIE_SECURE=true \
./gradlew :example:bootRun
```

Then use `<iframe src="https://demo.example.com" title="Onno Books demo"></iframe>`. Prefer keeping
the landing page and demo under the same registrable domain; strict third-party-cookie blocking can
otherwise prevent session-backed mutations.

On first launch `BookstoreSeeder` fills a fresh database with a populated shop — 12 categories, 8
suppliers and staff, ~36 customers, ~80 books, a generous opening stock receipt, and ~80 orders
spread over the last quarter across the whole lifecycle. It's generated from a fixed RNG seed, so the
dataset is identical every time. Delete `example/data/` to re-seed.
