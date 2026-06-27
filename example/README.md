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
| Comments | a per-order discussion thread for the team. |

## Two roles, two UI profiles

`admin`/`admin` resolves to the **admin profile** (`AdminLayout`): home is the dashboard, and the
People section includes Employees. `manager`/`manager` resolves to the **default profile**
(`MainLayout`): home is the Orders list, no Employees nav. The split is enforced by per-entity RBAC
(`@AccessControl` — `Employee` writes are ADMIN-only) and by role-scoped UI profiles (onno has no
per-page RBAC, so the dashboard simply doesn't exist in the manager profile). Change these passwords
before any real use.

## Running it

Requires **JDK 21**.

```bash
./gradlew :example:bootRun                       # http://localhost:8080
./gradlew :example:bootRun --args='--server.port=8090'   # or pick another port
```

On first launch `BookstoreSeeder` fills a fresh database with categories, suppliers, customers,
staff, a dozen books, an opening stock receipt, and a spread of orders across the lifecycle. Delete
`example/data/` to re-seed.
