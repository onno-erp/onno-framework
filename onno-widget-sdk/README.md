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

- `registerWidget(type, Component)` — register (or override) the renderer for a widget type.
- `registerListRenderer(type, Component)` — register the **body renderer for a custom list view**:
  the component an entity's server-side `list.custom("type")` resolves. Same registry as
  `registerWidget`, list-shaped props (`ListRendererProps`): `rows` (the current window, fed by the
  framework-owned search/filters/sort/pagination), `list` (the descriptor — `kind`, `name`, `title`,
  resolved `columns`, `canWrite`), and `open(row)` / `openUrl(row)` to open a record's detail pane.
  An unregistered type degrades to the default grid.
- `React`, `useState`, `useEffect`, `useMemo`, `useRef`, `useCallback`, `useReducer`, `useContext`,
  `useLayoutEffect` — the host React and its hooks (you may equally `import ... from "react"`).
- `useWidgetUpdates(widget, refresh, options?)` — refetch when the widget's bound entity changes,
  with matching, burst coalescing, and cleanup built in. It reuses the host's shared SSE stream.
- `events.subscribe(listener, filter?)` / `subscribeUiEvents(...)` / `useUiEvents(...)` — lower-level
  access to the same shared stream for widgets with unusual matching needs. Never open a separate
  `EventSource` from a widget.
- `api` — a read-only REST client (`listCatalog`, `getCatalogItem`, `listDocuments`, `getDocument`,
  `getBalance`, `getTurnover`, `getMovements`). List calls return one bounded keyset window.
  Same-origin,
  session + CSRF handled by the host. No writes.
- **UI primitives** — the host's real design-system controls, so a widget matches the product instead
  of shipping hand-rolled lookalikes. Import them **by name** (`import { DatePicker, Select, Button }
  from "@onno/widget-sdk"`) or off the `ui` object (`ui.Select`). Curated subset — `Button`, `Badge`,
  `Input`, `Label`, `Textarea`, `Checkbox`, `Switch`, `Segmented`, `DatePicker`, `Card`
  (+ `CardHeader`/`CardTitle`/`CardDescription`/`CardContent`), `Popover`
  (+ `PopoverTrigger`/`PopoverContent`), and `Select`
  (+ `SelectTrigger`/`SelectValue`/`SelectContent`/`SelectItem`/`SelectGroup`). They resolve to the
  host's singletons at runtime (shared React instance, host-emitted classes). Requires host contract
  v2+.
- `html` — `htm` bound to the host `React.createElement`, for JSX-free markup.
- `WidgetProps` — `{ widget: DashboardWidgetMeta }`, the props every widget receives. Read
  `widget.entityName` / `widget.entityType` for the bound entity and `widget.extraConfig` for your
  server-side `.config(key, value)` values. When the component is embedded through
  `EntityView.detail(DetailSpec)`, `widget.record` additionally carries the saved record's route
  `kind`, logical `name`, `id`, already-loaded `data`, and `readOnly` state.

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

### Date and time fields

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
