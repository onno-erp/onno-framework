---
name: onno-ui-widgets
description: >-
  Build onno-framework dashboard widgets and custom React widgets. Use when configuring PageBuilder
  widget DSL, built-in count/chart/list/timeRange/widgets, WidgetBuilder config, custom widget
  payloads, src/main/widgets/*.tsx files, @onno/widget-sdk, su.onno.widgets Gradle plugin, widget
  Tailwind styling, host UI primitives, read-only SDK data client, SSE live updates, plugin bundle
  loading, or debugging why a custom widget does not render.
---

# onno UI Widgets

Use built-in widgets first. Use custom widgets when the host framework has no built-in renderer for
the interaction or visualization.

## Operational Workspace Sizing

For a route-level custom list renderer or other operational workspace, fill the height already
allocated by the host: declare `page.list(Entity.class, view -> view.fill())` and use a renderer
root equivalent to `h-full min-h-0 w-full overflow-hidden`. Keep its
toolbar, pane headers, and composer fixed; make each content pane `min-h-0`; put `overflow-y-auto`
only on the conversation/list/timeline/detail bodies that should scroll. Do not add a viewport
`clamp`, arbitrary maximum height, or expanding message stack that leaves unused space and turns the
whole page into the scroller. Let ordinary embedded dashboard widgets remain content-sized.

Use authored avatar/image fields and resolved Ref sidecars such as `customerAvatar`, plus framework
payloads such as comment `authorAvatarUrl`, before inventing an avatar. Use the same deterministic
Glass fallback as the Onno shell when no stored/source image exists. In communication workspaces,
merge channel messages, Onno comments, and durable domain/system activity into one chronological
timeline; distinguish the entry types visually without hiding business events outside the flow.

## Built-In To Custom Flow

1. Can `count`, `chart`, `list`, `timeRange`, `constants`, or embedded `b.list(entity)` do it?
2. If not, create `src/main/widgets/MyWidget.tsx`.
3. Use `@onno/widget-sdk` for types, host UI primitives, and read-only data calls.
4. Apply `su.onno.widgets` in the consuming app.
5. Declare optional browser libraries with
   `onnoWidgets { npmDependencies.put("package", "version") }`, then import them normally from TSX.
6. Declare the widget from a `Page` with `b.widget("Title").type("myWidget").config(...)`.
7. Register it at module load with `registerWidget("myWidget", Component)`.
8. Make data-backed widgets live with `useWidgetUpdates(widget, load)`. It filters the host's shared
   SSE stream to the bound entity and coalesces bursts; never open an `EventSource` in a widget.
9. Run `compileWidgets`, inspect packaged `onno-plugins/*.js` and CSS, then verify `/api/config`,
   plugin URLs, RBAC-controlled reads, rendering, and SSE refresh in the browser.

Declared npm dependencies are installed in the plugin's managed workspace and bundled into the
widget module; the consuming app still needs no frontend project or local Node installation. The
app author owns their license, security, browser support, and bundle-size impact. React, React DOM,
`@onno/widget-sdk`, and compiler packages are framework-managed and cannot be overridden.

For a record surface, override `EntityView.detail(DetailSpec)` and declare the same registered type
with `detail.widget("Title").type("myWidget")`. Saved-record widgets receive `widget.record`; they
do not render on New/Duplicate forms.

Built-in `list` accepts catalogs/documents, not registers. Register KPI filters address dimensions;
use a custom widget with `api.getBalance`/`getMovements` for resource-threshold or movement-list UI.

Read [references/examples.md](references/examples.md) for full Java and TSX examples.
