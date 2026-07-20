---
name: onno-ui
description: >-
  Author onno-framework UI metadata and custom widgets. Use when creating or fixing Layout, Page,
  EntityView, ListSpec, EntityConfigBuilder, ActionSpec, dashboard widgets, custom React widgets
  compiled by the su.onno.widgets Gradle plugin, localization/messages, nav/sidebar placement,
  form/list field hints, row actions, detail actions, map views, related lists, or UI polish for
  a generated ERP app.
---

# onno UI

UI is authored as Spring beans, never as annotations on domain classes.

## Pick the Right Surface

| Need | Use |
| --- | --- |
| Sidebar/nav/shell/persona | `Layout` |
| Dashboard, settings, route override, custom route | `Page` |
| Entity list columns, form fields, actions, related lists | `EntityView` |
| Host SPA extension | `src/main/widgets/*.tsx` + `su.onno.widgets` |

An entity surface is served only when an `EntityView` exists for the active profile. A view does not
put the entity in the sidebar; a `Layout` section must list it. Nav is curated.

## Layout

Use `spec.section("Sales").icon("...").catalog(Customer.class).document(Invoice.class)` for entity
links and `.page("/ops", "Sales Ops", "activity")` for authored pages. Use `profile()`, `roles`, and
`viewport()` for personas. Keep the default layout (`profile() == null`) for back-office users.

## Page

Everything is a page: `/`, `/settings`, arbitrary routes, and default entity routes such as
`/catalogs/{name}` or `/documents/{name}`. A `Page` bean at a default route overrides the framework's
default surface.

Common builders: `b.title`, `b.subtitle`, `b.bare()`, `b.header(false)`, `b.widget(...)`,
`b.list(entity)`, `b.constants(...)`, `b.actions(...)`, `b.custom(type, payload)`, `b.row(...)`, and
`b.aside(...)`.

## EntityView

Use `list(ListSpec)` for columns, labels, filters, conditional row styles, and map views. Use
`fields(EntityConfigBuilder)` for `.order()`, `.group()`, `.width()`, `.widget()`, `.format()`,
`.placeholder()`, `.hint()`, `.label()`, `.refSecondary()`, `.refFilter()` (cascading ref pickers —
narrow one picker's options by another field's value), `.refOptions(Decorator.class)` (live
form/row-aware badges, disabled reasons, and filtering), `.uniqueWithinSection()`, visibility,
built-in action placement, and related lists. Use
`fields.validation(key, FormValidator.class).dependsOn(...).debounce(...)` for advisory live
cross-record error/warning/info feedback; keep hard invariants in the authoritative write path.

Seed a New form: field initializers for scalars/enums, `OnFillingHandler` for computed defaults, and
query-param prefill for `Ref`s and cross-navigation
(`…/new?room=<uuid>&startsAt=2026-07-16T19:00` — keys are write-path field names, `Ref`/enum values
are UUIDs, temporals ISO).

Use `actions(ActionSpec)` for custom toolbar, row, and detail actions. Prefer state-aware
`visibleWhen`, `enabledWhen`, `label(row -> ...)`, and `icon(row -> ...)` when a button depends on
record state.

## Custom Widgets

Author app widgets in `src/main/widgets/*.tsx`, use `@onno/widget-sdk`, and apply the
`su.onno.widgets` Gradle plugin. Prefer SDK UI primitives over lookalike controls. Literal Tailwind
classes inside `src/main/widgets` are compiled into the widget CSS; dynamic classes are not scanned.

Widgets that need live updates open `new EventSource("/api/events")` and listen for named events
(`created`, `updated`, `deleted`, `posted`, `unposted`, `changed`). `onmessage` will not fire.

## Iterate With Dev Mode

Add `developmentOnly("org.springframework.boot:spring-boot-devtools")`, then run `bootRun` in one
terminal and `./gradlew -t <app>:classes` in another — every save restarts the context and reloads
all open browsers over SSE. Force a reload with `touch .onno-reload`. Do not full-rebuild per UI
tweak.

## Reuse Canonical Components

When touching the host SPA itself, `onno-ui-starter/src/main/frontend/DESIGN.md` is the law: one
`Segmented`, one `FacetSheet`, one `RefSelect`, one date-picking system, islands without shadows.
Radius tiers are `rounded-pill` (9999px compact actions/chips/badges), `rounded-field`
(inputs/selects/rows/compact events), and `rounded-panel` (cards/bounded surfaces). The old
`rounded-control` alias means **pill**, not “any control”; never use it on a panel, table/grid,
schedule lane/event rectangle, generic row, empty-state box, or other large container.

## Polish Checklist

- Localize entity titles, field labels, enum labels, system columns, filters, actions, and shell
  messages consistently.
- Seed New forms so users do not start from blanks.
- Format money, percentages, dates, booleans, statuses, and references deliberately.
- Hide noisy fields from list/form/detail surfaces.
- Verify text fits and controls remain stable on mobile and desktop.

Read `../onno/reference/cheatsheet.md` for the full UI DSL and `onno-ui-starter/README.md` for widget
configuration.
