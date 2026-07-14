---
name: onno-modeling
description: >-
  Model a business into onno-framework concepts. Use when interviewing a user, turning business
  docs into @Catalog/@Document/@TabularSection/@AccumulationRegister/@InformationRegister,
  enumerations, constants, jobs, contexts, or implementing the first vertical slice of an ERP app
  on su.onno:onno-* artifacts. Covers the model-before-code interview, classification heuristics,
  implementation order, naming/localization, default seeding, and when to read the full API cheat
  sheet.
---

# onno Modeling

Start from the business, not tables or controllers. Say:

> I’ll model this as catalogs, documents, registers, rules, and possible future service boundaries.
> First I need to understand how the business works.

## Interview Flow

For an actual user interview, use the focused `onno-modeling-interview` skill. It has the full
question bank, extraction heuristics, confirmation template, and example transcripts.

At minimum, discover:

1. What the business sells, produces, delivers, rents, repairs, or manages.
2. The most important workflow from start to finish.
3. Main actors: customers, suppliers, employees, departments, partners.
4. Stable lists users maintain, and which are hierarchical.
5. Documents users create, their statuses, and their line items.
6. Balances, period totals, or historical facts the business must trust.
7. Rules that prevent bad saves/posts.
8. Roles, daily UI surfaces, integrations, background jobs, and future service boundaries.

Nouns usually become catalogs, registers, constants, or enum values. Verbs usually become documents,
lifecycle transitions, posting movements, or background jobs.

## Classification

| Business fact | onno concept |
| --- | --- |
| Stable list users choose from | `@Catalog` |
| Closed code-controlled list | `@Enumeration` |
| Event with date/number/lifecycle | `@Document` |
| Repeated lines owned by a document | `@TabularSection` |
| Current balance | `@AccumulationRegister(type = BALANCE)` |
| Period totals | `@AccumulationRegister(type = TURNOVER)` |
| Historical fact by dimensions | `@InformationRegister` |
| Singleton setting | `@Constant` |
| Repeated/later process | `@ScheduledJob` or Spring `@Scheduled` |
| Future service/team boundary | `context = "..."` |

Use `Ref<T>` for references between concepts. Do not embed whole objects.

## Implementation Order

1. Write a short business summary and list assumptions.
2. Implement catalogs and enums.
3. Implement documents and tabular sections.
4. Implement registers and posting logic.
5. Add constants, jobs, and integrations.
6. Author UI with `Layout`, `Page`, and `EntityView`.
7. Add focused tests and verify the running app.
8. Summarize what was modeled, what was assumed, and what remains ambiguous.

Read `../onno/reference/cheatsheet.md` before writing model classes or lifecycle hooks. It is the
dense public API lookup; code wins if the cheat sheet drifts.

## Production-Grade First Pass

- Use ASCII/URL-safe annotation `name`; put human/localized labels in `title`, `displayName`, and
  `@EnumLabel`.
- Name real business nouns. Delete copied scaffold entities and placeholder fields.
- Seed New-form defaults with idempotent `OnFillingHandler.onFilling()` or Java initializers.
- Use `EntityView.fields(...)` for order, groups, widths, widgets, placeholders, formats, and hints.
- Keep UI placement out of domain annotations; nav and pages belong to `Layout`/`Page`.
- Update docs and skills in the same change when public framework behavior changes.

## Related Skills

- `onno-modeling-interview` for what to ask the user and how to extract a model from answers.
- `onno-catalogs-enums`, `onno-documents-lines`, and `onno-registers` for implementing each concept.
- `onno-posting` for rules, register movement code, negative balance behavior, and events.
- `onno-ui` for Layout/Page/EntityView, custom widgets, localization, and list/form polish.
- `onno-runtime` for authenticated API/MCP verification.
