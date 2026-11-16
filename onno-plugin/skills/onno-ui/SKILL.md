---
name: onno-ui
description: >-
  Overview of the onno-framework UI: the npm package @onno/ui and an app's own Vite app in
  src/main/ui. Use when starting or restructuring an app's UI, applying the su.onno.ui Gradle
  plugin (onnoUiInit, generateUiModel, buildUi), writing main.tsx / defineApp, working with the
  generated model.gen.ts types (EntityName, RecordOf, FieldOf, enum constants, OnnoRoles), the
  typed UI vocabulary (formats, inputs, widths, icons, where), the npm-run-dev loop, localization
  and branding (onno.ui.*), the shell's design system, or deciding which file or skill a UI
  change belongs in. Routes to onno-ui-entities (list/form customizations), onno-ui-pages (pages,
  navigation, widgets, data hooks, onno:// links) and onno-commands (server behaviour in Java).
---

# onno UI

The Java model is the source of truth. With no UI code at all an app already has navigation
(entities grouped by their annotation `context`), a home page, and a list + record form for every
entity the signed-in user may read — `@AccessControl` and record policies decide what exists for
whom. The server describes **data and permissions**, never screens; the browser decides
presentation.

The UI is the npm package **`@onno/ui`** (a React shell). An app's UI is an ordinary Vite app in
`src/main/ui` that imports it and declares only what differs from the defaults. There is no Java
UI DSL, no name → component registry and no import side effects: you import a component and
render it, or pass it as a prop.

## Where things live

| Concern | Where | Skill |
| --- | --- | --- |
| The app declaration (pages, navigation, entity customizations, extensions) | `src/main/ui/main.tsx` — one `defineApp({...})` call | this one |
| How an entity's default list and form present | `src/main/ui/entities/<entity>.ts` — one `EntityCustomization<"Orders">` | `onno-ui-entities` |
| The app's own pages and nav | `src/main/ui/pages/*.tsx` + `pages`/`navigation` in `main.tsx` | `onno-ui-pages` |
| The app's own components (widgets, custom list views, record panels) | `src/main/ui/components/*.tsx` | `onno-ui-pages` |
| What can be *done* with a record (buttons, MCP tools), live form checks, picker badges, login → record link | Java beans: `Commands<E>`, `Checks<E>`, `PickerOptions<E>`, `IdentityLink` | `onno-commands` |
| Rules that must hold on save, posting | Java domain (`Validated`, `BeforeWriteHandler`, `Postable`) | `onno-rules-lifecycle`, `onno-posting` |
| Branding, chrome language, chrome strings | `onno.ui.*` config | this one |
| Read-only mode, list page size, dev reload | `onno.web.*` config | `onno-runtime` |

The reference app is `example/src/main/ui` (the bookstore). Copy shapes from it.

## Set up an app's UI

```kotlin
// build.gradle.kts
plugins {
    id("org.springframework.boot")
    id("su.onno.ui")          // builds src/main/ui into the jar
}
dependencies {
    implementation("su.onno:onno-framework-starter:<version>")
    implementation("su.onno:onno-web-starter:<version>")
    implementation("su.onno:onno-auth-starter:<version>")
}
```

`./gradlew onnoUiInit` seeds `src/main/ui` (never overwrites): `package.json` (`@onno/ui`, React,
Vite, TypeScript), `vite.config.ts`, `tsconfig.json`, `index.html`, `.gitignore`, `main.tsx`,
`pages/HomePage.tsx`, `entities/README.md`. Without a `src/main/ui/package.json` the plugin does
nothing and the server serves the starter's default shell.

The plugin's tasks:

| Task | Does |
| --- | --- |
| `generateUiModel` | Writes `src/main/ui/model.gen.ts` from the compiled Java entities (git-ignored; runs before every UI build). |
| `buildUi` | `npm run build` (Vite) into the jar's `onno-app/`, which the server serves instead of the default shell. Wired before `processResources`. |
| `onnoUiInit` | Seeds a new app. |

`onnoUi { sourceDir; npmProjectDir; nodeVersion; modelPackages }` is optional; `modelPackages`
adds entities from packages outside the app's own (e.g. a starter's) to the generated model.

`vite.config.ts` is one line — the preset adds React, Tailwind with the shell's theme, the dev
proxy to the backend and the Gradle output directory:

