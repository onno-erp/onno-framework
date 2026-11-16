# Page examples

From the reference app, `example/src/main/ui/` (`pages/`, `components/`, `lib/`).

- main.tsx: pages and navigation
- Home: by role
- Dashboard: measures, stats, charts
- A list beside its numbers: controlled EntityList
- Team: calendar, header action, openRecord
- Replacing an entity screen
- Settings: constants and the print starter
- A route with params: the CRM conversation
- Kanban board
- Your own data component
- Running a command from a component
- Registers and constants in code
- Links

## main.tsx: pages and navigation

```tsx
import { defineApp } from "@onno/ui";
import { Activity, ListChecks, Users, Settings, Inbox, Plug, ShoppingCart, Book, Package, ChartColumn, MessagesSquare } from "@onno/ui/icons";
import { entities } from "./entities";
import { crmInboxAvailable } from "./lib/crm";
import { HomePage } from "./pages/HomePage";
import { SalesOpsPage } from "./pages/SalesOpsPage";
import { SalesReportPage } from "./pages/SalesReportPage";
import { SettingsPage } from "./pages/SettingsPage";
import { ConnectionsPage, ConversationPage, SupportPage } from "./pages/SupportPage";
import { StockReceiptPage } from "./pages/StockReceiptPage";
import { SuppliersPage } from "./pages/SuppliersPage";
import { TasksPage } from "./pages/TasksPage";
import { TeamPage } from "./pages/TeamPage";

defineApp({
  entities, // one EntityCustomization per entity, in entities/
  pages: [
    { path: "/", component: HomePage },
    { path: "/ops", component: SalesOpsPage, label: "Sales Ops", icon: Activity },
    { path: "/tasks", component: TasksPage, label: "My tasks", icon: ListChecks },
    { path: "/team", component: TeamPage, label: "Team", icon: Users },
    { path: "/settings", component: SettingsPage, label: "Settings", icon: Settings, roles: ["ADMIN"] },
    // Optional modules: the page and its link exist only when the module does.
    { path: "/support", component: SupportPage, label: "Inbox", icon: Inbox, when: crmInboxAvailable },
    { path: "/support/connections", component: ConnectionsPage, label: "Connections", icon: Plug, roles: ["ADMIN"], when: crmInboxAvailable },
    { path: "/catalogs/crm_conversations/:id", component: ConversationPage, view: false, when: crmInboxAvailable },
    // Pages that replace an entity's default screen (only where the layout differs).
    { list: "Suppliers", component: SuppliersPage },
    { record: "Stock Receipts", component: StockReceiptPage },
    { path: "/registers/book_sales", component: SalesReportPage, view: false },
  ],
  // Entities the user cannot read, pages they cannot use and sections left empty are dropped.
  navigation: [
    { title: "Sales", icon: ShoppingCart, items: ["Orders", "Customers", "/tasks"] },
    { title: "Catalog", icon: Book, items: ["Books", "Categories"] },
    { title: "Inventory", icon: Package, items: ["Stock Receipts", "Book Stock"] },
    { title: "People", icon: Users, items: ["/team", { entity: "Employees", roles: ["ADMIN"] }] },
    { title: "Reports", icon: ChartColumn, items: ["Book Sales", "/ops"] },
    { title: "Support", icon: MessagesSquare, items: ["/support", "/support/connections"] },
    { title: "System", icon: Settings, items: ["/settings"] },
  ],
});
```

A `when` that asks the server once and remembers the answer:

```ts
import { api } from "@onno/ui";

let available: Promise<boolean> | null = null;

export function crmInboxAvailable(): Promise<boolean> {
  available ??= api.getJson<unknown>("/api/crm/inbox-workspaces").then(() => true, () => false);
  return available;
}
```

## Home: by role

```tsx
import { EntityList, Page, useHasRole } from "@onno/ui";
import { DashboardPage } from "./DashboardPage";

/** Admins land on the dashboard; everyone else on the working Orders list. */
export function HomePage() {
  if (useHasRole("ADMIN")) return <DashboardPage />;
  return (
    <Page>
      <EntityList entity="Orders" fill />
    </Page>
  );
}
```

## Dashboard: measures, stats, charts

```ts
// lib/measures.ts
import { count, sum } from "@onno/ui";
import { OrderStatus } from "../model.gen";

export const openOrders = count("Orders", { open: true });
export const allOrders = count("Orders");
export const revenue = sum("Orders", "total", { posted: true });
export const postedOrders = count("Orders", { posted: true });
export const cancelledOrders = count("Orders", { status: OrderStatus.CANCELLED });
export const bookSalesRevenue = sum("Book Sales", "revenue");
export const unitsSold = sum("Book Sales", "quantity");
```

