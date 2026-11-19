# A tour of the UI code

How the UI works, in the order worth reading it.

## The idea

The Java model (catalogs, documents, registers) is the source of truth. The server tells the
browser what exists and what the signed-in user may do; it does not describe screens. The UI is
the npm package **`@onno/ui`**: a React shell that renders a default screen for everything in the
model. An app's UI is an ordinary Vite app that imports `@onno/ui` and declares only what differs.

```
example/src/main/ui  ──imports──▶  @onno/ui  (onno-ui)
        │                              ▲
        └──imports──▶ @onno/crm, @onno/print (the starters' screens) ──imports──┘
```

## 1. What an app writes — read this first

`example/src/main/ui/` . Nothing in it is framework
plumbing; it is the files any Vite + React app has, plus the app's own code:

| File | What it is |
| --- | --- |
| `package.json`, `vite.config.ts`, `tsconfig.json`, `index.html` | A normal Vite app. The config is one line: `plugins: [onno()]`. |
| `main.tsx` | One `defineApp({ login, entities, pages, navigation })` call. |
| `entities/*.ts` | How each entity presents, one `EntityCustomization<E>` per file (`Orders.ts`: columns, filters, row tint, field formats, command icons, comments) — applied to its default list and form, embedded lists and custom pages. |
| `pages/*.tsx` | The app's own pages, composed from `@onno/ui` components (`Page`, `Row`, `Stat`, `Chart`, `EntityList`) and the starters' (`CrmInbox`, `PrintTemplates`). |
| `components/*.tsx` | The app's own components. `OrderActivity.tsx` shows data access (`useRecords`); `BookTiles.tsx` a custom list view. |
| `model.gen.ts` | Generated on every build from the Java entities (git-ignored). Gives entity names, fields and enum values their types. |

Server behaviour stays in Java, as ordinary beans in `example/src/main/java/com/example/`:
`commands/OrderCommands` (what can be done with an order — on every surface: UI, REST, MCP),
`checks/ScheduleEventChecks` (live form checks), `pickers/EmployeeAvailability` (picker options).
How a command is offered (row button, header button, menu, icon) is the entity's `commands`
customization in `entities/*.ts`; so are the built-in record actions and the print starter's
`print-*` actions. The CRM inbox's conversation row menu reads the same customization of
`CrmConversations` (`useCommandPresentations`). The same file supplies the entity's imported Lucide `icon`, and `comments: true`
enables the record discussion.

## 2. The contract between server and browser

Both in `onno-web-starter/src/main/java/su/onno/web/`.

**The model and default screens** — `UiViewController.java` (`/api/ui`):

- `GET /api/ui/model` — every entity the user can read (`kind`, `logicalName`, `title`, `context`,
  `route`, `canWrite`; no icon — the client picks one), the readable information registers, the `@Constant` settings (for
  administrators), the user and roles. Navigation is built from this. Branding is not in it: it is
  served once, by the public `GET /api/branding` (`UiConfigController.java`), which the login screen
  and the shell both read; `GET /api/config` carries read-only mode, locale and chrome strings.
