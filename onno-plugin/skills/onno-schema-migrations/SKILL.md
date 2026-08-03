---
name: onno-schema-migrations
description: >-
  Work with onno-framework schema diffs, metadata-driven DDL, renames, destructive-change safety,
  AppMigration data migrations, schema history, generated configuration docs, and migration
  verification. Use when changing annotation names/table names/attribute names/types, adding
  previousNames, configuring onno.schema.mode or onno.schema.allow-destructive, writing backfills,
  seeding data, or diagnosing startup schema drift.
---

# onno Schema And Migrations

Structural schema changes are derived from metadata at boot. Data changes are explicit
`AppMigration` beans.

## Rules

- Use `previousNames` for entity or field renames that must preserve data.
- Do not hand-write DDL for catalog/document/register structure.
- Use `onno.schema.mode=plan` to inspect a diff without applying the model diff or `AppMigration`s.
  `plan`/`validate` may still bootstrap the schema-history table.
- Use `onno.schema.mode=validate` in CI-like checks when drift should fail startup.
- Use `AppMigration` for backfills, seeding, and data reshaping.
- Required-column reconciliation runs before `AppMigration`s. Backfill both `NULL` and any neutral
  placeholder the upgrader inserted; finalize constraints explicitly when the type has no safe
  neutral value.
- Keep destructive changes disabled while applying safe renames/additions/backfills. Back up,
  inspect the complete destructive plan, approve exact targets, enable the global gate for one
  controlled run, then disable it and validate/restart on both H2 and PostgreSQL.
- Update docs when public annotation/config behavior changes.

Read [references/examples.md](references/examples.md) before making rename or migration changes.
