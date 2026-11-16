---
name: onno-ui-entities
description: >-
  Customize how an onno-framework entity's default list and record form present, with an
  TSX list and record page in src/main/ui/pages/ registered through defineApp({ pages }).
  Use for list columns, list header labels, default sort, grouping (groupable, date buckets),
  per-group aggregates, toolbar filters (options, optionsFrom, date/number ranges), a fixed list
  where, map, calendar, timeline (resource lanes) and custom list views, selection checkboxes and selection toolbar components, row
  tint (rowStyle), field labels (including system columns code/description/number/date/posted),
  form order/group/width, inputs (textarea, switch, image, avatar, color, map), value formats
  (money, number, percent, date), custom cell render functions, placeholders and hints, reference
  pickers (refSecondary, filterBy cascades, open/clear buttons, uniqueWithinSection), how server
  commands and built-in actions are offered (commands: placement/icon/title), record panels under a
  saved record, the record change-history panel, LocalTime/Instant fields and the display time
  zone, the entity icon, and the comments thread.
---

# onno UI: entity customizations

Every entity the user can read already has a default list and record form, built from the model.
Use TSX components in `src/main/ui/pages/`, registered by logical name in `defineApp({ pages })`:
`{ list: "Orders", component: OrdersPage, icon: ClipboardList }` and
`{ record: "Orders", component: OrderPage }`. Import icons from `@onno/ui/icons`; use components,
never icon name strings. No `definePage` wrapper or separate entity configuration file is needed.

`OrdersPage({ list }: ListPageProps<"Orders">)` renders `<EntityList list={list} columns={…}
filters={…} fields={…} commands={…} />`. The list presentation options below are direct props.
Use `selectionToolbar` for the toolbar component and `selection` for controlled selected ids.
`OrderPage({ form }: RecordPageProps<"Orders">)` renders `<RecordPage form={form}>` with
`RecordFields`, `RecordLines` and ordinary React components. Fields and sections are typed by the
model. `RecordFields` takes an ordered field array or a field-to-options map; `RecordLines` takes
`section="items"` and a map of column options. Custom controls read `form.values` and call
`form.set(field, value)`. Saving, posting, validation, dirty guards, picker cascades and live
refresh remain shared. `RecordPage` takes `commands`, `panels`, `comments`, and `history`.
The older `EntityCustomization`/`entities` API remains for compatibility; prefer components for
new work. See [the package examples](../../../onno-ui/README.md).

## `list`

