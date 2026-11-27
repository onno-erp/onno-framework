---
name: onno-ui-pages
description: >-
  Build an onno-framework app's own pages, navigation and widgets in React with @onno/ui. Use when
  adding a page (defineApp pages: path/list/record, component, label, icon, roles, when, view), replacing an
  entity's default screen at its route, curating the sidebar (navigation sections and items),
  the home page, composing pages from Page/Row/EntityList/DefaultView, controlling an embedded
  EntityList (where, defaultGroupBy, selection, fill), dashboard widgets (Stat, Sparkline, Gauge,
  Chart, TimeRange, Calendar, Kanban, RecentRecords, EntityMap, Setting, TaskInbox, UserAccounts,
  PeriodClose, RecordHistory)
  with typed measures (count/sum/avg/min/max), where predicates, groupings and render functions,
  the data hooks (useRecords, useRecord, useAggregate, useBalance, useTurnover, useMovements,
  useInformationRegister, useConstant, useCreateRecord, useCommand, …), live updates, onno:// links
  (links, openRoute, openRecord, RouteLink, command-result navigation), non-modal windows
  (FloatingWindow, windows.open: docked/floating/resizable/minimized/maximized), extension outlets (defineApp extensions, ExtensionSlot), the
  starters' screens (@onno/print PrintTemplates, @onno/crm CrmInbox), and full-height workspaces.
---

# onno UI: pages, navigation, widgets and data

A page is a React component registered in `defineApp({ pages })`. It is composed from `@onno/ui`
building blocks and the app's own components, reads data through typed hooks, and stays live as
the server reports changes. Server behaviour (what a button does) is a Java command — see
`onno-commands`.

## Replacing built-in components

Use the public SDK rather than copying lifecycle logic. Record pages already receive a controller;
`useRecordForm` creates the same controller from a fetched descriptor in a custom host.
`useRecordField` or `RecordField` binds a custom editor to values, validation, write policy and
picker context. Custom list renderers receive shared pagination, selection and select-all.
See [the replacement guide](../../../docs/UI_SDK.md); do not import private `@onno/ui/src` files.

## Development workflow

