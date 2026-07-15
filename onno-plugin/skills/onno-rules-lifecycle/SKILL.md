---
name: onno-rules-lifecycle
description: >-
  Implement onno-framework lifecycle hooks and business validation. Use when adding
  OnFillingHandler, BeforeWriteHandler, AfterWriteHandler, BeforePostHandler, AfterPostHandler,
  BeforeDeleteHandler, Validated.rules(), BusinessRule or BusinessRule.onField, derived fields,
  default seeding for New forms, idempotent lifecycle behavior, dependency-injection workarounds, or
  event listeners for side effects after writes/posts.
---

# onno Rules And Lifecycle

Lifecycle hooks run on domain objects, not Spring beans. Do not `@Autowired` inside entities.

## Hook Selection

| Need | Use |
| --- | --- |
| seed default values for New forms | `OnFillingHandler` |
| compute totals/derived fields | `BeforeWriteHandler` |
| validate invariants | `Validated` and `BusinessRule` |
| validate immediately before posting | `BeforePostHandler` or `Validated` |
| write register movements | `Postable` |
| call Spring services after post | `@EventListener DocumentPostedEvent` |
| prevent deletion | `BeforeDeleteHandler` |

Rules run on save/post — and live: the generated form debounce-calls
`POST /api/{catalogs,documents}/{name}[/{id}]/validate` (a dry run of the same lifecycle) while the
user edits, so a `BusinessRule.onField("slot", …)` conflict check surfaces inline before Save.

Read [references/examples.md](references/examples.md) for complete examples.
