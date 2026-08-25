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
