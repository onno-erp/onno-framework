---
name: onno-registers
description: >-
  Create or query onno-framework accumulation and information registers. Use when modeling balances,
  turnovers, stock, cash, loyalty points, logged hours, revenue, prices by date, exchange rates,
  employee rates, supplier lead times, register dimensions/resources, balance-vs-turnover choice,
  register repository queries, information register slice last/first reads, or register UI/report
  views.
---

# onno Registers

Registers answer questions that documents create: "what is the balance now?", "what happened during
the period?", or "what fact was effective on this date?"

## Pick The Register Type

| Need | Register |
| --- | --- |
| current quantity/money balance | `@AccumulationRegister(type = BALANCE)` |
| period totals/activity | `@AccumulationRegister(type = TURNOVER)` |
| facts over time by dimensions | `@InformationRegister` |

Use `@Dimension` for the keys you group/filter by. Use `@Resource` for numeric values that
accumulate or are stored as facts.

## Read The Examples

Read [references/examples.md](references/examples.md) when writing code. It includes:

- stock balance register
- sales turnover register
- price information register
- register queries and tuple filters
- UI view hints for registers

Posting examples live in `onno-posting`.
