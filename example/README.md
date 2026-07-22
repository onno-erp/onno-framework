# Onno Books — the onno-framework example app

A small **bookstore back-office**, and the reference application for
[onno-framework](../README.md). The business is declared as typed Java metadata under
`com.example`; the framework generates the schema, REST API, and the server-driven (DivKit) admin UI
from it. Nothing here hand-writes tables, DTOs, or CRUD controllers.

It is deliberately **small and end-to-end** — one coherent domain, the core starters only
(framework + UI + auth), every feature wired so it actually works rather than half-scaffolded.

## What's modeled

| Area | Concept |
| --- | --- |
| **Orders** | `Order` document — a customer, lines (`OrderLine`: book × qty × price), a status, and an assignee. Posting draws stock and records the sale. |
| Order lifecycle | `OrderStatus` enum: `NEW → CONFIRMED → SHIPPED → COMPLETED`, plus `CANCELLED`. Rendered as **colored status pills** (`@EnumLabel(color = …)`); row actions advance or cancel an order. |
| Catalog (master data) | `Book` (title, author, ISBN, category, supplier, price, cover image), `BookCategory`. |
| Inventory | `StockReceipt` document → `BookStock` (BALANCE register). Receiving raises stock; an order lowers it. Because the register is `BALANCE`, the engine **refuses to post an oversold order**. |
| Reports | `BookSales` (TURNOVER register) — units and revenue by book and seller, written when an order is posted. |
| People | `Customer`, `Supplier`, `Employee` (`Position` enum). Employees double as the login identity directory. |
| Settings | one `@Constant` (store name), editable on the built-in Settings page. |
| Dashboard | admin-only home page — KPI tiles, an orders-by-status pie, revenue over time, recent orders. |
| Comments | a per-order message thread with replies, reactions, `@` mentions, and `#` document references. |

## Two roles, two UI profiles

The demo automatically signs anonymous visitors in as `manager@onnobooks.local`, which resolves to
the **default profile** (`MainLayout`): home is the Orders list, no Employees nav. The password never
needs to reach the browser for this flow. `admin@onnobooks.local`/`admin` resolves to the **admin
profile** (`AdminLayout`): home is the dashboard, and the People section includes Employees. The split
is enforced by per-entity RBAC (`@AccessControl` — `Employee` writes are ADMIN-only) and by
role-scoped UI profiles (onno has no per-page RBAC, so the dashboard simply doesn't exist in the
manager profile). Remove `onno.auth.demo.auto-login-username` before any non-demo use.

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