| Key | Meaning |
| --- | --- |
| `columns` | Fields shown, in order. Keep the first column descriptive; drop what doesn't fit a viewport. |
| `labels` | List-only header labels (`{ description: "Title" }`). `fields[f].label` relabels list and form. |
| `sort` | Initial sort; `"-date"` for descending. |
| `groupBy` | Open grouped by this field. |
| `groupable` | What the "Group by" picker offers: `["status", "assignedTo", { date: "month" }]` (date buckets `day`/`month`/`year`). |
| `aggregates` | Per-group subtotals: `{ sum: "total", label: "Total", format: { money: "USD" } }` (`sum`/`avg`/`min`/`max`). |
| `filters` | Toolbar filters, replacing the defaults (see below). |
| `where` | Records the list always shows (`{ open: true }`); the viewer's filters apply on top. |
| `title` | List heading. |
| `map` | Table ⇄ Map toggle: `{ lat: "latitude", lng: "longitude", label?, defaultView? }`. |
| `calendar` | Table ⇄ Calendar for date-ranged records: `{ start, end?, title?, color?, mode?: "month" \| "week" \| "day", defaultView? }` (`start`/`end` are typed date fields). |
| `timeline` | Table ⇄ Timeline, a day scheduler with one lane per value of a reference: `{ start, end, lane?, laneEntity?, laneLabel?, title?, color?, slotMinutes?, startHour?, endHour?, defaultView? }`. `laneEntity` (the lane reference's catalog) lists every lane, not only the ones with rows. |
| `custom` | Table ⇄ custom view: `{ component, label, defaultView? }` — your component renders the same searched/filtered/sorted rows (`ListRendererProps`). |
| `selection` | A component shown in the toolbar while rows are selected; gets `{ ids, complete }`. |
| `selectionCheckboxes` | Visible row checkboxes (keyboard selection works regardless). |

Calendar and timeline read the list feed with the list's search and filters, refresh live, open the
record on click and — only with write access — save a drag (move, resize, lane change) as an
ordinary record update, so `beforeWrite`, rules and record access apply. Don't build a bespoke
scheduler component or reschedule endpoint for one entity:

```tsx
<EntityList
  list={list}
  calendar={{ start: "startsAt", end: "endsAt", title: "subject", mode: "week" }}
  timeline={{ start: "startsAt", end: "endsAt", lane: "organizer", laneEntity: "Employees",
              title: "subject", slotMinutes: 30, startHour: 7, endHour: 22 }}
/>
```

Filters: `{ field, label, type?, options?, optionsFrom? }`. Omit `type` and the control follows
the field: enum/reference → `"multiOptions"`, date → `"dateRange"`, number → `"numberRange"`,
text → `"contains"`. Also `"options"` (single choice) and `"startsWith"`. Enum choices, labels and
colours come from the model; give `options` (`string[]` or `{ value, label, color?, avatarUrl? }[]`)
for fixed choices of a text field, `optionsFrom: "Employees"` to load a catalog's records.

Selection runs the entity's record commands over every selected row (batch) and offers the print
starter's selection downloads; a `selection` component does its own batch work and calls
`complete()` (clear + reload) when done. The server still authorizes every command.

## `fields`

| Key | Meaning |
| --- | --- |
| `label` | Display label on form and list. The only way to relabel system columns `code`/`description`/`number`/`date`/`posted`. |
| `hidden` / `hideInList` / `hideInForm` | Suppress everywhere / in the list / in the form. |
| `order`, `group` | Form order; fields sharing a `group` render as their own card. |
| `width` | `"full"` (default) or `"half"`; two half fields share a row. Form layout only. |
| `input` | Edit control: `"textarea"`, `"switch"`, `"image"`, `"images"`, `"file"`, `"avatar"`, `"color"`, `"map"`. Media/colour inputs also drive the list cell. |
| `format` | Display in lists and subtotals: `{ money: "USD" }`, `{ number: { decimals: 0 } }`, `{ percent: { decimals: 1 } }`, `{ date: "medium" }` or `Intl.DateTimeFormatOptions`. |
| `render` | `(value, record) => ReactNode` — the list cell drawn by the app; wins over `format`/`input`. |
| `placeholder`, `hint` | Input placeholder; help text behind a `?` next to the label. |
| `refSecondary` | Reference picker: a target field shown under each option's name (`"email"`). |
| `filterBy` | Reference picker cascade: `{ supplier: "supplier" }` = offer only targets whose `supplier` equals this form's `supplier` (a row column reads its own row first). Changing the source clears the pick. |
| `openButton`, `clearButton` | Picker's "open record" / "clear" buttons (a required reference is never clearable). |
| `uniqueWithinSection` | Tabular column: a value may appear in one row only. |

Every reference picker searches all text columns of the target, leads an empty query with the
user's recent picks, and ends with "Show all…" — the target's own list (its customization's
columns and filters) in a dialog, with a folder tree for hierarchical catalogs. `filterBy`, server
`PickerOptions` and `uniqueWithinSection` carry over into that dialog. Make the target's list
readable as a picker: a descriptive first column, search-friendly columns.

`LocalTime` fields edit with a time picker (`TimePicker`) and `Instant` fields with a date-time
picker that shows and enters the moment in the display time zone (`InstantPicker`): the server's
`onno.ui.time-zone` (reported by `/api/config`), else the viewer's browser zone. Lists and
`{ date: … }` formats render instants in the same zone.

Seed a New form from Java (field initializers, `OnFillingHandler`) or, for references and
cross-navigation, a prefilled link: `openRecord("Orders", "new", { customer: id })` or
`links.newRecord("Orders", { customer: id })` (ids for references and enums, ISO for dates).

Live as-you-type feedback and picker badges/disabled options are server code
(`Checks<E>`, `PickerOptions<E>`) — see `onno-commands`.

## `rowStyle`

`(record) => "danger" | "warning" | "success" | "accent" | "muted" | null` tints a list row with a
theme-aware wash. It runs in the browser over the typed record: compare enums with the generated
constants.

## `commands`

Commands are declared in Java (`Commands<E>`, see `onno-commands`); here you only choose how each
is offered, by key. Keys are the entity's server commands, the built-ins `post`, `unpost`,
`duplicate`, `delete`, `openInList`, and extension modules' actions (the print starter's
`print-<form>` and `print-<form>-pdf`).

| Placement | Offered as |
| --- | --- |
| `"menu"` (default) | Record header's ⋯ menu and the list row's context menu. |
| `"row"` | An inline icon button on every list row, plus the menus. |
| `"primary"` | A prominent record-header button (a collection command: a prominent toolbar button). |
| `"hidden"` | Not offered (still runnable through `useCommand` and the API). |

```ts
// inside EntityCustomization<"Orders">
commands: {
  advance: {
    placement: "row",
    // An icon may vary per record (typed RecordOf<"Orders">).
    icon: (order) => (order.status === OrderStatus.SHIPPED ? BadgeCheck : ArrowRight),
  },
  cancel: { icon: Ban },
  requestApproval: "primary",                         // a placement alone is shorthand
  "print-invoice": { icon: Printer },
  delete: "hidden",
},
```

Commands sharing a server `group("Change status")` appear as one submenu. Titles come from the
server (per record when the command declares `title(row -> …)`); `title` here overrides them.

## `recordPanels`

Components rendered under a **saved** record's form (not on New/Duplicate):

```ts
recordPanels: [{ component: CustomerConversations, title: "Client conversations", hint: "…", width: "full" }],
```

The component receives `{ record: RecordContext<E>; title? }` — `record.kind`, `name`, `id`,
`data` (typed `RecordOf<E>`), `readOnly`.

## Record history

An entity with `history = true` on its `@Catalog`/`@Document` is marked `history: true` in the UI
model, and the default record page shows a collapsed **History** panel under a saved record (who
changed which field, old → new, tabular rows and post/unpost). On an authored page,
`<RecordPage form={form} history={false}>` hides it and `history` forces it on. Place the panel
yourself with `<RecordHistory target={{ kind: "documents", name: "Orders", id }} />`. It reads
`GET /api/history/{kind}/{name}/{id}` with the record's own read rules. Opting in is a Java model
decision (it records on every write path), never a UI one.

## Rules

- Presentation only. Never hide a field to "secure" it; use `@AccessControl`/record policies.
- Give every column a human label — directly (`labels`) or through the field's `label`/Java
  `displayName`.
- Don't rebuild the list in a component to change columns or filters; customize it.
- Behaviour (a button that changes data) is a Java command; a customization never posts to an
  ad-hoc endpoint.

Read [references/examples.md](references/examples.md) for complete customizations from the
example app.
