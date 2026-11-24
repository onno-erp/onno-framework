# @onno/ui

The onno UI as an npm package: a React shell that renders a default list and record form for every
entity of the Java business model, navigation grouped by bounded context and the register reports —
plus everything an application builds its own UI from: page blocks, data widgets, controls, typed
data hooks and the `onno://` link protocol.

The server describes the model and enforces every rule (see
[onno-web-starter](../onno-web-starter/README.md)); it never describes screens. An application's UI
is an ordinary Vite app that imports `@onno/ui` and declares only what differs from the defaults. An
application without one gets this package's default shell, bundled into `onno-web-starter`.

## An app

The `su.onno.ui` Gradle plugin ([onno-ui-gradle-plugin](../onno-ui-gradle-plugin/README.md)) builds
`src/main/ui` into the application's jar and generates `model.gen.ts`, the TypeScript types of the
Java entities. `./gradlew onnoUiInit` seeds this layout:

| File | What it is |
| --- | --- |
| `package.json`, `vite.config.ts`, `tsconfig.json`, `index.html` | A normal Vite app. The config is `plugins: [onno()]` from `@onno/ui/vite`. |
| `main.tsx` | One `defineApp({ login, entities, pages, navigation })` call. |
| `entities/*.ts` | How each entity presents, one `EntityCustomization<E>` per file — applied to its default list and form, embedded lists and custom pages. |
| `pages/LoginPage.tsx`, `pages/HomePage.tsx` | The `/login` screen and the page at `/`, seeded as the framework's `<DefaultLogin>` and `<DefaultHome>`. |
| `pages/*.tsx`, `components/*.tsx` | The app's own pages and components, and entity screens whose layout differs from the default. |
| `model.gen.ts` | Generated on every build (git-ignored). Types entity names, fields and enum values. |

```ts
// entities/Orders.ts
import { ClipboardList } from "@onno/ui/icons";
import type { EntityCustomization } from "@onno/ui";

export const orders = {
  icon: ClipboardList,
  list: { columns: ["number", "date", "customer", "total"], sort: "-date", groupable: ["customer", { date: "month" }] },
  form: { fields: ["customer", "status", "note"] },
  fields: {
    customer: { width: "half" },
    total: { format: { money: "USD" } },
    "items.book": { label: "Book" },
  },
  comments: true,
} satisfies EntityCustomization<"Orders">;
```

```tsx
// main.tsx
import { defineApp } from "@onno/ui";
import { Activity, ShoppingCart } from "@onno/ui/icons";
import { orders } from "./entities/Orders";
import { OrderPage } from "./pages/OrderPage";
import { HomePage } from "./pages/HomePage";
import { SalesOpsPage } from "./pages/SalesOpsPage";

defineApp({
  entities: { Orders: orders },
  pages: [
    { path: "/", component: HomePage },
    { record: "Orders", component: OrderPage },
    { path: "/ops", component: SalesOpsPage, label: "Sales Ops", icon: Activity },
  ],
  navigation: [{ title: "Sales", icon: ShoppingCart, items: ["Orders", "Customers", "/ops"] }],
});
```

```tsx
// pages/OrderPage.tsx — registered only because its layout differs from the default
import { RecordPage, RecordFields, RecordLines, type RecordPageProps } from "@onno/ui";

export function OrderPage({ form }: RecordPageProps<"Orders">) {
  return <RecordPage form={form}>
    <RecordFields form={form} fields={["customer", "status"]} />
    <RecordLines form={form} section="items" />
    <textarea value={form.values.note ?? ""} onChange={(event) => form.set("note", event.target.value)} />
  </RecordPage>;
}
```

**Selection is an array, presentation is a map.** `list.columns` and `form.fields` choose which
fields show and in what order; `fields` only says how each looks (its key order means nothing).
The entity's customization applies wherever it shows — its default list and form, an embedded
`<EntityList entity="Orders" />`, a custom page — so a page passes only what differs there:
`<EntityList overrides={…}>`, `<RecordFields fields={[…]} overrides={…}>`,
`<RecordLines section="items" overrides={…}>`. Register a `{ list }` / `{ record }` page only when
its layout differs from the default.

