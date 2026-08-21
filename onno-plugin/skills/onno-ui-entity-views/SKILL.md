---
name: onno-ui-entity-views
description: >-
  Author onno-framework EntityView beans. Use when configuring entity list columns, field labels,
  system column labels, list filters, grouping, aggregation, conditional row styles, form layout,
  field widgets, formatting, placeholders, hints, refSecondary, refFilter cascading pickers
  (dependent ref fields filtered by another field's value), related lists, comments, toolbar row
  and detail actions, action forms, dynamic action labels/icons/visibility, built-in post/unpost
  placement, or map list views.
---

# onno UI Entity Views And Actions

An `EntityView` is the allowlist for served catalog/document surfaces. It is necessary for direct
routes and API UI surfaces, but nav still requires a `Layout` section.

## Author The Complete View

- `list(ListSpec)` for table/report shape, filters, grouping, sorting, row styles, map toggle.
- `fields(EntityConfigBuilder)` for form/detail hints, system column labels, tabular section hints,
  related lists, action placement.
- `detail(DetailSpec)` for custom widgets below a saved record's fields; `widget.record` supplies
  the owning kind/name/id, loaded data, and read-only state.
- `actions(ActionSpec)` for toolbar, row, and detail buttons.
- `inputs(InputSpec)` for toolbar inputs when the surface needs them.
- `comments()` to opt the entity into comments when the global feature is enabled.

Use `EntityView<E>` and getter references. Prefer `field/refField/rowField/rowRefField` typed
overloads; raw types and string Java-field names are only dynamic escape hatches. Expression text
such as `refFilter("customer = ${customer}")` intentionally remains a string.

For a server row action with `label(row -> ...)`, also set a human-facing `label(String)`. Batch
selection has no single `ActionRow`, so its menu and progress messages use the static label and
otherwise fall back to the action key. If one operation is not deterministic across mixed record
states, expose separate actions instead of a toggle.

Read [references/examples.md](references/examples.md) for a full view with actions and forms.
