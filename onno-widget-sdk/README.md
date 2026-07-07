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
- `api` — a read-only REST client (`listCatalog`, `getCatalogItem`, `listDocuments`, `getDocument`,
  `searchCatalog`, `searchDocument`, `getBalance`, `getTurnover`, `getMovements`). Same-origin,
  session + CSRF handled by the host. No writes.
- `html` — `htm` bound to the host `React.createElement`, for JSX-free markup.
- `WidgetProps` — `{ widget: DashboardWidgetMeta }`, the props every widget receives. Read
  `widget.entityName` / `widget.entityType` for the bound entity and `widget.extraConfig` for your
  server-side `.config(key, value)` values.

## Styling

Widget modules are compiled by esbuild **outside** the host SPA's Tailwind build. Tailwind only
generates CSS for class names it finds in the host's own sources, so a utility class in your widget
works only if the host app happens to emit the same class — anything else (e.g. `border-l`,
arbitrary values like `-left-[5px]`) is silently dropped, with no build error. Rules of thumb:

- Common text/spacing utilities (`text-sm`, `text-muted-foreground`, `mb-3`, `flex`, …) are safe —
  the host uses them everywhere.
- For layout-critical or uncommon styles, use **inline `style`** instead of classes.
- For theme colors in inline styles, use the host's HSL variables: `hsl(var(--primary))`,
  `hsl(var(--border))`, `hsl(var(--muted-foreground))`, etc. — they follow light/dark mode.

## Example

```tsx
import { registerWidget, useEffect, useState, api, type WidgetProps } from "@onno/widget-sdk";

function EventLog({ widget }: WidgetProps) {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => { api.listDocuments(widget.entityName).then(setRows); }, [widget.entityName]);
  return (
    <ul className="text-sm text-foreground">
      {rows.map((r) => <li key={String(r.id)}>{String(r._date)} — {String(r._number)}</li>)}
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