Entity pages resolve their routes from the readable model; missing optional entities create no route.
`ListPageProps<E>` injects the list descriptor, so `EntityList` needs no second view request.
`EntityList` also takes the list options as props (columns, filters, grouping, aggregates, map,
calendar, timeline, custom view) for this list only. `sort` and `groupBy` set initial presentation;
pair them with their `on…Change` callback for controlled state. `selectionToolbar` is a component;
`selection` is the controlled array of selected ids.

`where` follows the page's current state: changing or removing it reloads the first matching
window and clears the previous selection. For example,
`<EntityList entity="Orders" where={onlyOpen ? { open: true } : undefined} />` switches between
open orders and all orders when `onlyOpen` changes. Recreating an equivalent filter object during
a render does not reload the list; no changing React `key` or manual refresh is needed.

`RecordPageProps<E>` injects the live form: `values`, typed `set(field, value)`, `save(post?)`,
`isNew`, `readOnly`, `saving`, `errors`, `feedback`, and `defaultLayout`. Custom editors can display
`form.errors.note` and the field-scoped `form.feedback` messages. `RecordPage` retains the header, actions,
validation feedback, save/post footer, dirty guard, and live refresh. `RecordFields` renders header
fields (`fields` picks and orders them, default the entity's `form.fields`); `RecordLines` renders
one section; both take page-only `overrides`. Picker
cascades apply across both. Render `{form.defaultLayout}` to extend the default arrangement, or
use `<RecordPage form={form} />` for the complete default form. Saved, new and duplicate records
use the same registered component. `RecordPage` also accepts `commands`, `panels`, and `comments`.
Ordinary React controls (including npm editors such as Lexical) read and update the same form;
controls inside `RecordPage` inherit its disabled fieldset when the record is read-only.

```tsx
// pages/DashboardPage.tsx
import { Chart, Page, RecentRecords, Row, Stat, TimeRange, count, sum } from "@onno/ui";

const revenue = sum("Orders", "total", { posted: true });

export function DashboardPage() {
  return (
    <Page title="Dashboard">
      <TimeRange presets={["7d", "30d", "1y"]} default="30d" />
      <Row>
        <Stat title="Open orders" measure={count("Orders", { open: true })} />
        <Stat title="Revenue" measure={revenue} currency="USD" />
      </Row>
      <Chart title="Revenue by day" type="area" measure={revenue} by={{ date: "day" }} currency="USD" />
      <RecentRecords title="Recent orders" entity="Orders" primary={(o) => o.number} />
    </Page>
  );
}
```

The full reference app is [`example/src/main/ui`](../example/src/main/ui).

Standard entity lists, including embedded lists and custom row/card renderers over a standard feed,
offer **Export → XLSX / CSV** to configured export roles (default `ADMIN`). The file includes every
matching authorized row with the current search, filters, sort, grouping and column order. Picking,
map/schedule modes and custom feeds do not show the button. The server enforces roles and row limits;
see [list export](../onno-web-starter/README.md#list-export--apilistexportkindname).

## The public API

Everything an app can import is in [`src/index.ts`](src/index.ts); the types are in
[`src/public-types.ts`](src/public-types.ts). If it is not exported there, it is internal.

| Area | Exports |
| --- | --- |
| The app | `defineApp(app)` — `entities` (one `EntityCustomization<E>` per entity name), `pages` (`path` or `list` or `record`, `component`, `label`, `icon`, `roles`, `when`, `view`), `navigation` (sections of entity names and page paths, or a function of the model), `extensions` (components for named `ExtensionSlot` outlets), `login` (the app's own login page). |
| Login | `useSignIn()` (`password`, `method(id, credentials)`, `challenge(id, input)`, `redirect(provider)`), `useLoginMethods()`, `LoginLayout`, `SignInMethods`, `PasswordLogin`, `SsoButtons`, `LoginButton`, `DefaultLogin`, `SignInError`; types `LoginPageProps`, `LoginMethods`, `SsoOption`. |
| Navigation UI | `Navigation`, `NavigationSection`, `NavigationGroup`, `NavigationLink`, `DefaultNavigation`; section `actions`, nested `items`, optional `navigationComponent`. |
| Navigation | `openRecord(entity, id?, prefill?)`, `openRoute(link \| path, { newWorkspaceTab?, newTab? })` (an open replaces the active tab's screen and its Back returns; `newWorkspaceTab` opens a new workspace tab, `newTab` a browser tab), `RouteLink` (an anchor that opens in the workspace), `links.record` / `newRecord` / `list` / `page` (typed `onno://` links). A command result's `navigateTo` is applied after its feedback and refresh. |
| Model | `useModel()`, `useHasRole(...roles)`. |
| Page blocks | `Page`, `Row`, `RecordPage`, `RecordFields`, `RecordLines`, `PageHeader`, `DefaultView`, `DefaultHome`, `EntityList` (an entity's full list; controllable: `where`, `defaultGroupBy`/`groupBy`, `sort`, `view`, `search`, `selection` with `on…Change`; `calendar`/`timeline` views). `RecordPage` takes `history` (default: when the entity records history). |
| Widgets | `Stat`, `Sparkline`, `Gauge`, `Chart`, `TimeRange`, `RecentRecords`, `Kanban`, `Calendar`, `EntityMap`, `Setting`, `TaskInbox`, `UserAccounts`, `ChangePassword`, `PeriodClose` (period-close administration), `RecordHistory` (one record's change history). Typed per entity; they read data themselves and follow the page's time range. |
| Controls | `Button`, `Badge`, `Input`, `Label`, `Textarea`, `Checkbox`, `Switch`, `Segmented`, `Card…`, `Popover…`, `Select…`, `ContextMenu…`, `DialogShell`, `ResponsiveActionPanel`, `WorkspaceToolbar`, `DatePicker`, `DateTimePicker`, `TimePicker`, `InstantPicker`, `ColorPicker`, `RefSelect`, `EntityTags`, `EntityTagMenu`, `CommentComposer`, `CommentBody`, `EntityListView`, `OptionsFacet`, `toast`, `HintIcon`. |
| Data | `useRecords(entity, { where, sort, limit })`, `useRecord`, `useAggregate(measure, by)`, `useBalance`, `useTurnover`, `useMovements` (every window of the register feed), `useInformationRegister`, `useConstant`, `useSetConstants`, `useCreateRecord`, `useUpdateRecord`, `useDeleteRecord`, `usePostDocument`, `useUnpostDocument`, `useCommand(entity, key)`, `useCommandPresentations(kind, name)` (the entity's `commands` customization, merged, for a surface that lists commands itself); `api` (register reads `getMovements`/`getBalance`/`getTurnover` return one `{ rows, nextCursor, hasMore }` window; `allRegisterRows` follows the cursor), `queryClient` and TanStack Query's `useQuery`/`useMutation`. |
| Measures | `count`, `sum`, `avg`, `min`, `max` — `sum("Orders", "total", { posted: true })`. |
| Live events | `useUiEvents(listener, filter)`, `subscribeUiEvents` — for comments, presence, tasks; entity data refreshes by itself. |
| Text | `useTranslate`, `useLocale`, `text`, `locale`, `formatMoney`, `formatDate`, `displayOf`. |
| Mobile, outlets | `useMobileDetail`, `usePageSlide`, `ExtensionSlot`. |

**Typed vocabulary.** Everything an app hands the package is a typed value, never a string it
parses: entities by logical name (`"Orders"`); which records as a `where` object
(`{ posted: true, total: { gte: 100 }, status: { in: [OrderStatus.NEW] } }`, sent to the server as
JSON); formats `{ money: "USD" }`, `{ number: { decimals: 0 } }`, `{ date: "medium" }`; inputs
`"textarea"`, `"switch"`, `"image"`, `"color"`, `"map"`, …; widths `"full" | "half"`; icons
imported Lucide components (see Icons below); groupings `"status"` or `{ date: "month" }`; subtotals
`{ sum: "total", label, format }`. With the generated model all of it is checked per entity.

**Entity customization** (`EntityCustomization<E>`, one per entity in `defineApp({ entities })`): `list` (columns, labels, `where`, sort,
`groupBy`, `groupable`, `aggregates`, `filters`, `map`, `calendar` (`{ start, end?, title?, color?, mode? }`), `timeline` (`{ start, end, lane?, laneEntity?, slotMinutes?, startHour?, endHour?, … }`; drags save as ordinary record updates), `custom` view component, `selection` toolbar
component, `selectionCheckboxes`, `groupsExpanded`), `form` (`fields`: the header fields shown, in order),
`fields` — presentation only, keyed by field (`"section.field"` for line columns; `code`/`description`
too): label, hidden/hideInList/hideInForm, group,
width, input, format, `render`, placeholder, hint, `refSecondary`, `filterBy` for a cascading
picker, openButton/clearButton, `commandGroup` for a cell's right-click menu), `rowStyle`, `commands` (placement `"primary" | "menu" | "row" |
"hidden"`, icon — also for the built-ins `post`, `unpost`, `duplicate`, `delete`, `openInList` and
modules' `print-*`), `recordPanels`, `icon`, `comments`, `relations` (a many-to-many join entity's rows edited like a tabular section and saved with the record: `{ entity: "Supplier Offers", via: "supplier", columns: ["book", "purchasePrice"] }`). Relation editors load every cursor window before enabling edits; saving therefore preserves links beyond the first 500 rows. Failed or invalid continuations never produce a partial replacement payload. The commands themselves, their inputs and
who may run them are the server's (`Commands` beans).

**Time.** `LocalTime` fields use `TimePicker`; `Instant` fields use `InstantPicker` and display in
the zone `/api/config` reports (`onno.ui.time-zone`), else the viewer's browser zone.

**`onno://` links** name what to open, not how a client shows it: `onno://documents/orders/<id>`,
`onno://documents/orders/new?customer=<id>`, `onno://documents/orders?where=<json>`, `onno://ops`.
Build them with `links` and open them with `openRoute`.

**Icons** are imported Lucide components, shipped through `@onno/ui/icons` (or import directly
from `lucide-react` when it is your dependency). Use `icon: Truck` in registrations, navigation,
and command presentation; icon name strings do not type-check. A command may choose a component
per row: `icon: (order) => order.posted ? CircleCheck : Pencil`.

```tsx
import { Icon, Truck } from "@onno/ui/icons";
<Truck className="size-4" />
<Icon icon={Truck} size={14} />
```

The desktop navigation exposes `shell.navigation.actions` through `defineApp({ extensions })`;
register an ordinary React button component there to add a navigation action. Entity and CRM
outlets remain available for actions, side panels, context menus, and chat controls.

**Roles and pages.** `RoleName` is typed from the `@AccessControl` roles in the model; add a
runtime-only role with `declare module "@onno/ui" { interface OnnoRoles { SUPPORT: true } }`. A page's
`roles` hide it and its nav link; the server still enforces access on every call.

## Custom login page

`defineApp({ login: MyLogin })` replaces the framework's `/login` screen. The page receives
`LoginPageProps` (`methods` from `GET /api/ui/login`, a localized `?error=` message, and `from`)
and signs in with `useSignIn()`; every successful sign-in returns to `from`. Compose it from the
default parts or write anything else — it renders before sign-in, so it has no model and must not use
data hooks.

```tsx
import { Button, Input, LoginLayout, SsoButtons, useSignIn, type LoginPageProps } from "@onno/ui";

function CodeLogin({ methods, error }: LoginPageProps) {
  const signIn = useSignIn();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const run = (step: () => Promise<unknown>) => step().catch((e: Error) => setProblem(e.message));
  return (
    <LoginLayout error={problem ?? error}>
      <Input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Work e-mail" />
      {sent && <Input value={code} onChange={(e) => setCode(e.target.value)} placeholder="Code" />}
      <Button onClick={() => run(sent
        ? () => signIn.method("code", { email, code })            // POST /api/auth/login/code
        : () => signIn.challenge("code", { email }).then(() => setSent(true)))}>
        {sent ? "Sign in" : "Email me a code"}
      </Button>
      {methods && <SsoButtons providers={methods.providers} />}  {/* e.g. "Continue with Keycloak" */}
    </LoginLayout>
  );
}

defineApp({ login: CodeLogin, pages: [/* … */] });
```

`signIn.method`/`challenge` call an application `LoginMethod` bean on the server (see
[`onno-auth-starter`](../onno-auth-starter/README.md#application-login-methods)); `signIn.redirect`
starts an SSO provider (OIDC/Keycloak, a connector's own flow); `signIn.password` is the ordinary
password login. A single "Sign in with Keycloak" page is
`<LoginLayout><LoginButton primary label="Sign in" onClick={() => signIn.redirect(methods!.providers[0])} /></LoginLayout>`.

## Replace built-in components

The default UI and replacements share public controllers and bindings. Use `useRecordField` or
`RecordField` for custom editors, injected `RecordFormState` for a custom record screen, and
`useRecordForm` when creating a host from a fetched descriptor. Custom list renderers receive the
standard pagination, selection and scoped select-all callbacks. Metadata and control prop types,
`ListCell`, `RecordActions`, and the reference-picker dialog are public too.

See [the UI SDK replacement guide](../docs/UI_SDK.md) for supported boundaries and examples.

## Starter packages

Starters with screens ship them as packages next to their Java code, built on this one:
`@onno/print` (`PrintTemplates`), `@onno/crm` (`CrmInbox`, `CrmConnections`, `CrmContactDetails`,
`CrmConversationPage`) and `@onno/crm-channels` (`channelSetups`). An app adds the package to its
`package.json` and renders the components.

## Developing

Use the [intended repository development workflow](../docs/RUNNING.md#intended-development-workflow):
Vite with React Fast Refresh on port 5173 and a separate backend on 8091. Run `npm install` once
at the repository root. For frontend work with an existing backend, run only `npm run dev`.
For application Java/model changes, also use `npm run dev:backend` and `npm run dev:watch` in
separate terminals. Pages and shared `@onno/*` UI sources hot-reload without jar builds.

Run package checks from the repository root:

```bash
npm run typecheck --workspace=@onno/ui
npm run test:run --workspace=@onno/ui
npm run lint --workspace=@onno/ui
```

Gradle runs the same through `onno-web-starter`: `typecheckFrontend`, `testFrontend` and
`lintFrontend` (all part of `check`) and `buildFrontend`, which builds the default shell into that starter's jar. These are
verification and packaging steps, separate from the normal Vite edit loop.

Where things live is walked through in [docs/UI_TOUR.md](../docs/UI_TOUR.md); the visual rules
(tokens, controls, islands, mobile) are in [DESIGN.md](DESIGN.md). Adding something apps can import
means implementing it here, then one line in `src/index.ts` (and its types in `public-types.ts`).

List selection: the header checkbox and ⌘A/Ctrl+A select every record matching the current search, filters and workspace constraints, including unloaded rows and collapsed groups. Selection loads through the scoped feed without changing the visible window. A failed request preserves the previous selection; changing the query or clearing the selection cancels a pending select-all. Shift-range selection follows loaded rows.

## Linting

`eslint.config.js` lints this package with `@eslint/js` and `typescript-eslint` recommended,
`react-hooks/rules-of-hooks` (error) and `react-hooks/exhaustive-deps` (warning), plus the onno
rules in `eslint-plugin/`. Each onno rule's message states the fix:

| Rule | Catches |
| --- | --- |
| `onno/no-private-imports` | `@onno/ui/src/...` and other deep `@onno/*` paths, relative paths into `onno-ui/src`, and `lucide-react` (use `@onno/ui/icons`). |
| `onno/icon-component` | `icon: "truck"` or `icon="truck"`: icons are imported Lucide components. |
| `onno/no-raw-api-fetch` | `fetch("/api/...")`: use the typed hooks or `api` from `@onno/ui`. |
| `onno/notify-caught-error` | `toast.error(caught)` in a `catch`: report it with `notifyError` (one failed request, one toast). Package-internal. |

An app on `@onno/ui` gets the app-facing rules from `@onno/ui/eslint`. ESLint is an optional peer
dependency; the export imports nothing, and it does not configure a parser, so pair it with
`typescript-eslint`:

```js
// src/main/ui/eslint.config.js (npm i -D eslint typescript-eslint)
import tseslint from "typescript-eslint";
import onno from "@onno/ui/eslint";

export default tseslint.config(...tseslint.configs.recommended, onno.configs.app);
```

## Navigation composition

Sections and nested groups accept `actions`, an ordinary React node. Groups use the same `items`
shape recursively. Entity and page gates apply at every depth; denied links and empty groups are
removed. A group's `roles` gates the whole branch. Action components can use `useModel()` or
`useHasRole()` to decide whether to offer their own action; server authorization still applies.

```tsx
import { ChartColumn, ShoppingCart } from "@onno/ui/icons";
import { NewOrderButton } from "./components/NewOrderButton";

defineApp({
  pages: [{ path: "/sales-report", component: SalesReportPage }],
  navigation: [{
    title: "Sales",
    icon: ShoppingCart,
    actions: <NewOrderButton />,
    items: ["Orders", "Customers", {
      title: "Reports", icon: ChartColumn,
      items: ["/sales-report"],
    }],
  }],
});
```

Active links reveal their ancestor groups. Groups use keyboard-accessible disclosure buttons;
section actions are separate controls and do not toggle the group. The mobile More menu renders
the same tree and actions. Its bottom tabs use the first four leaf destinations, respecting
`mobileOrder`; groups themselves do not become tabs.

For a different layout, register `navigationComponent: AppNavigation`. The component receives
`NavigationProps`: the permission-filtered `navigation` tree, `activePath`, `mobile`, and optional
`header`/`footer` nodes containing the desktop home, account and notification controls. It replaces
the desktop navigation and the navigation body in the mobile More menu; the phone's bottom bar
and outer menu/account controls remain managed by the shell.

```tsx
import {
  Navigation, NavigationSection, NavigationGroup, NavigationLink,
  DefaultNavigation, type NavigationProps,
} from "@onno/ui";

export function AppNavigation({ navigation, header, footer }: NavigationProps) {
  return <Navigation>
    {header}
    <NavigationSection title="Shortcuts" actions={<NewOrderButton />}>
      <NavigationLink entity="Orders" />
      <NavigationGroup title="Reports">
        <NavigationLink path="/sales-report" label="Sales report" />
      </NavigationGroup>
    </NavigationSection>
    <DefaultNavigation sections={navigation} />
    {footer}
  </Navigation>;
}
```

`NavigationLink` resolves entity/page gates and uses the shell's normal dispatcher and dirty-form
guard. Custom group/link components share active-route expansion. `DefaultNavigation` renders an
already resolved tree. Put any React components between these blocks; no component registry or
`defineNavigation` wrapper is needed. The existing `shell.navigation.actions` outlet remains
available on the default desktop rail. See `example/src/main/ui/components/AppNavigation.tsx`.


## Dev proxy and consumer applications

The example's root development commands are documented in [Running & verifying](../docs/RUNNING.md).
Set `ONNO_BACKEND` to point its frontend to a backend other than port 8091. The preset proxies API,
auth, SSE and default `/ui/branding` assets.

A standalone consumer app runs `npm run dev` in its own `src/main/ui`. The preset defaults to
backend port 8080 unless configured; this repository's example explicitly defaults to 8091.

For framework contributors, the [component organization](DESIGN.md#component-organization) describes
the internal folders. Application imports continue to use the public `@onno/ui` entry point.