- `GET /api/ui/view/{catalogs|documents|registers}/{name}[/{id}]` — the default screen for a route,
  as data only: `{list}`, `{form}` or `{register}`, each that surface's descriptor. No page chrome,
  icons, tones or English titles: the client titles the page from the model and its messages, and
  draws the comments thread. A record form's `actions` are `{key, url[, title][, danger]}`
  (`unpost`, `duplicate`, `delete`, `openInList`, a module's `print-invoice`) and its document
  `canPost`; field metadata carries names, types, refs and constraints, never order/width/widget.
  `ViewDescriptors.java` builds list and register descriptors; `ResolvedMetadataService.java` the
  field metadata forms use.
- Commands answer `{refresh, feedback}`; feedback is meaning only (`severity`, `title`, `message`,
  `details`, plus `fieldErrors`/`formErrors` on a 422 rejection). `lib/command-feedback.tsx` decides
  display: short success/info/warning → toast, errors and details → the dialog
  `CommandFeedbackProvider` holds (components reach it with `useCommandFeedback()`), a rejection in
  an open input form → inline in that form.

**Data** — the generic REST API:

- Reads: `ListDataController` (`/api/list/...`, paged lists, groups, aggregates),
  `GenericCatalogController`, `GenericDocumentController`, `GenericRegisterController`; the SQL is
  in `CatalogQueryService`, `DocumentQueryService`, `RegisterQueryService`.
- Writes: `CatalogCommandService`, `DocumentCommandService` (hooks, rules, posting).
- Access: `AccessService` (roles) and `RecordAccess` (row-level policies) guard both.
- Live updates: `EventPublisher` → `GET /api/events` (SSE).

**Serving the UI** — `SpaIndexHtml`, `UiAutoConfiguration`: the app's own build if its jar has one
(`classpath:/onno-app/`), else the default shell from the starter jar (`classpath:/static/ui/`).

## 3. The package `@onno/ui`

`onno-ui/`. Start at the public entry, then follow it inward:

| File | Job |
| --- | --- |
| `src/index.ts` | **Everything an app can import**, in one file: `defineApp`, page blocks, widgets, controls, data hooks, formatting. If it's not here, it's internal. |
| `src/public-types.ts` | The typed surface: `EntityName`, `RecordOf<E>`, `FieldOf<E>`, `EntityCustomization<E>`, widget props, `AppDefinition`. `model.gen.ts` fills in `OnnoEntities`. |
| `vite-preset.js` | `onno()` for an app's `vite.config.ts`: React, Tailwind with the shell's theme, dev proxy to the backend, output directory for Gradle. |
| `src/start.tsx` | `defineApp(app)`: renders `<App app={app} />`. That's the whole entry. |
| `src/lib/app-config.tsx` | `AppConfigProvider` puts the app's declaration in React context at the root; hooks read it below: `usePageRoute`, `useNavigation`, `useCustomizedList`, `useCustomizedForm`. |
| `src/App.tsx` → `src/views/app-shell.tsx` | Auth, then the chrome: model fetch, navigation, panes (list beside record), mobile layout. `app-shell.tsx` (2,000 lines) is the one file most worth splitting later. |
| `src/views/content-pane.tsx` | One pane: an app page for the route, or the server's default view. |
| `src/views/route-view.tsx` | Renders a default view (list, record form + comments, register), applying the app's entity customizations. |
| `src/components/lists/entity-list.tsx` (3,800 lines) | The list: columns, search, filters, grouping, selection, actions. |
| `src/components/entities/record-form.tsx` (1,500 lines) | The record form. |
| `src/components/pages/page-blocks.tsx` | `Page`, `Row`, `DefaultView`, `EntityList` — what pages are composed from; the data components (`Stat`, `Chart`, …) live under `src/components/widgets/`. |
| `src/lib/query.ts` | Data hooks on TanStack Query (`useRecords`, `useCreateRecord`, `useBalance`…) and SSE-driven invalidation. |
| `src/lib/api.ts` | The REST client. |

**How state flows.** The app's declaration goes *down the tree* in context, never into module
state, so a component's behaviour depends only on its props and the providers above it:

- Configuration (pages, navigation, entity customizations): `AppConfigProvider` context.
- Server data (the model, records, aggregates): TanStack Query's cache, refreshed by the server's
  live events. The shell loads the model (`lib/model-store.ts`); everything reads it with `useModel()`.
  The cache is the one live-update channel: `startQueryInvalidation` turns each SSE change into
  `invalidateEntity(entity, ids?)`, which refetches the entity's queries and bumps its change signal.
  A component that keeps its own view of the data (the list's keyset window, a form's draft) hears it
  through `useEntityChanges(entity, ids => …)`; a command's `refresh` calls the same
  `invalidateEntity`. Events that aren't entity data (comments, presence, tasks, notifications) are
  read with `useUiEvents(listener, filter)` over the same shared stream.
- Per-session state (notifications, presence, dirty forms, the quick-create hand-off): providers
  mounted in `App.tsx`. Opening and closing screens goes through `openRoute`/`closeRoute`
  (`lib/navigation.ts`), which the shell alone listens to.
- Shared page state (theme, messages, the page's time range): their providers.
- A component's own UI state: inside it, and *controllable* where a page may care.
  `EntityList` works like an `<input>`: `defaultGroupBy`/`defaultSort`/`defaultView` set where it
  starts; `groupBy`+`onGroupByChange`, `sort`, `view`, `search`, `selection` hand that state to the
  page (see the example's Sales Ops page, whose side panel totals the selected orders).

`lib/entity-customizations.ts` and `lib/routes.ts` are pure functions (apply a
customization to a descriptor, match a path); `lib/app-model.ts` builds the shell from the model.
Descriptors are plain objects that may carry the app's functions and components (`rowStyle`,
`custom.component`, form `panels`): the list and form render what they are handed.

There is no name → component registry: the server never names a component, and nothing registers
itself on import. Extension outlets (`ExtensionSlot`: a form's actions, a list's context menu, the
CRM chat header) render the app's `defineApp({ extensions })` contributions from context. The
`/login` route renders `defineApp({ login })` when given (else `DefaultLogin`), handing it
`LoginPageProps`; `useSignIn()` (`components/auth/sign-in.tsx`) is the one sign-in path for both,
covering password, application `LoginMethod` beans (`/api/auth/login/{method}`) and SSO redirects. Every
component is a plain one: the widgets (`Stat`, `Chart`, `Calendar`, …, one file each in `src/components/`) take
typed props and read data through `useEntitySource(entity)` and the TanStack hooks in
`lib/aggregate-data.ts`.

**Typed vocabulary.** Everything an app hands `@onno/ui` is a typed value, never a string the UI
parses: formats are `{ money: "USD" }`, `{ number: { decimals: 0 } }`, `{ date: "medium" }`; inputs
are one name per control (`"textarea"`, `"switch"`, `"image"`, `"color"`, `"map"`, …); form widths
are `"full" | "half"`; `Row widths` are flex weights or `{ px }`; icons are imported Lucide components; list subtotals and groupings reuse the widget shapes (`{ sum: "total" }`, `{ date: "month" }`);
which records — for lists, `useRecords`, measures and reference pickers alike — is a `where` object
sent to the server as JSON. Entities are named one way, by logical name (`"Orders"`). With the
generated model all of it is checked per entity; `tests/types/` pins that down with type-level
tests (`tsconfig.types.json`).

### The `onno://` link protocol

`onno://` links name what to open, not how a client shows it; the web shell and future mobile
clients share them (notifications, QR codes, a widget's "open" action):

| Link | Opens |
| --- | --- |
| `onno://documents/orders/<id>` | a record |
| `onno://documents/orders/new?customer=<id>` | a new record, pre-filled (ids for references and enums, ISO dates) |
| `onno://documents/orders?where=<json>` | a list showing only the records `where` selects |
| `onno://ops`, `onno://` | an app page (`/ops`), home |

Build them with `links.record(entity, id)`, `links.newRecord(entity, prefill)`,
`links.list(entity, { where })` and `links.page(path)` (`lib/links.ts`) and open them with
`openRoute(link)` (or render a `<RouteLink to={link}>`, a real anchor that opens in the workspace).
A command result's `navigateTo` (from `CommandResult.open(ref)` / `navigate(route)`) goes through
the same dispatcher after its feedback and refresh; external and command URLs are ignored. The shell also handles a few action links of its own (`onno://command/…`,
`onno://delete/…`, `onno://logout`) in `app-shell.tsx`.

## 4. The starters' screens

Each starter with UI ships it as a package next to its Java code, in `src/main/ui`:

- `onno-print-starter/src/main/ui` → `@onno/print` (`<PrintTemplates />`)
- `onno-crm-starter/src/main/ui` → `@onno/crm` (`<CrmInbox workspace="support" list={…} />`,
  `<CrmConnections setups={…} />`, `CrmContactDetails`, `CrmConversationPage`)
- `onno-crm-channels-starter/src/main/ui` → `@onno/crm-channels` (`channelSetups`, the bundled
  channels' setup forms for `CrmConnections`)

An app that wants those screens adds the package to its `package.json` and renders its components.
The Java beans (a `CrmInboxWorkspace`) say what exists and who may use it; the props say how it looks.

## 5. Build and repository layout

- The root `package.json` is an npm workspace over `@onno/ui`, the starters' packages and the
  example app: one `npm install`, one `node_modules`, the packages symlinked.
- `onno-ui-gradle-plugin` (`id("su.onno.ui")`) is the only Gradle glue: `generateUiModel` writes
  `model.gen.ts`, `buildUi` runs `vite build` into the jar's `onno-app/`, `onnoUiInit` seeds (from the plugin's
  `resources/su/onno/ui/template` file tree, login and home wired explicitly) a new
  app. About 150 lines.
- The starter's own build (`onno-web-starter/build.gradle.kts`) builds the default shell with the
  same preset and runs the frontend tests.
- `onno-ui/eslint-plugin/` holds the onno lint rules (exported as `@onno/ui/eslint`, with
  `configs.app` for applications); `onno-ui/eslint.config.js` wires them, with `typescript-eslint`
  and the rules of hooks, into `npm run lint` and the Gradle `lintFrontend` task that `check` runs.

## 6. Follow one request

Opening `/ui/documents/orders`:

1. The server returns the app's `index.html`; `main.tsx` runs `defineApp`, which mounts the shell.
2. `app-shell.tsx` loads `/api/ui/model`, so the sidebar shows "Orders".
3. `content-pane.tsx` finds no registered list page for Orders, so it fetches
   `/api/ui/view/documents/orders` for the default screen.
4. `UiViewController` checks access and returns `{list}`, the list descriptor.
5. The app's `entities/Orders.ts` customization supplies columns, filters and row tint
   (`useCustomizedList`); `EntityListView`'s grid pages through `/api/list/documents/Orders`. An SSE event for
   Orders invalidates it in the query cache: the list patches the changed rows or reloads its window.

Opening `/ui/ops` instead stops at step 3: the app's pages (in context) have `SalesOpsPage`.

## 7. Where to change things

| To… | Edit |
| --- | --- |
| add a page or nav entry in an app | `src/main/ui/main.tsx` |
| change how an entity lists or edits | `src/main/ui/entities/<Entity>.ts`; a `pages/` component only when the layout differs |
| add something apps can import | its implementation in `@onno/ui`, then one line in `src/index.ts` |
| add an option to entity customizations | `lib/entity-customizations.ts` + `public-types.ts` |
| let pages control more list state | `ListStateProps` in `entity-list.tsx` (`useControllable`), then `EntityList` in `page-blocks.tsx` |
| add a typed prop to a widget | `public-types.ts` only (props pass through as widget options) |
| change what the model exposes | `UiViewController.model` |
| change generated TypeScript | `onno-framework-starter/.../codegen/TypeScriptModelGenerator.java` |
| add a command, a live check or picker options | a `Commands` / `Checks` / `PickerOptions` bean in the app |
| place or icon a command (or a built-in like `delete`, or `print-invoice`) | the entity's `commands` customization: `{ advance: { placement: "row", icon: ArrowRight } }` |
| change how command feedback shows (toast vs dialog) | `feedbackNeedsDialog` in `lib/command-feedback.tsx` |
| change the calendar/timeline list views | `components/lists/list-schedule-view.tsx`, `lib/schedule.ts` |
| change the record history panel | `components/collaboration/record-history.tsx` |
| change the period-close admin screen | `components/widgets/period-close.tsx` |
| change time-of-day / instant editing and the display zone | `components/fields/time-picker.tsx`, `instant-picker.tsx`, `lib/time-zone.ts` |

## 8. Run and test

The intended workflow is Vite hot reload plus a separate backend. Run from the repository root:

```bash
npm install                     # once
npm run dev:backend             # terminal 1: backend on 8091, Spring DevTools
npm run dev:watch               # terminal 2: application Java + model.gen.ts
npm run dev                     # terminal 3: http://localhost:5173
```

For UI-only work, reuse the existing backend and run only `npm run dev`. Pages, components, styles
and linked framework UI sources update without rebuilding jars. Use the Vite URL, not the
backend's built UI snapshot. The watcher handles application Java/model changes; dependency jars
may require a backend restart. See [the canonical workflow](RUNNING.md#intended-development-workflow).

Package verification is separate:

```bash
npm run typecheck --workspace=@onno/ui
npm run test:run --workspace=@onno/ui
./gradlew :onno-web-starter:buildFrontend
```

## Composing entity pages

How an entity presents is one `EntityCustomization<E>` per entity in `entities/` —
`export const books = { … } satisfies EntityCustomization<"Books">` — registered with
`defineApp({ entities })`. It holds the `icon`, `list` (columns, filters, grouping, custom views),
`form` (`fields`: the header fields shown, in order), `fields` (presentation only, keyed by field;
its key order selects and orders nothing), `rowStyle`, `commands`, `recordPanels`, `comments` and `relations` (a join entity such as `Supplier Offers` edited inline from each side and saved with the record),
and applies wherever the entity shows. Icons are imported components from `@onno/ui/icons`.

Relation editors read the complete join-row set through cursor pagination before enabling edits.
They preserve the owner filter and configured sort on every page. A failed or invalid continuation
keeps the editor unavailable for replacement writes: saving a partial set would delete unseen links.
The lower-level `api.listCatalogPage` and `api.listDocumentsPage` accept `sort` alongside `cursor`
and `limit`; bounded `listCatalog`/`listDocuments` reads still return a single window.

Register a list or record component by logical name in `defineApp({ pages })` only when the layout
differs from the default: `{ list: "Suppliers", component: SuppliersPage }` puts a stat above
`<EntityList list={list} />`; `{ record: "Stock Receipts", component: StockReceiptPage }` composes
`RecordPage`, `<RecordFields form={form} fields={["supplier"]} />`, `RecordLines` and a custom notes
editor in a side column. Those blocks take page-only `overrides`. `form.values` and `form.set` share
the standard validation, save/post, dirty guard and refresh pipeline. Field names, values, sections,
and columns are checked against the generated model. See [the package examples](../onno-ui/README.md),
[entities/Orders.ts](../example/src/main/ui/entities/Orders.ts) and
[StockReceiptPage.tsx](../example/src/main/ui/pages/StockReceiptPage.tsx). The desktop rail has a
`shell.navigation.actions` extension outlet for custom buttons.

List selection: the header checkbox and ⌘A/Ctrl+A select every record matching the current search, filters and workspace constraints, including unloaded rows and collapsed groups. Selection loads through the scoped feed without changing the visible window. A failed request preserves the previous selection; changing the query or clearing the selection cancels a pending select-all. Shift-range selection follows loaded rows.


Navigation sections and nested groups accept React `actions` and recursive `items`; `roles`
filters branches and empty groups disappear. Desktop active routes reveal ancestor groups; the
mobile More menu uses the same tree, while bottom tabs derive from its leaf destinations.
Register `navigationComponent: AppNavigation` for a custom desktop/menu layout. Compose
`Navigation`, `NavigationSection`, `NavigationGroup`, `NavigationLink`, and `DefaultNavigation`;
`NavigationProps` supplies the resolved tree, active path, mobile flag and optional desktop
header/footer. Custom links share model/page gates and the shell dispatcher. See
[the UI package](../onno-ui/README.md) for examples.


## Replacing built-in components

The public SDK exposes the same `useRecordForm` controller used by the default record host,
`useRecordField`/`RecordField` bindings, metadata and control prop types, `RecordActions`,
`ListCell`, and reference-picker blocks. Custom list renderers receive shared selection and
select-all callbacks in addition to the existing feed/pagination props. See [UI_SDK.md](UI_SDK.md)
for the supported replacement boundaries. New extension work should share controllers between
standard and custom views rather than duplicate business or lifecycle behavior.