Use Vite hot reload for page and component changes. In this repository, run `npm run dev` at the
root against the existing backend on 8091 and open `http://localhost:5173`. Do not rebuild a jar
or restart the backend for UI-only edits. Application Java/model edits use `npm run dev:backend`
and `npm run dev:watch` in separate terminals. These root scripts belong to the framework repo;
consumer apps run Vite in their own `src/main/ui`. See
[the intended workflow](../../../docs/RUNNING.md#intended-development-workflow).

## Pages

How an entity presents (columns, filters, field formats, command icons, its nav `icon`) is its
`EntityCustomization` in `entities/` (`onno-ui-entities`), not a page. Register `{ list: "Suppliers",
component: SuppliersPage }` or `{ record: "Stock Receipts", component: StockReceiptPage }` only when
the layout differs from the default. The injected `list` is a descriptor (`ListPageProps<E>`); the
injected `form` is live state (`RecordPageProps<E>`). Compose `EntityList`, `RecordPage`,
`RecordFields` (`fields={["supplier"]}` picks and orders), `RecordLines`, and normal React
controls; pass `overrides` only for what differs on that page. See
[the package examples](../../../onno-ui/README.md). Icons below are imports from `@onno/ui/icons`,
not strings. The desktop rail exposes `shell.navigation.actions` for custom React buttons.


```tsx
defineApp({
  pages: [
    { path: "/", component: HomePage },                                           // home
    { path: "/ops", component: SalesOpsPage, label: "Sales Ops", icon: Activity },
    { path: "/settings", component: SettingsPage, label: "Settings", icon: Settings, permission: "settings.edit" },
    { path: "/support", component: SupportPage, label: "Inbox", icon: Inbox, when: crmInboxAvailable },
    { list: "Suppliers", component: SuppliersPage },                    // replaces a default screen
    { path: "/registers/book_sales", component: SalesReportPage, view: false },
    { path: "/catalogs/crm_conversations/:id", component: ConversationPage, view: false },
  ],
});
```

| Key | Meaning |
| --- | --- |
| `path` | The route without the `/ui` prefix. `"/"` is home. An entity route (`/catalogs/<route>`, `/documents/<route>`, `/registers/<route>`, lowercase snake case of the logical name) replaces that entity's default screen. `:param` and `*` capture. |
| `component` | Receives `RouteProps`: `path`, `params`, `query`, `view` (the server's default view for the route, if fetched) and `renderDefault()`. |
| `label`, `icon` | How navigation shows the page. |
| `roles` | Only these roles see the page and its link (compared normalized; no role passes implicitly). Presentation only — data stays guarded by `RoleGrants`. |
| `permission` | Only holders of this permission (`"settings.edit"`, typed from `model.gen.ts`) see the page and its link; the server checks it again. |
| `when` | `(model) => boolean \| Promise<boolean>` — e.g. an optional module being present. |
| `view` | Fetch the server's default view for the route (default: only on entity routes). `false` for a purely client-side page at an entity route. |

A `/` page is home: the shell's home link opens it. Without one, the home link opens the first
navigation item and `/` shows the default home (entity cards by context — `<DefaultHome />`
renders it inside your own page). The shell links home itself — never put `"/"` in
`navigation`.

## Navigation

Navigation sections and nested groups accept React `actions` and recursive `items`; `roles`
filters branches and empty groups disappear. Desktop active routes reveal ancestor groups; the
mobile More menu uses the same tree, while bottom tabs derive from its leaf destinations.
Register `navigationComponent: AppNavigation` for a custom desktop/menu layout. Compose
`Navigation`, `NavigationSection`, `NavigationGroup`, `NavigationLink`, and `DefaultNavigation`;
`NavigationProps` supplies the resolved tree, active path, mobile flag and optional desktop
header/footer. Custom links share model/page gates and the shell dispatcher. See
[the UI package](../../../onno-ui/README.md) for examples.



```tsx
navigation: [
  { title: "Sales", icon: ShoppingCart, items: ["Orders", "Customers", "/tasks"] },
  { title: "Inventory", icon: Package, items: ["Stock Receipts", "Book Stock"] },
  { title: "People", icon: Users, items: ["/team", { entity: "Employees", roles: ["ADMIN"] }] },
  { title: "System", icon: Settings, items: ["/settings"] },
],
```

An item is an entity's logical name, a declared page path (an undeclared path is a type error), or
`{ entity | path, label?, icon?, roles? }`. Entities the user cannot read, pages they may not use
and empty sections drop out. Omit `navigation` for the default: labelled pages first, then one
section per bounded `context`. `navigation: (model, defaults) => sections` edits the defaults in
code. Name sections after user jobs ("Sales", "Support"), not storage kinds ("Catalogs").

## Page building blocks

| Component | Use |
| --- | --- |
| `Page` | `title`, `subtitle`, `actions` (header right edge), children. |
| `Row` | Columns side by side on desktop, stacked on phones: `widths={[2, 1]}` (flex weights) or `{ px: 320 }`. Wrap several blocks of one column in a fragment. |
| `EntityList` | The full interactive list of an entity (see below). |
| `DefaultView` | The server's default screen for a path: `<DefaultView path="/documents/orders" />`. |
| `DefaultHome`, `PageHeader` | The default home; the page header alone. |

```tsx
export function SuppliersPage() {
  return (
    <Page title="Suppliers" subtitle="Who we buy stock from">
      <Row widths={[1, 2]}>
        <Stat title="Total suppliers" measure={count("Suppliers")} trend={false} />
        <div />
      </Row>
      <EntityList entity="Suppliers" />
    </Page>
  );
}
```

### `EntityList`

Starts from the app's customization of the entity (`onno-ui-entities`); props change one instance.
It works like an `<input>`: `default…` sets where it starts, the controlled pair hands state to the
page.

| Prop | Meaning |
| --- | --- |
| `entity` | Logical name (required). |
| `where` | Records it always shows, typed: `{ open: true }`. |
| `columns`, `labels`, `title`, `overrides` (field presentation), `customization` | Per-instance presentation over the app's customization. |
| `fill` | Grow to the page height and scroll inside (full-height workspaces). |
| `calendar`, `timeline` | Calendar / resource-lane timeline views of date-ranged records (see `onno-ui-entities`). |
| `defaultView`, `defaultGroupBy`, `defaultSort` | Initial state (`"table" \| "map" \| "calendar" \| "timeline" \| "custom"`, a field, `"-date"`). |
| `view`/`onViewChange`, `groupBy`/`onGroupByChange`, `sort`/`onSortChange`, `search`/`onSearchChange`, `selection`/`onSelectionChange` | Controlled state. |

```tsx
const [selection, setSelection] = useState<string[]>([]);
<Row widths={[2, 1]}>
  <EntityList entity="Orders" selection={selection} onSelectionChange={setSelection} />
  <SelectedOrders ids={selection} />
</Row>
```

## Widgets

Typed per entity: a measure's field, a grouping's field, a `where` and a render function's record
are all checked against the model.

```ts
// lib/measures.ts — define numbers once, use them on several pages
import { count, sum } from "@onno/ui";
import { OrderStatus } from "../model.gen";

export const openOrders = count("Orders", { open: true });
export const revenue = sum("Orders", "total", { posted: true });
export const cancelledOrders = count("Orders", { status: OrderStatus.CANCELLED });
export const unitsSold = sum("Book Sales", "quantity");   // a register: signed movements
```

`count(entity, where?)`, `sum|avg|min|max(entity, numericField, where?)`. Over a register a
measure aggregates movements (receipts add, expenses subtract); its `where` may also name
`period`, `recorder`, `movementType`. Aggregation runs on the server.

| Widget | Key props |
| --- | --- |
| `Stat` | `measure`, `compare`, `trend`, `sparkline` (`true` or a `By`), `ratioTo`, `currency`/`unit`, `format` |
| `Sparkline`, `Gauge` | `measure`, `by` / `target` (number or measure) |
| `Chart` | `measure` + `by` (`"status"`, `{ date: "week" }`), `type` (`bar`, `line`, `area`, `pie`, …), `splitBy`, `stacked`; or `series=[{ measure, type, label, color, axis }]` with `axes`, `thresholds`, `legend`, `curve`, `bucketMode` |
| `TimeRange` | `presets={["7d","30d","all"]}`, `default`, `id` — every stat and chart on the page follows it |
| Data the page already has | `Chart` takes `data={rows}` with `x` (a category, or an ISO date / `Date`, bucketed by `bucket` or by span) and `value`, or `series=[{ value, type, axis }]`; `splitBy` names a row key; `headline="total" \| "last" \| "none"`. `Stat` takes `value`, `previous`, `points`; `Sparkline` takes `points` (and `value`). No entity, no request, no shared time range: the page fetches and windows its rows itself |
| `Calendar` | `entity`, `start`, `end` or `duration`, `allDay`, `itemTitle`/`subtitle`/`color` (record → value), `where`, `fill`; drag reschedules |
| `Kanban` | `entity`, `by` (an enum or reference field, or `posted`), `where`, `caption`/`primary`/`secondary`/`amount`; drag sets the field (or posts/unposts) |
| `RecentRecords` | `entity`, `where`, `limit`, `dateField`, `primary`/`secondary`/`amount`, `currency` |
| `EntityMap` | `entity`, `lat`+`lng` or `geoJson`, `label`, `where` |
| `Setting` | `constant="Store Name"` — edits one `@Constant` (administrators) |
| `TaskInbox`, `UserAccounts`, `ChangePassword` | Process task inbox; user administration (`DATABASE` auth); password change |
| `PeriodClose` | Period-close administration over `/api/period-close`: `scopes` (default global, every context and register; pass `organization:<id>` here), `auditRows`. Gate its page with `when: (m) => m.features.periodClose === true` |
| `RecordHistory` | `target={{ kind, name, id }}` — one record's change history (entities with `history = true`) |

All take `title` and `hint`.

```tsx
<Page title="Dashboard">
  <TimeRange title="Time range" presets={["24h", "7d", "30d", "90d", "1y", "all"]} default="30d" />
  <Row>
    <Stat title="Open orders" measure={openOrders} compare trend={false} hint="Not yet completed or cancelled." />
    <Stat title="Revenue (posted)" measure={revenue} currency="USD" compare trend={false} />
  </Row>
  <Row>
    <Chart title="Orders by status" type="pie" measure={allOrders} by="status" />
    <Chart title="Revenue by day" type="area" measure={revenue} by={{ date: "day" }} currency="USD" />
  </Row>
  <RecentRecords
    title="Recent orders" entity="Orders" limit={10}
    primary={(o) => `${o.number} · ${o.customerDisplay ?? ""}`}
    secondary={(o) => o.statusDisplay}
    amount={(o) => o.total} currency="USD"
  />
</Page>
```

Starters ship their screens as packages next to their Java code; add the package to the app's
`package.json` and render: `<PrintTemplates title="Print templates" />` from `@onno/print`;
`<CrmInbox workspace="support" list={…} />`, `<CrmConnections setups={[channelSetups]} />`,
`CrmConversationPage` from `@onno/crm` (+ `@onno/crm-channels`). The CRM is also exported in
parts: `CrmConversation` (one chat anywhere), `openCrmConversation(...)` (a docked window across
pages), `CrmContactPage`, the row/timeline/composer components and the `useCrmConversation` /
`useCrmComposer` hooks; `CrmInbox` takes `components`, `timelineSources` (business entries in the
chat) and `renderEntry`. See `onno-crm-starter/README.md` → "Building your own screens" and the
`onno-crm-*` skills.

## Your own components: data hooks

Plain React over typed data. Every hook is TanStack Query on the shell's shared client and stays
live: the server's change events invalidate an entity's queries.

| Hook | Returns |
| --- | --- |
| `useRecords(entity, { where, sort, limit })` | `RecordOf<E>[]` |
| `useRecord(entity, id)` | one record |
| `useAggregate(measure, by?)` | `{ buckets, truncated }` |
| `useBalance(register, { where, asOf })`, `useTurnover(register, { from, to, where })`, `useMovements(register, { from, to, where })` | every register row (read window by window, 500 at a time); `api`'s `getMovements`/`getBalance`/`getTurnover` return one `{ rows, nextCursor, hasMore }` window and `allRegisterRows` follows the cursor |
| `useInformationRegister(name, { where, at })` | slice of last values |
| `useConstant(name)`, `useSetConstants()` | `@Constant` values (administrators) |
| `useCreateRecord`, `useUpdateRecord` (`{ id, values }`), `useDeleteRecord`, `usePostDocument`, `useUnpostDocument` | mutations through the same server path as the forms |
| `useCommand(entity, key)` | runs a Java command: `run.mutate({ id, inputs })` / `run.mutate({ inputs })` |
| `useModel()`, `useHasRole(...roles)` | the model as the user sees it; role check |
| `useQuery`, `useMutation`, `queryClient`, `api` | escape hatches on the same client / REST client |

```tsx
import { Card, CardContent, formatMoney, useRecords } from "@onno/ui";

export function SelectedOrders({ ids }: { ids: string[] }) {
  const { data: picked = [] } = useRecords("Orders", { where: { id: { in: ids } } });
  const total = picked.reduce((sum, o) => sum + (o.total ?? 0), 0);
  return (
    <Card>
      <CardContent className="p-5">
        {ids.length} · {formatMoney(total, "USD")}
      </CardContent>
    </Card>
  );
}
```

Never open your own `EventSource`. Entity data needs nothing; for comments, presence, tasks and
other non-entity events use `useUiEvents(listener, { types, entityName })`. Format with
`formatMoney(value, "USD")`, `formatDate(value, options?)`, `displayOf(record, "customer")`.

## Opening things: `onno://` links

Links name what to open; web and mobile clients share them.

| Builder | Link | Opens |
| --- | --- | --- |
| `links.record("Orders", id)` | `onno://documents/orders/<id>` | a record |
| `links.newRecord("Orders", { customer: id })` | `onno://documents/orders/new?customer=<id>` | a new record, prefilled (ids for references/enums, ISO dates) |
| `links.list("Orders", { where: { posted: false } })` | `onno://documents/orders?where=<json>` | a list showing only those records |
| `links.page("/ops")` | `onno://ops` | an app page (`onno://` is home) |

Open with `openRoute(link)` (also takes a page path). Like a link in a browser window, it replaces
what the active workspace tab shows and that tab's Back returns; `openRoute(link, { newWorkspaceTab:
true })` opens a new workspace tab and `openRoute(link, { newTab: true })` a browser tab under the
app's base path. Use `newWorkspaceTab` when several records open at once (each would otherwise
replace the previous one) or the shortcut `openRecord("Orders", id)` /
`openRecord("Orders", "new", prefill)`. `<RouteLink to={link}>` is a real anchor (middle-click and
copy work) that opens in the workspace on a plain click. A Java command can send the user on with
`CommandResult.open(ref)` / `.navigate(route)`; the client applies the result's `navigateTo` after
its feedback and refresh, through the same dispatcher, and ignores external or command URLs. Build links in handlers and
components, not at module scope — they resolve routes through the loaded model.

## Windows: docked, floating, resizable

Non-modal windows that sit above the page without blocking it: a Gmail-style compose box docked in
the corner, a calculator or notes panel the user drags and resizes while working, a record preview
beside a list. Each window can be minimized to a title bar, docked bottom-right (docked and minimized
windows line up right to left), floated (drag the title bar, resize from any edge), or maximized. On
phones a window always fills the screen. Escape inside a window never reaches the page beneath it.
For a blocking question or form, use a command's input dialog instead.

Owned by a component, with children in the owner's state and context. It closes with its owner, unless
another component renders a `FloatingWindow` with the same `id` in the same commit: then that one takes the
window over as it is (minimised, docked, moved, resized). The inbox uses this to keep a composer window on its
conversation while the planner opens other chats.

```tsx
import { FloatingWindow } from "@onno/ui";

