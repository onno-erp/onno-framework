---
name: onno-rules-lifecycle
description: >-
  Implement onno-framework lifecycle hooks and business validation. Use when adding
  OnFillingHandler, BeforeWriteHandler, AfterWriteHandler, BeforePostHandler, AfterPostHandler,
  BeforeDeleteHandler, HasBusinessRules.rules(), BusinessRule or BusinessRule.onField, Jakarta field
  constraints (@NotNull, @Size, @Min, @Pattern, @Email), derived fields,
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
| validate one field (required, length, range, format) | Jakarta annotation: `@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@Pattern`, `@Email`, custom `@Constraint` |
| validate invariants across fields / business logic | `HasBusinessRules` and `BusinessRule` |
| validate immediately before posting | `BeforePostHandler` or `HasBusinessRules` |
| veto an unpost by document state | `BeforeUnpostHandler` |
| restrict who may post/unpost | `@AccessControl(postRoles, unpostRoles)` |
| write register movements | `Postable` |
| call Spring services after post | `@EventListener DocumentPostedEvent` |
| react immediately after a successful entity persist | `AfterWriteHandler` |
| prevent deletion | `BeforeDeleteHandler` |
| advisory as-you-type feedback (conflicts, warnings) | a `Checks<E>` bean (`onno-commands`) |

Single-field constraints are Jakarta Bean Validation (needs `spring-boot-starter-validation`); onno
runs them on every write and in `/validate`, keyed by field (`items[0].quantity`), and mirrors the
representable ones to the form. Do not use the deprecated `@Attribute(required/min/max/minLength/
pattern/email)` flags in new code. `HasBusinessRules` replaces the deprecated `Validated`.
| an action beyond create/update/delete | a `Commands<E>` bean (`onno-commands`) |

There is no framework `BusinessRuleSet` type. Share rules with an ordinary Java factory returning
`List<BusinessRule>`.

Repository saves and generic UI/API/import/MCP commands invoke `AfterWriteHandler` after a
successful persist. Validation previews skip it. It is an immediate lifecycle hook, not a universal
after-commit callback; use `EntityChangedEvent` for Spring-managed side effects after a write.

Rules run on save/post — and live: the generated form debounce-calls
`POST /api/{catalogs,documents}/{name}[/{id}]/validate` (a dry run of the same lifecycle) while the
user edits, so a `BusinessRule.onField("slot", …)` conflict check surfaces inline before Save.

Read [references/examples.md](references/examples.md) for complete examples.