```ts
import { defineConfig } from "vite";
import { onno } from "@onno/ui/vite";

export default defineConfig({ plugins: [onno()] });   // onno({ backend: "http://localhost:9090" }) to change the proxy target
```

`main.tsx` is one call. Everything in it is optional:

```tsx
import { defineApp } from "@onno/ui";
import { orders } from "./entities/orders";
import { HomePage } from "./pages/HomePage";
import { SalesOpsPage } from "./pages/SalesOpsPage";
import { SettingsPage } from "./pages/SettingsPage";

defineApp({
  entities: { Orders: orders },
  pages: [
    { path: "/", component: HomePage },
    { path: "/ops", component: SalesOpsPage, label: "Sales Ops", icon: "activity" },
    { path: "/settings", component: SettingsPage, label: "Settings", icon: "settings", roles: ["ADMIN"] },
  ],
  navigation: [
    { title: "Sales", icon: "shopping-cart", items: ["Orders", "Customers", "/ops"] },
    { title: "System", icon: "settings", items: ["/settings"] },
  ],
});
```

The declaration goes down the React tree in context; components read it from there. Never keep
app configuration in module state.

## The typed model

`model.gen.ts` augments `@onno/ui`'s interfaces (`OnnoEntities`, `OnnoInformationRegisters`,
`OnnoConstants`, `OnnoRoles`), so every name an app writes is checked:

- **Entities are named by logical name** — the annotation's `name` (`"Orders"`, `"Stock Receipts"`),
  never the Java class or the route. `EntityName`, `CatalogName`, `DocumentName`, `RegisterName`.
- **Records and fields**: `RecordOf<"Orders">` is the generated `Order` type; `FieldOf<E>` lists its
  fields with `"items.book"` for tabular-section columns; `TopField<E>` excludes those. A record
  carries `<field>Display` (label of a reference/enum) and `<field>Ref` companions.
- **Enums are constants with UUID values**, plus label maps:
  `import { OrderStatus, OrderStatusLabels } from "../model.gen"`; compare
  `order.status === OrderStatus.NEW`. Never compare against the constant's name string.
- **Roles**: `RoleName` is every role an `@AccessControl` names plus `ADMIN`. A role that appears
  only in a command's `roles(...)` or in configured users is declared by the app:

  ```ts
  declare module "@onno/ui" { interface OnnoRoles { SUPPORT: true } }
  ```

When a type error says a name is not assignable, regenerate (`./gradlew generateUiModel`) before
"fixing" the code — the Java model is the authority.

## The typed vocabulary

Everything handed to `@onno/ui` is a typed value, never a string the UI parses:

| Concept | Type | Example |
| --- | --- | --- |
| Value format | `ValueFormat` | `{ money: "USD" }`, `{ number: { decimals: 0 } }`, `{ percent: { decimals: 1 } }`, `{ date: "medium" }` |
| Edit control | `FieldInput` | `"textarea" \| "switch" \| "image" \| "images" \| "file" \| "color" \| "avatar" \| "map"` |
| Form width | `FieldWidth` | `"full" \| "half"` (two `half` fields pair up) |
| Row column width | `RowWidth` | flex weight `2`, or `{ px: 320 }` |
| Icon | `IconName` | lucide names: `"shopping-cart"`, `"chart-column"`. In components: `import { Icon, Truck } from "@onno/ui/icons"` (all of lucide ships with `@onno/ui`; never add `lucide-react`) |
| Which records | `Where<E>` | `{ posted: true, total: { gte: 100 }, status: { in: [OrderStatus.NEW] }, or: [...] }` |
| A number over records | `Measure<E>` | `count("Orders", { open: true })`, `sum("Orders", "total", { posted: true })` |
| Grouping | `By<E>` | `"status"`, `{ date: "week" }` |
| Deep link | `OnnoLink` | `links.record("Orders", id)` → `onno://documents/orders/<id>` |

`Where` is sent to the server as JSON and evaluated there — for lists, `useRecords`, measures and
reference pickers alike. Operators: `eq`, `not`, `in`, `notIn`, `gt`, `gte`, `lt`, `lte`,
`contains`, `startsWith`, plus `and`/`or`/`not` and `id: { in: ids }`.

## Intended dev loop

In the onno-framework repository, use the root scripts as the default edit loop:

```bash
npm install                       # once, at the repository root
npm run dev:backend               # terminal 1: example backend, DevTools, port 8091
npm run dev:watch                 # terminal 2: application Java/resources + model.gen.ts
npm run dev                       # terminal 3: Vite, http://localhost:5173
```

