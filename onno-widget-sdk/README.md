# @onno/widget-sdk

Author custom React widgets for the [onno-framework](https://github.com/onno-erp/onno-framework)
server-driven UI. Write a component, register it by the type string your server-side
`.widget(...).type("…")` declaration uses, and the onno SPA renders it in place of the built-in
widget it has no equivalent for.

You normally do **not** install this package yourself. Apply the `su.onno.widgets` Gradle plugin in
your Java app — it bundles this SDK, compiles your `src/main/widgets/*.tsx` with a managed Node +
esbuild toolchain, and ships the result into the app. See the
[onno-ui-starter README](https://github.com/onno-erp/onno-framework/blob/main/onno-ui-starter/README.md)
→ "Authoring a custom widget".

## Why the output has no React in it

The SDK's runtime bindings (`React`, hooks, `registerWidget`, `api`, `html`) resolve to the host
SPA's singletons on `window.onno`. The Gradle build aliases `react` / `react/jsx-runtime` to those
too. So your compiled widget is a ~1 KB ESM module that shares the host's exact React instance —
hooks, context, router, and theme all work, and there is no second React on the page.

## API

```tsx
import { registerWidget, useState, useEffect, api, html, type WidgetProps } from "@onno/widget-sdk";
```

- `registerWidget(type, Component, options?)` registers a dashboard widget.
  Pass `{ override: true }` to replace a built-in widget.
- `registerListRenderer(type, Component, options?)` registers the renderer for a
  custom list view configured with `list.custom("type")`. List renderers have
  their own registry, so they can reuse dashboard widget names. The component
  receives `ListRendererProps`, including `rows`, the `list` descriptor, and
  `open(row)` / `openUrl(row)` for opening records. If no renderer is registered,
  the list falls back to the default grid.
  A bundle built with an older SDK that registered list renderers or selection
  widgets through `registerWidget` still resolves, with a console warning; rebuild it.
- `React`, `useState`, `useEffect`, `useMemo`, `useRef`, `useCallback`, `useReducer`, `useContext`,
  `useLayoutEffect` — the host React and its hooks (you may equally `import ... from "react"`).
- `useWidgetUpdates(widget, refresh, options?)` — refetch when the widget's bound entity changes,
  with matching, burst coalescing, and cleanup built in. It reuses the host's shared SSE stream.
- `events.subscribe(listener, filter?)` / `subscribeUiEvents(...)` / `useUiEvents(...)` — lower-level
  access to the same shared stream for widgets with unusual matching needs. Never open a separate
  `EventSource` from a widget.
- `api` — a read-only REST client (`listCatalog`, `getCatalogItem`, `listDocuments`, `getDocument`,
  `getBalance`, `getTurnover`, `getMovements`, plus the `getJson` escape hatch below). List calls
  return one bounded keyset window. Same-origin, session + CSRF handled by the host. No writes. Each
  call resolves to the widget's own deep copy of the response, so mutating it never affects the host.
- **UI primitives** — the host's real design-system controls, so a widget matches the product instead
  of shipping hand-rolled lookalikes. Import them **by name** (`import { DatePicker, Select, Button }
  from "@onno/widget-sdk"`) or off the `ui` object (`ui.Select`). Curated subset — `Button`, `Badge`,
  `Input`, `Label`, `Textarea`, `Checkbox`, `Switch`, `Segmented`, `DatePicker`, `RefSelect`, `Card`
  (+ `CardHeader`/`CardTitle`/`CardDescription`/`CardContent`), `Popover`
  (+ `PopoverTrigger`/`PopoverContent`), and `Select`
  (+ `SelectTrigger`/`SelectValue`/`SelectContent`/`SelectItem`/`SelectGroup`). They resolve to the
  host's singletons at runtime (shared React instance, host-emitted classes). Requires host contract
  v2+.
- `html` — `htm` bound to the host `React.createElement`, for JSX-free markup.
- `navigate`, `openRecord`, and `Link` — [workspace navigation](#in-app-navigation).
- `WidgetProps` — `{ widget: DashboardWidgetMeta }`, the props every widget receives. Read
  `widget.entityName` / `widget.entityType` for the bound entity and `widget.extraConfig` for your
  server-side `.config(key, value)` values. When the component is embedded through
  `EntityView.detail(DetailSpec)`, `widget.record` additionally carries the saved record's route
  `kind`, logical `name`, `id`, already-loaded `data`, and `readOnly` state.
- **Navigation** — dispatch an `onno:action` event with an `onno://` URL. Entity routes use the
  snake_case route name, not the logical name REST uses: for `@Document(name = "SalesOrders")`,

  ```ts
  window.dispatchEvent(new CustomEvent("onno:action", {
    detail: `onno://documents/sales_orders/${id}`,
  }));
  ```

  A link with the REST spelling (`SalesOrders`) still opens, but the record gets no live updates
  and no localized tab title.

## In-app navigation

Use `Link` for a clickable destination and `navigate` or `openRecord` for programmatic navigation,
for example after an operation completes. These exports require host contract v8 and use the same
tab and pane rules as built-in UI actions.

```tsx
import { Button, Link, navigate, openRecord } from "@onno/widget-sdk";