<FloatingWindow open={open} onOpenChange={setOpen} title="Price calculator"
  initialState="docked" width={420} height={520} persist="acme.calculator"
  footer={<Button onClick={apply}>Apply</Button>}>
  <Calculator />
</FloatingWindow>
```

Owned by the app, opened from anywhere (a command result, a list row, a page button). It stays open
while the user navigates and is closed on sign-out:

```tsx
import { windows, useWindow } from "@onno/ui";

windows.open(`order-preview:${id}`, {          // same id again = bring it forward, no duplicate
  title: `Order ${number}`, initialState: "floating", width: 640,
  content: <OrderPreview id={id} />,             // or (w) => … with the window's controls
  onClose: () => confirm("Discard changes?"),    // return false to keep it open
});

function OrderPreview({ id }: { id: string }) {
  const w = useWindow();                         // { id, state, setState, close, focus }
  return <Button onClick={() => w.setState("minimized")}>Later</Button>;
}
```

Spec fields: `title`, `icon`, `initialState` (`floating` | `docked` | `maximized`), `width`/`height`,
`minWidth`/`minHeight`, `x`/`y`, `resizable`, `minimizable`/`dockable`/`maximizable`/`closable`,
`persist` (localStorage key for state + geometry), `headerActions`, `footer`, `padded`,
`bodyClassName`. `useOpenWindows()` lists open windows live (for a task bar or badge);
`windows.close/focus/setState/update/isOpen/list` control them by id. Windows sit above the page and
below popovers and modal dialogs, so pickers and command dialogs opened from inside one work.

## Extension outlets

Contribute to existing surfaces without forking them:

```tsx
defineApp({
  extensions: [{
    id: "acme.orders.copy-number",
    slot: "entity.form.actions",
    visible: (ctx) => ctx.name === "orders",
    component: ({ context }) => <Button size="sm" onClick={() => navigator.clipboard.writeText(String(context.record?.number ?? ""))}>Copy number</Button>,
  }],
});
```

`context.kind`/`context.name` are the route segments (`documents`, `stock_receipts`).
Slots: `entity.list.context-menu`, `entity.list.actions`, `entity.list.selection`,
`entity.form.actions`, `entity.form.aside`, `crm.chat.header`, `crm.chat.composer`,
`crm.chat.aside`, `crm.chat.context-menu`, `crm.contact.actions`, `crm.contact.profile`,
`crm.inbox.toolbar`. The `context` carries the surface, record, selection, permissions, `refresh`,
and on CRM outlets `execute(command, inputs)` for the named commands it supplies. Visibility never
replaces server authorization. Render your own outlet with `<ExtensionSlot name context />`.

## Full-height workspaces

An inbox, scheduler or board that should fill the tab: render `<EntityList entity=… fill />`
(or `Calendar fill`) and give the list a `custom` view whose root is `h-full min-h-0` with only
inner panes scrolling. Read [references/full-height-workspaces.md](references/full-height-workspaces.md)
before building one.

Read [references/examples.md](references/examples.md) for complete pages from the example app.
