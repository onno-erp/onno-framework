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
| seed values visible on the blank New form | Java field initializer |
| seed/normalize defaults on create/save | null-guarded `OnFillingHandler` |
| compute totals/derived fields | `BeforeWriteHandler` |
| validate invariants | `Validated` and `BusinessRule` |
| validate immediately before posting | `BeforePostHandler` or `Validated` |
| write register movements | `Postable` |
| call Spring services after post | `@EventListener DocumentPostedEvent` |
| react immediately after a successful entity persist | `AfterWriteHandler` |
| prevent deletion | `BeforeDeleteHandler` |

There is no framework `BusinessRuleSet` type. Share rules with an ordinary Java factory returning
`List<BusinessRule>`.

Repository saves and generic UI/API/import/MCP commands invoke `AfterWriteHandler` after a
successful persist. Validation previews skip it. It is an immediate lifecycle hook, not a universal
after-commit callback; use `EntityChangedEvent` for Spring-managed side effects after a write.

Rules run on save/post — and live: the generated form debounce-calls
`POST /api/{catalogs,documents}/{name}[/{id}]/validate` (a dry run of the same lifecycle) while the
user edits, so a `BusinessRule.onField("slot", …)` conflict check surfaces inline before Save.

Read [references/examples.md](references/examples.md) for complete examples.