Reuse a running backend and start only `npm run dev` for frontend-only work. Pages, components,
styles and linked `@onno/*` source packages hot-reload. Do not rebuild jars or restart the backend
for UI edits. Stop a packaged backend before switching to `dev:backend` on the same port; preserve
`example/data/bookstore`. The development scripts skip production UI builds. Framework dependency
jar/classpath changes may require a backend restart. Normal build/check/publish commands remain
packaging and verification steps. See [the canonical workflow](../../../docs/RUNNING.md#intended-development-workflow).

For a separate consumer application, start its backend, then run `npm run dev` in `src/main/ui`.
The preset defaults to backend port 8080; override with `ONNO_BACKEND` or `onno({ backend })`.
The repository root scripts above do not exist in consumer projects. Regenerate that app's typed
model with `./gradlew generateUiModel` after Java model changes; enable DevTools and continuous
compilation if it needs Java reloads too.

## Localize and brand

A first pass is unfinished while any surface is in a different language from the business:

- Entity `title=`, attribute `displayName=`, `@Enumeration(title=…)` and `@EnumLabel("…")` in Java.
- System columns (`code`, `description`, `number`, `date`, `posted`) and any field label: `label`
  in the entity's `fields` customization (form) and `list.labels` (list header) — see
  `onno-ui-entities`.
- Page titles, nav section titles and page `label`s in the app's TSX.
- Chrome (buttons, login, toasts, empty states): `onno.ui.locale` picks the built-in bundle,
  `onno.ui.messages.<key>` overrides single strings. In components, `useTranslate()` /
  `text(key)` read them; `useLocale()` / `locale()` the configured locale.
- Branding: `onno.ui.branding.app-name`, `logo-url[-dark]`, `mark-url[-dark]`, `favicon-url`,
  `light`/`dark` color maps; demo sign-in buttons: `onno.ui.login.demo-accounts`. The SPA is
  mounted under `onno.ui.path` (default `/ui`).

## Reuse the shell's components

Import controls from `@onno/ui` (`Button`, `Badge`, `Input`, `Textarea`, `Checkbox`, `Switch`,
`Segmented`, `Select…`, `Card…`, `Popover…`, `DialogShell`, `DatePicker`, `DateTimePicker`,
`ColorPicker`, `RefSelect`, `WorkspaceToolbar`, `toast`, `HintIcon`) instead of building lookalikes;
pick records with `RefSelect` (search, recent picks, "Show all…"), not a `Select` of fetched rows.
Tailwind runs over `src/main/ui` with the shell's theme, so app components use the same tokens
(`bg-card`, `border-border`, `text-muted-foreground`, `rounded-panel`, `rounded-field`,
`rounded-pill`).

When editing `@onno/ui` itself, `onno-ui/DESIGN.md` is the law: one `Segmented`, one `FacetSheet`,
one `RefSelect`, one date-picking system, islands without shadows. Radius tiers are `rounded-pill`
(compact actions/chips/badges), `rounded-field` (inputs/selects/rows/compact events) and
`rounded-panel` (cards/bounded surfaces); never put `rounded-pill` on a panel, table, lane or
empty-state box. Inputs and every select/combobox/reference/date trigger use `rounded-field`
through `selectTriggerClassName` (`components/ui/select.tsx`); add a component test when changing
picker geometry.

## Polish checklist

- Localize entity titles, field and system-column labels, enum labels, filters, command titles and
  chrome consistently.
- Seed New forms (Java field initializers, `OnFillingHandler`, prefilled `links.newRecord`).
- Format money, percentages and dates deliberately (`format`), choose inputs (`input`), hide noise
  (`hideInList`/`hideInForm`/`hidden`).
- Curate navigation by user job, not by storage kind.
- Check the rendered list and form at desktop and phone width: headers readable, no clipped badges.

## Verify

```bash
cd src/main/ui && npm run typecheck   # the app's TS against the generated model
./gradlew build                        # generateUiModel + buildUi + tests
```

Then open the running app and look. Working on `@onno/ui` itself: in `onno-ui/`,
`npx tsc --noEmit -p tsconfig.app.json`, `npx tsc --noEmit -p tsconfig.types.json` (type-level
tests of the public API), `npx vitest run`; `./gradlew :onno-web-starter:testFrontend`.
`docs/UI_TOUR.md` walks the package's internals.