function ReportActions({ orderId }: { orderId: string }) {
  return (
    <>
      <Link to="/reports?period=month">Monthly report</Link>
      <Button onClick={() => navigate("/settings")}>Settings</Button>
      <Button onClick={() => openRecord("documents", "orders", orderId)}>Open order</Button>
    </>
  );
}
```

Pass application paths starting with `/` and omit the configured UI prefix (for example, `/ui`).
Use `"catalogs"` or `"documents"` with the logical entity name and record ID for `openRecord`.
`Link` adds the UI prefix to its real `href`, so copy-link, Ctrl/⌘-click and middle-click still
work. Use `navigate("/reports", { newTab: true })` to open a separate browser tab explicitly.
For navigation within the current workspace, use these exports so its open tabs and drafts survive.

## Styling

The `su.onno.widgets` Gradle plugin runs **Tailwind over your widget sources** and ships the result as
`<group>-<artifact>-widgets.css`, which the host injects at boot — before its own stylesheet, so these unscoped
utilities can never out-cascade the host's responsive variants on host markup. So Tailwind utility
classes in your widget's own
markup — including uncommon ones (`border-l`) and arbitrary values (`-left-[5px]`) the host never
emits — now produce real CSS. The stylesheet is **utilities-only with preflight off** and carries the
host's design tokens (`bg-primary`, `text-muted-foreground`, `rounded-pill`/`rounded-field`/
`rounded-panel`, which resolve against the host's runtime CSS variables), so it matches the product
and light/dark both work. No config on your side.

Radius mapping: `rounded-pill` is a 9999px capsule for compact actions/chips/badges;
`rounded-field` is for inputs, rows, and compact event blocks; `rounded-panel` is for cards and
bounded surfaces. The older `rounded-control` and `rounded-card` names remain aliases for
`rounded-pill` and `rounded-panel`. Do not use the pill/control tier on grids, tables, schedule
lanes, generic rows, or large containers.

Two caveats remain:

- **Only `src/main/widgets` is scanned.** Class names in files outside that dir, or built by string
  concatenation at runtime (`` `text-${color}` ``), aren't seen by Tailwind — write class names as
  literals, or use inline `style` for the truly dynamic bits.
- **For dynamic colors**, inline `style` with the host's HSL variables still works:
  `hsl(var(--primary))`, `hsl(var(--border))`, `hsl(var(--muted-foreground))` — they follow the theme.

For interactive controls (dropdowns, toggles, buttons), prefer the host `ui` primitives over rolling
your own — you get the exact product control, keyboard nav, mobile drawers and all:

```tsx
import { Segmented, useState } from "@onno/widget-sdk"; // named — or destructure from `ui`

function ViewSwitch() {
  const [view, setView] = useState("day");
  return (
    <Segmented
      value={view}
      onChange={setView}
      options={[{ value: "day", label: "Day" }, { value: "week", label: "Week" }]}
    />
  );
}
```

## Live updates

A widget does **not** auto-refresh merely because it called `api`, but live behavior is one hook:
use the same stable loader for the initial fetch and `useWidgetUpdates`. The hook matches
`created`/`updated`/`deleted`/`posted`/`unposted`/`changed` to `widget.entityType` and
`widget.entityName`, understands register wildcard invalidations, coalesces bursts, and cleans up on
unmount.

```tsx
import { useEffect } from "@onno/widget-sdk";

