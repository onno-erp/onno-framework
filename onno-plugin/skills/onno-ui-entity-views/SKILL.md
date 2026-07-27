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

## Work In Three Methods

- `list(ListSpec)` for table/report shape, filters, grouping, sorting, row styles, map toggle.
- `fields(EntityConfigBuilder)` for form/detail hints, system column labels, tabular section hints,
  related lists, action placement.
- `actions(ActionSpec)` for toolbar, row, and detail buttons.

For a server row action with `label(row -> ...)`, also set a human-facing `label(String)`. Batch
selection has no single `ActionRow`, so its menu and progress messages use the static label and
otherwise fall back to the action key. If one operation is not deterministic across mixed record
states, expose separate actions instead of a toggle.

Read [references/examples.md](references/examples.md) for a full view with actions and forms.
