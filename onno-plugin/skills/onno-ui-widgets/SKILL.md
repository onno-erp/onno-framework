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

## Built-In To Custom Flow

1. Can `count`, `chart`, `list`, `timeRange`, `constants`, or embedded `b.list(entity)` do it?
2. If not, create `src/main/widgets/MyWidget.tsx`.
3. Use `@onno/widget-sdk` for types, host UI primitives, and read-only data calls.
4. Apply `su.onno.widgets` in the consuming app.
5. Declare the widget from a `Page` with `b.widget("Title").type("myWidget").config(...)`.
6. Register it at module load with `registerWidget("myWidget", Component)`.
7. If it needs live updates, subscribe to named SSE events and filter payloads to the bound entity.
8. Run `compileWidgets`, inspect packaged `onno-plugins/*.js` and CSS, then verify `/api/config`,
   plugin URLs, RBAC-controlled reads, rendering, and SSE refresh in the browser.

Built-in `list` accepts catalogs/documents, not registers. Register KPI filters address dimensions;
use a custom widget with `api.getBalance`/`getMovements` for resource-threshold or movement-list UI.

Read [references/examples.md](references/examples.md) for full Java and TSX examples.
