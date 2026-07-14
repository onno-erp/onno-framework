---
name: onno-catalogs-enums
description: >-
  Create or modify onno-framework catalogs and enumerations. Use when modeling master data,
  hierarchical catalogs, reference fields with Ref<T>, catalog codes/prefixes, localized titles and
  field labels, @Enumeration Java enums, @EnumLabel display labels/colors, secret attributes,
  catalog repositories, soft-delete-aware catalog logic, or deciding whether a business list should
  be a @Catalog or an enum.
---

# onno Catalogs And Enums

Use a catalog when users maintain the list at runtime. Use an enumeration when code owns the allowed
set and business logic branches on constants.

## Workflow

1. Decide whether each list is runtime master data or a code-controlled state/category.
2. Keep annotation `name` ASCII and route-safe. Put user-facing/localized labels in `title`,
   `displayName`, `EntityView.field(...).label(...)`, and `@EnumLabel`.
3. Use `Ref<T>` for links to catalogs/documents. Do not embed target objects.
4. Add `@AccessControl`; entities without explicit roles are deny-by-default.
5. Add an `EntityView` if the catalog should be served in the UI/API surface.
6. Add repository methods only when app logic needs them; prefer active finders for business rules.

## Read The Examples

Read [references/examples.md](references/examples.md) when writing code. It includes:

- a full catalog with refs, money, image URL, and RBAC
- hierarchical catalog pattern
- enum with localized labels and status colors
- repository and soft-delete examples
- catalog vs enum decision examples

For exact annotation defaults and supported field types, read `../onno/reference/cheatsheet.md`.