```tsx
import { Chart, Page, RecentRecords, Row, Stat, TimeRange } from "@onno/ui";
import { allOrders, openOrders, postedOrders, revenue } from "../lib/measures";

export function DashboardPage() {
  return (
    <Page title="Dashboard" subtitle="Onno Books — orders and stock at a glance">
      <TimeRange title="Time range" presets={["24h", "7d", "30d", "90d", "1y", "all"]} default="30d" />

      <Row>
        <Stat title="Open orders" measure={openOrders} compare trend={false} hint="Orders not yet completed or cancelled." />
        <Stat title="Total orders" measure={allOrders} compare trend={false} />
        <Stat title="Revenue (posted)" measure={revenue} currency="USD" compare trend={false} />
      </Row>

      <Row>
        <Chart title="Orders by status" type="pie" measure={allOrders} by="status" />
        <Chart title="Revenue by day" type="area" measure={revenue} by={{ date: "day" }} currency="USD" />
      </Row>

      <Chart
        title="Orders & revenue"
        by={{ date: "week" }}
        bucketMode="fixed"
        series={[
          { measure: revenue, type: "area", label: "Revenue", color: "primary", currency: "USD" },
          { measure: postedOrders, type: "bar", label: "Orders", color: "warning", axis: "right" },
        ]}
        axes={{ left: { min: 0, label: "Revenue" }, right: { min: 0, label: "Orders" } }}
        thresholds={[{ value: 5000, label: "Weekly target", color: "success", style: "dashed" }]}
        legend="top"
        curve="linear"
      />

      <RecentRecords
        title="Recent orders"
        entity="Orders"
        limit={10}
        primary={(o) => `${o.number} · ${o.customerDisplay ?? ""}`}
        secondary={(o) => o.statusDisplay}
        amount={(o) => o.total}
        currency="USD"
      />
    </Page>
  );
}
```

## A list beside its numbers: controlled EntityList

```tsx
import { useState } from "react";
import { Chart, EntityList, Page, Row, Stat, TimeRange } from "@onno/ui";
import { SelectedOrders } from "../components/SelectedOrders";
import { allOrders, openOrders, revenue } from "../lib/measures";

export function SalesOpsPage() {
  const [selection, setSelection] = useState<string[]>([]);
  return (
    <Page title="Sales ops" subtitle="The Orders list beside its numbers">
      <Row widths={[2, 1]}>
        <EntityList entity="Orders" selection={selection} onSelectionChange={setSelection} />
        <>
          <SelectedOrders ids={selection} />
          <TimeRange title="Time range" presets={["7d", "30d", "90d", "1y", "all"]} default="all" />
          <Stat title="Open orders" measure={openOrders} trend={false} />
          <Stat title="Posted revenue" measure={revenue} currency="USD" trend={false} />
          <Chart title="Orders by status" type="pie" measure={allOrders} by="status" />
        </>
      </Row>
    </Page>
  );
}
```

An embedded list opening on a preset: `<EntityList entity="Orders" where={{ open: true }} defaultGroupBy="status" />`.

## Team: calendar, header action, openRecord

```tsx
import { Button, Calendar, EntityList, Page, Row, openRecord, useHasRole } from "@onno/ui";

export function TeamPage() {
  const canCreate = useHasRole("MANAGER", "ADMIN");
  return (
    <Page
      title="Team"
      subtitle="Upcoming events and staff availability"
      actions={canCreate ? <Button size="sm" onClick={() => openRecord("Schedule Events")}>New team event</Button> : null}
    >
      <Row widths={[2, 1]}>
        <Calendar
          title="Upcoming team events"
          entity="Schedule Events"
          start="startsAt"
          end="endsAt"
          allDay={false}
          itemTitle={(event) => event.subject}
          subtitle={(event) => event.number}
          hint="Open an event to manage participants; drag it to reschedule."
        />
        <EntityList entity="Employees" />
      </Row>
    </Page>
  );
}
```

## Replacing an entity screen

Registered at the entity's route (`/registers/book_sales`, `view: false` because the page needs
no server view):

```tsx
import { Page, Row, Stat, TimeRange } from "@onno/ui";
import { bookSalesRevenue, unitsSold } from "../lib/measures";

export function SalesReportPage() {
  return (
    <Page title="Sales report" subtitle="Turnover across the Book Sales register">
      <TimeRange title="Time range" presets={["30d", "90d", "1y", "all"]} default="all" />
      <Row>
        <Stat title="Total revenue" measure={bookSalesRevenue} currency="USD" trend={false} />
        <Stat title="Units sold" measure={unitsSold} trend={false} />
      </Row>
    </Page>
  );
}
```

To keep the default and add to it, use the route's `renderDefault()`:

```tsx
import type { RouteProps } from "@onno/ui";
import { Page, Stat, count } from "@onno/ui";

export function CustomersPage({ renderDefault }: RouteProps) {
  return (
    <Page>
      <Stat title="Customers" measure={count("Customers")} trend={false} />
      {renderDefault()}
    </Page>
  );
}
```