const load = useCallback(async () => {
  setRows(await api.listDocuments(widget.entityName));
}, [widget.entityName]);
useEffect(() => { void load(); }, [load]);
useWidgetUpdates(widget, load);
```

The host owns the authenticated `/api/events` transport, reconnect policy, session-expiry handling,
and Web Locks/BroadcastChannel fan-out, so every widget and tab shares one connection. Do not use
`new EventSource(...)` in a custom widget. If the widget depends on several entities or an event such
as `tasks-changed`, subscribe through `events.subscribe(listener, filter?)` or `useUiEvents(...)`.

## Example

```tsx
import {
  registerWidget, useCallback, useEffect, useState, useWidgetUpdates, api, type WidgetProps
} from "@onno/widget-sdk";

function EventLog({ widget }: WidgetProps) {
  const [rows, setRows] = useState<any[]>([]);
  const load = useCallback(async () => setRows(await api.listDocuments(widget.entityName)), [widget.entityName]);
  useEffect(() => { void load(); }, [load]);
  useWidgetUpdates(widget, load);
  return (
    <ul className="text-sm text-foreground">
      {rows.map((r) => <li key={String(r.id)}>{String(r.date)} — {String(r.number)}</li>)}
    </ul>
  );
}
registerWidget("eventLog", EventLog);
```

Server side:

```java
b.widget("Recent activity").type("eventLog").document(Payment.class)
    .config("amountField", "amount").config("currency", "EUR");
