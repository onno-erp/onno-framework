---
name: onno-modeling-interview
description: >-
  Run a business-modeling discovery interview for an onno-framework ERP app. Use when the user wants
  to model a business, gives business docs without enough implementation detail, asks what questions
  to ask, or needs a structured path from conversation to catalogs, documents, tabular sections,
  registers, enums, constants, jobs, contexts, rules, integrations, and UI scope. Covers interview
  rounds, question banks, how many questions to ask at once, how to extract model objects from
  answers, what to confirm before coding, and example transcripts.
---

# onno Modeling Interview

Interview before coding unless the user already gave detailed docs. The goal is to understand the
business workflow deeply enough that the Java model is obvious.

## Opening Script

Use this short opener:

> I’ll model this as catalogs, documents, registers, rules, and possible future service boundaries.
> First I need to understand how the business works.

Then ask a small number of focused questions. Do not dump the whole question bank at once.

## Interview Loop

1. Ask 3-5 questions from the current round.
2. Summarize what you heard as candidate concepts.
3. Ask the next questions that remove ambiguity.
4. Stop interviewing once you can name the first vertical slice.
5. Confirm assumptions before coding if they affect data shape, posting, or user workflow.

Read [references/interview.md](references/interview.md) for the full question bank, extraction
rules, and examples.

## Output After Discovery

Produce:

- business summary
- candidate catalogs, enums, documents, tabular sections, registers, constants, jobs, contexts
- posting/rule assumptions
- UI/persona assumptions
- first vertical slice to implement
- open questions that block safe implementation

Then implement with the focused skills: `onno-catalogs-enums`, `onno-documents-lines`,
`onno-registers`, `onno-rules-lifecycle`, `onno-posting`, and `onno-ui-entity-views`.