## Settings: constants and the print starter

```tsx
import { Page, Row, Setting } from "@onno/ui";
import { PrintTemplates } from "@onno/print";

/** Admin-only (see `roles` in main.tsx). */
export function SettingsPage() {
  return (
    <Page title="Settings">
      <Row>
        <Setting title="Store name" constant="Store Name" hint="The trading name of the shop." />
        <div />
      </Row>
      <PrintTemplates title="Print templates" hint="Edit the Word and Excel files behind the Print menu." />
    </Page>
  );
}
```

## A route with params: the CRM conversation

```tsx
import type { RouteProps } from "@onno/ui";
import { CrmConversationPage, CrmInbox, type CrmInboxProps } from "@onno/crm";

const inbox: Omit<CrmInboxProps, "workspace" | "conversation"> = {
  list: {
    columns: ["customer", "subject", "lastMessageAt", "unreadCount"],
    sort: "-lastMessageAt",
    filters: [{ field: "subject", label: "Subject", type: "contains" }],
  },
  connectionsRoute: "/support/connections",
};

export function SupportPage() {
  return <CrmInbox workspace="support" {...inbox} />;
}

/** `onno://catalogs/crm_conversations/<id>` opens the chat in the inbox. */
export function ConversationPage({ params }: RouteProps) {
  return <CrmConversationPage params={params} {...inbox} />;
}
```

## Kanban board

```tsx
import { Kanban, Page } from "@onno/ui";
import { OrderStatus } from "../model.gen";

export function OrdersBoardPage() {
  return (
    <Page title="Order board">
      <Kanban
        title="Orders by status"
        entity="Orders"
        by="status"                                         // dragging a card sets the status
        where={{ status: { not: OrderStatus.CANCELLED } }}
        primary={(o) => o.customerDisplay}
        amount={(o) => o.total}
        currency="USD"
      />
    </Page>
  );
}
```

## Your own data component

```tsx
import { useState } from "react";
import { Badge, Card, CardContent, Segmented, formatDate, formatMoney, openRecord, useRecords } from "@onno/ui";

/** The latest orders; `useRecords` returns `Order[]` and stays live as orders change. */
export function OrderActivity({ limit = 10 }: { limit?: number }) {
  const [order, setOrder] = useState<"newest" | "oldest">("newest");
  const { data: latest = [], error } = useRecords("Orders", { sort: order === "newest" ? "-date" : "date", limit });

  return (
    <Card>
      <CardContent className="p-6">
        <Segmented
          value={order}
          onChange={setOrder}
          options={[{ value: "newest", label: "Newest" }, { value: "oldest", label: "Oldest" }]}
        />
        {error ? <p className="text-xs text-destructive">Failed to load orders.</p> : null}
        {latest.map((o) => (
          <button key={o.id} type="button" onClick={() => openRecord("Orders", o.id)}
            className="flex w-full justify-between rounded-field px-2 py-2 text-left hover:bg-accent/40">
            <span>{o.number} · {o.customerDisplay}</span>
            <span>
              {o.total != null ? <Badge variant="secondary">{formatMoney(o.total, "USD")}</Badge> : null}
              <span className="ml-2 text-[10px] text-muted-foreground">{formatDate(o.date)}</span>
            </span>
          </button>
        ))}
      </CardContent>
    </Card>
  );
}
```

## Running a command from a component

```tsx
import { Button, useCommand } from "@onno/ui";

export function AdvanceButton({ orderId }: { orderId: string }) {
  const advance = useCommand("Orders", "advance");      // OrderCommands' "advance"
  return (
    <Button size="sm" disabled={advance.isPending} onClick={() => advance.mutate({ id: orderId })}>
      Advance
    </Button>
  );
}
```

A command with inputs: `cancel.mutate({ id, inputs: { reason: "Out of stock" } })`. The server
enforces roles, availability and record access; queries over the entity refresh on success.

## Registers and constants in code

```tsx
import { useBalance, useConstant, useMovements } from "@onno/ui";

export function LowStock() {
  const { data: low = [] } = useBalance("Book Stock", { where: { quantity: { lt: 5 } } });
  const { data: receipts = [] } = useMovements("Book Stock", { where: { movementType: "RECEIPT" } });
  const { data: storeName } = useConstant("Store Name");
  return <p>{storeName}: {low.length} titles low, {receipts.length} receipts</p>;
}
```

## Links

```tsx
import { Button, links, openRoute } from "@onno/ui";

export function UnpostedOrdersLink() {
  return (
    <Button variant="outline" onClick={() => openRoute(links.list("Orders", { where: { posted: false } }))}>
      Unposted orders
    </Button>
  );
}
```
