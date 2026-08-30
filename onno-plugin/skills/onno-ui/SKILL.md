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
links and `.page("/ops", "Sales Ops", "activity")` for authored pages. Use `profile()` and
`viewport()` for personas: `Layout.profile()` names the persona and
`spec.roles(...).priority(...)` selects it. Keep the default layout (`profile() == null`) for shared
shell/identity and back-office users.

With desktop `NavStyle.SIDEBAR`, each authored section is a workspace icon in the collapsible app
rail and its entries fill the nested drawer. Name sections after user jobs/bounded workspaces
(`Inbox`, `Sales`, `Configuration`), not framework storage kinds (`Catalogs`, `Documents`). Do not
rebuild a second navigation shell inside a custom widget.

## Page

Everything is a page: `/`, `/settings`, arbitrary routes, and default entity routes such as
`/catalogs/{lowercase_snake_name}` or `/documents/{lowercase_snake_name}`. A `Page` bean at a
default route overrides the framework's default surface.

Common builders: `b.title`, `b.subtitle`, `b.bare()`, `b.header(false)`, `b.widget(...)`,
`b.list(entity)`, `b.actions(...)`, `b.custom(type, payload)`, `b.row(...)`, and
`b.aside(...)`.

Render a constant setting with `b.widget(...).type("setting").config("constant", logicalName)`;
there is no `b.constants(...)` API.

## EntityView

Use `list(ListSpec)` for columns, labels, filters, conditional row styles, and map views. Use
`fields(EntityConfigBuilder)` for `.order()`, `.group()`, `.width()`, `.widget()`, `.format()`,
`.placeholder()`, `.hint()`, `.label()`, `.refSecondary()`, `.refFilter()` (cascading ref pickers —
narrow one picker's options by another field's value), `.refOptions(Decorator.class)` (live
form/row-aware badges, disabled reasons, and filtering), `.uniqueWithinSection()`, visibility,
built-in action placement, and related lists. Use
`fields.validation(key, FormValidator.class).dependsOn(...).debounce(...)` for advisory live
cross-record error/warning/info feedback; keep hard invariants in the authoritative write path.

Field widths serve two surfaces. Use `half`/`1/2`/`50%` for a half-row form field; the list ignores
those tokens. Use a positive whole-pixel width such as `240` or `240px` only when a table column must
be fixed. Never treat a form fraction as table pixels.

Use `detail(DetailSpec)` to place registry-backed custom widgets below the fields of a saved
catalog/document record. The widget receives `widget.record` (`kind`, `name`, `id`, loaded `data`,
`readOnly`) and is intentionally absent from New/Duplicate forms.

Seed a blank New form with field initializers for scalars/enums. Use `OnFillingHandler` for
create/save normalization and computed defaults, and
query-param prefill for `Ref`s and cross-navigation
(`…/new?room=<uuid>&startsAt=2026-07-16T19:00` — keys are write-path field names, `Ref`/enum values
are UUIDs, temporals ISO).

Use `actions(ActionSpec)` for custom toolbar, row, and detail actions. Prefer state-aware
`visibleWhen`, `enabledWhen`, `label(row -> ...)`, and `icon(row -> ...)` when a button depends on
record state. For a dynamic server row action exposed to batch selection, always also set a
human-facing `.label(String)`: the batch menu and progress messages have no single row context and
otherwise show the action key. If a mixed-state batch would be ambiguous, declare separate
deterministic actions. Configure an action form's canonical dialog inside `.form(f -> f.title(...)
.description(...).submitLabel(...).cancelLabel(...).tone(...).size(...).input(...))`. Throw
`ActionRejectedException` for an expected business rejection; use `fieldError`/`formError` so the
open form retains its values and shows corrective feedback. Use
`ActionResult.toast(ActionToast.success(title).message(...).detail(...))` for structured,
severity-aware transient feedback and `ActionResult.dialog(ActionDialog…)` for a successful outcome
that needs acknowledgement. Use `reload()` or `refresh(ActionToast|ActionDialog)` when the surface
must refetch. Handler results contain only refresh intent and structured feedback; put static
navigation on the action declaration with `.navigate(...)`.

## Custom Widgets

Author app widgets in `src/main/widgets/*.tsx`, use `@onno/widget-sdk`, and apply the
`su.onno.widgets` Gradle plugin. Prefer SDK UI primitives over lookalike controls. Literal Tailwind
classes inside `src/main/widgets` are compiled into the widget CSS; dynamic classes are not scanned.

Declare optional browser libraries in the consuming Gradle build with
`onnoWidgets { npmDependencies.put("package", "version") }`, then import them normally from TSX.
They are bundled into the widget; React, React DOM, `@onno/widget-sdk`, and compiler packages remain
framework-managed and cannot be overridden.

Route-level operational workspaces should declare
`page.list(Entity.class, view -> view.fill())`, consume that height (`h-full min-h-0`),
keep headers/toolbars/composers fixed, and give scrolling only to bounded inner panes. Avoid widget
`max-height`/viewport clamps and ever-growing feeds that make the entire tab scroll. Embedded
dashboard widgets remain content-sized. Reuse entity avatar fields, resolved Ref `*Avatar`
sidecars, and comment/profile avatar payloads; use Onno's Glass fallback only when no stored or
source image exists. A conversation timeline should merge messages, Onno comments, and durable
domain/system events chronologically.

Data-backed custom widgets use `useWidgetUpdates(widget, load)` from `@onno/widget-sdk`; it filters
and coalesces the host's shared live-event stream. Never open `new EventSource("/api/events")` in a
widget. For unusual multi-entity or non-entity subscriptions, use the SDK's `events.subscribe` or
`useUiEvents`. The underlying stream carries named events
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
- Inspect the real rendered list after adding field widths; headers and representative row values
  must remain visible, and no grid track may collapse to `1px`/`2px`.

Read `../onno/reference/cheatsheet.md` for the full UI DSL and `onno-ui-starter/README.md` for widget
configuration.
