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
6. If it needs live updates, subscribe to named SSE events.

Read [references/examples.md](references/examples.md) for full Java and TSX examples.