```

Plugin JS runs first-party in the app origin with the user's full session — author it as trusted
code. Licensed Apache-2.0.

### Action notifications

Import `toast` from `@onno/widget-sdk` to use the host's shared notification stack:
`toast.success("Telegram connection verified.")`. `error`, `info`, and `warning` also accept
a message string. This requires an updated host exposing `window.onno.toast`; older hosts
continue supporting existing widgets, but cannot display notifications through this API.

`OptionsFacet` is available from `@onno/widget-sdk`: the same filter chip used by entity lists.
Pass `label`, `options` (`value`, `label`, optional `color`/`avatarUrl`), `multi`, `selected: string[]`,
and `onChange`. Empty selection means no constraint; the consuming widget owns data filtering.

`EntityListWidget` is also exposed through the widget SDK for scoped operational lists. Supply the
host list descriptor (`list`, including a scoped `feed`), optional `renderer` component for the
custom body, and optional numeric `refreshKey` to trigger a soft live refresh. The host retains
ownership of the entire standard list header, filtering, sorting and table/custom-view switching.

Use `registerListSelection(type, Component)` with `ListSelectionProps` (`ids`, `complete`)
to provide an authored `ListSpec.selectionWidget(type)` toolbar action for selected rows.

### Custom CRM chat message bodies

Apps can register React message components from `src/main/widgets/*.tsx` with
`registerChatMessageRenderer` in `@onno/widget-sdk` (UI host v4+). CRM retains the
avatar, author, timestamp, delivery status, and retry controls. Internal notes and
system events retain their existing renderers. This is a display extension; it does
not add rich-message transport or a new stored payload format.

```tsx
import { registerChatMessageRenderer, type ChatMessageRendererProps } from "@onno/widget-sdk";

function TelegramBody({ message }: ChatMessageRendererProps) {
  return <div className="whitespace-pre-wrap text-sm">{message.body}</div>;
}

registerChatMessageRenderer({
  id: "myapp.telegramBody",
  priority: 10,
  matches: message => message.channel === "TELEGRAM",
  component: TelegramBody,
});
```

The component receives read-only `message` data and a `fallback` React node. It may
use SDK hooks/primitives and load application data using the message ID. Higher
priority matches win; ties sort by ID. Registration replaces the same ID and returns
an unregister callback. Late registrations update visible messages. Missing matches,
failed predicates, and render errors preserve text display (failed predicates are
skipped). Never render untrusted message bodies as raw HTML. Interactive components
must call authenticated application commands for changes; registration grants no
additional backend permissions.

The SDK also exposes host `ContextMenuContent` and `ContextMenuItem` primitives for pointer-positioned and keyboard-invoked widget menus.

### Structured record tags

`EntityTags` is a host/SDK component with `{ kind: "catalogs" | "documents", name, id,
readOnly? }`. It displays colored chips and supports selecting existing catalog tags and adding/removing
individual assignments. Use `<EntityTags kind="catalogs" name="customers" id={id} />` in
widgets, or `detail.widget("Tags").type("entityTags")` in an EntityView's detail configuration.

The UI starter stores stable tag IDs, names, colors, and record assignments in `onno_tags` and
`onno_tag_links`. Libraries are scoped to the canonical entity kind/name, so tags can be reused
across its records. Case-insensitive names reuse a definition; removing a chip only removes that
record's assignment. Standard entity read/write permissions apply. Optional `TagAccessPolicy`
beans add record-level checks; the CRM uses its workspace customer permissions.

Bind an ordinary application tag catalog with a `TagCatalog` bean (`scope`, `list`, and an idempotent
legacy `importTag`). The catalog owns names, colors, permissions, and soft deletion; the tagging
service owns record assignments. CRM provides `CrmTags`, available under Configuration → Tags.
The Add tag picker only searches/selects existing catalog entries. Legacy IDs and assignments
survive migration; catalog edits update every assigned chip and deleted tags leave the picker.

Internal CRM notes keep the inbox composer and use a compact inline reference picker: `@` selects people, `#` selects catalog/document records, and pasted local record links become references. Notes remain ordinary Onno comments, including permission checks and mention notifications. The host `CommentBody` renderer accepts a stored `body` and resolves links for the current viewer. CRM reply drafts and internal-note drafts stay separate.

`ContextMenuSub` and `EntityTagMenu` expose a catalog-tag checkbox flyout for custom widgets. Host context menus automatically dismiss other root context menus when opened.

### UI extension outlets

Host contract v5 adds `registerExtension` and `ExtensionSlot`: opt-in contributions to page/list/form
controls, entity and CRM context menus, composer tools, and right-panel sections. Contributions
receive scoped context and supported host callbacks, have deterministic ordering and isolated
render failures, and can be replaced/unregistered without modifying the host. Backend authorization
continues to govern commands. Timeline bodies retain `registerChatMessageRenderer`.
See the UI contributions section of `docs/EXTENDING.md` for outlet names, context, and examples.

`Badge` accepts an optional configured hex `color`, using the shared `enumPillStyle` contrast calculation. CRM contact stages and entity tags use this filled pill treatment; unknown colors fall back to the semantic badge variant.

Widgets can anchor a shared `PopoverContent` to an existing field using SDK `PopoverAnchor` with `asChild`. Use this for input-driven suggestion popovers without adding a separate trigger button; preserve input focus via the popover autofocus callbacks. CRM controls use SDK buttons, labels, inputs, selects, and popovers.

### Pagination in custom list panes

Custom list renderers receive optional `hasMore`, `loadingMore`, `loadMoreFailed` and `loadMore()`
props. A renderer with its own scrolling pane must call `loadMore()` near that pane's bottom and
provide an accessible load-more/retry button (disabled while loading). Stop automatic retries after
`loadMoreFailed`; an explicit retry uses the same cursor. The host retains the scoped feed, filters,
query generation, row deduplication and loading guard. Do not fetch the entire catalog in a renderer.

While a next window loads, the host shows its own "Loading more…" status below the renderer. A
renderer that draws that feedback inside its own pane opts out at registration:

```tsx
registerListRenderer("inbox", Inbox, { ownsLoadingMore: true });
```

Only the `EntityListWidget` props documented here (`list`, `renderer`, `toolbarPlacement`) are SDK
contract. Others the host accepts — `initialPage`, `toolbarFooter`, `toolbarOptions.filterSidebar`
and similar — are host-internal hooks for first-party workspaces and may change without notice.

### Dates in the app's locale

`Intl` with an `undefined` locale formats in the browser's language, so an `en-US` browser writes
"Nov 6" in an app configured for `en-GB`. Pass `useLocale()` (in a component, re-rendering when
`/api/config` lands) or `locale()` (anywhere else) instead: it returns `onno.ui.locale`, and
undefined (the browser's locale) before config arrives, when none is set, or on a host older than
contract v7.

```tsx
import { useLocale } from "@onno/widget-sdk";
const when = new Intl.DateTimeFormat(useLocale(), { day: "numeric", month: "short" }).format(date);
```

### Reference pickers

Import `RefSelect` to pick a catalog or document record. It is the same control as a generated
form's reference field: a server-side typeahead (the catalog never loads whole), recent picks first,
"+ New" opening the target's new-record form, and "Show all…" opening the target's full list —
columns, filters, folders — in a picker dialog. Requires host contract v7.

```tsx
import { Label, RefSelect, useState } from "@onno/widget-sdk";

function SupplierField({ eventId }: { eventId: string }) {
  const [supplier, setSupplier] = useState<string | null>(null);
  return <>
    <Label htmlFor="supplier">Supplier</Label>
    <RefSelect id="supplier" targetName="Contacts" fieldLabel="Supplier" placeholder="Not chosen yet"
      value={supplier} onChange={setSupplier} clearable allowCreate={false}
      filter="active = true" optionDecorator="com.example.ui.SupplierOptions"
      optionContext={{ fieldPath: "vendor", formValues: { wedding: eventId } }} />
  </>;
}
```

`value` is the record id and clearing emits `null`. `filter` is a fixed WidgetFilter predicate
applied to the dropdown and the dialog alike. `optionDecorator` names a server `RefOptionDecorator`
bean by class; it receives `optionContext` and can badge, disable or hide options. `allowCreate`,
`showAll`, `clearable` and `openButton` toggle the "+ New", "Show all…", clear and open-record
affordances (on, on, off, off by default). `className` merges over the trigger, so a dense row can
render it as a compact chip; the dropdown keeps a readable minimum width. Pass `valueLabel`
when the widget already has the picked record's name: it shows at once, and stays for a user who
may not read the target catalog.

### Date and time fields

Import `ColorPicker` for `#RRGGBB` colours. It is the same control as the `color` field widget: a hex
field plus a picker popover. `compact` shows only a round swatch (the hex field moves into the
popover) for dense rows; `onCommit` fires once with the final colour when the popover closes, so a
row can save without a request per drag step:

```tsx
<ColorPicker compact aria-label="Stage colour" value={color} onChange={setColor} onCommit={save} />
```

Import `DatePicker` for dates and `DateTimePicker` for local dates with 24-hour time:

```tsx
import { DatePicker, DateTimePicker, useState } from "@onno/widget-sdk";

function ScheduleFields() {
  const [day, setDay] = useState("");
  const [startsAt, setStartsAt] = useState("");
  return <>
    <DatePicker aria-label="Day" value={day} onChange={setDay} />
    <DateTimePicker aria-label="Starts at" value={startsAt} onChange={setStartsAt} />
  </>;
}
```

Values are `YYYY-MM-DD` and `YYYY-MM-DDTHH:mm`, respectively; clearing emits an empty string.
Date/time values are local wall times without timezone conversion. A date-only initial value
starts at midnight; seconds are omitted. Both support `isDisabled`, `isReadOnly`,
`isRequired`, `isInvalid`, and accessible labels. Generated `LocalDateTime` fields use
`DateTimePicker`; the legacy `DatePicker includeTime` prop remains compatible.
The host must include the `DateTimePicker` export to use it from a custom widget.

Custom list renderers receive optional `ListRendererProps.total`, the server count matching the
current search and filters, independently of how many pages are loaded. A null/omitted total means
unknown. The inbox uses this count in its main chat header; folder counts remain scoped to loaded
rows and are labeled as the current view.

### Page navigation motion

`usePageSlide(ref, activeKey, enabled?, onFinish?, motion?)` applies the host's shared horizontal
navigation slide to direct children marked `data-slide-page="key"`. Keep both pages
mounted and give them their final positions; the hook animates the incoming page to
zero and the outgoing page toward its sibling. DOM order defines forward/back.
Initial render, switching `enabled` (for responsive layouts), and reduced-motion
preferences snap without animation. The cleanup cancels interrupted slides.
`onFinish` can release retained outgoing pages. Timing comes from `--page-slide-dur`
and `--page-slide-ease` on the container. Older hosts fall back to no animation.

The optional `motion` is `"slide"` by default; `"fade"` uses a 120ms opacity transition
without horizontal travel, used by the mobile shell when switching top-level sections.

### Configurable workspace toolbars

`WorkspaceToolbar` is a domain-independent layout primitive. Pass `items` containing a
stable `id` and a React `content` node. The caller still owns permissions, enabled state,
handlers, labels and data. `layout` selects and orders IDs in `start`, `center`, `end`,
`secondary`, or `overflow`; `hidden` suppresses IDs everywhere. Unlisted IDs stay hidden,
unknown IDs are ignored, and duplicate IDs render once (first region wins).
`compactLayout` overrides individual arrays when `compact` is true. To move an item,
remove it from its old region as well as adding it to the new region. An empty array
hides that region. `balanced` keeps a centered title between equal-width edge regions.
The overflow disclosure hosts ordinary controls, including disabled buttons.

```tsx
<WorkspaceToolbar compact={narrow}
  items={[
    {id: "title", content: <h2>Orders</h2>},
    {id: "save", content: <Button disabled={!canSave} onClick={save}>Save</Button>},
    {id: "export", content: canExport ? <Button onClick={exportRows}>Export</Button> : null},
  ]}
  layout={{start: ["title"], end: ["save", "export"]}}
  compactLayout={{end: ["save"], overflow: ["export"]}} />
```

`fit` keeps a single-row (non-`compact`, non-`balanced`) toolbar inside its width. When the items
overflow, the toolbar first hides every descendant marked `data-toolbar-label` — wrap a button's
optional text in `<span data-toolbar-label>` and keep its icon and `aria-label` outside, so the control
stays identifiable — and, if it still overflows, applies `narrowLayout` (e.g. moving tools into
`secondary` or `overflow`):

```tsx
<WorkspaceToolbar fit narrowLayout={{start: ["title"], overflow: ["log"]}}
  items={[
    {id: "title", content: <h2>Orders</h2>},
    {id: "log", content: <Button size="toolbar" aria-label="Log call"><Phone className="size-4" /><span data-toolbar-label>Log call</span></Button>},
  ]}
  layout={{start: ["title"], end: ["log"]}} />
```

`EntityListWidget toolbarPlacement="renderer"` passes the host-owned toolbar to a custom
list renderer as `props.toolbar`. Render it exactly once inside the workspace, including
when `rows` is empty, so users can clear filters. Query, permissions, actions and pagination
remain with the host. The default is `"header"`; table/map views always keep their normal
host toolbar even when a custom renderer opts into internal placement.

Custom renderers with delegated toolbars remain mounted during query reloads and receive
`loading: true`, preserving search focus and open filter controls. Use that flag to show a
loading state in the body rather than unmounting the toolbar.

`api.getJson<T>("/api/extension/path")` supports authenticated extension reads through the host's
shared GET client. The path is typed as `ApiPath` (`` `/api/${string}` ``); at runtime the host
rejects anything that is not a same-origin `/api/…` path after URL normalization, including `.`/`..`
segments (plain or percent-encoded), backslashes and absolute or protocol-relative URLs. Concurrent
requests to the same URL share one in-flight request, but each caller receives its own deep copy
(`structuredClone`), so a widget may mutate the result freely; completed responses are not cached.
`getJson` is a **convenience over the app's same-origin session, not a security boundary**: plugin
code runs first-party with the user's full session, and the server's endpoint authorization is what
protects data. Prefer the typed reads (`listCatalog`, `getDocument`, …) where they fit; use `getJson`
for an extension's own read endpoints. Mutations still belong to the extension's command API.
`getJson` is a required member of `OnnoReadApi`, so the widget SDK needs a host that provides it.

`DialogShell` is available from the SDK for accessible widget dialogs, typed by `DialogShellProps`.
Pass `title`, `onOpenChange` and children, plus optional `description`, `footer`, `icon`, `tone`
(`info`/`success`/`warning`/`error`), `size` (`sm`/`md`/`lg`; use `lg` for comparisons),
`dismissable`, `open` and `role="alertdialog"`. It handles focus, mobile layering, and nested
Select pickers through the host modal.
