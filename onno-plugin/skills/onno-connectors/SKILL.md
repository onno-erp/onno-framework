---
name: onno-connectors
description: >-
  Build onno-framework connectors and external-system integration starters. Use when creating a
  community connector, commercial onno-enterprise connector, typed HTTP client, token manager,
  external sync service, host-side event listener, polling job, outbox/Kafka handoff, RefResolver
  mapping, connector audit/idempotency table, or deciding what belongs in a connector versus the
  consuming app's business model.
---

# onno Connectors

A connector wraps an external API. It does not own the host business model.

## Rules

- Host apps define catalogs, documents, registers, posting, and UI.
- Connector starters provide typed clients, token managers, sync services, config properties, and
  optional connector-owned audit tables.
- Use `RefResolver` to map host `Ref<T>` values into external schemas.
- React to domain events with Spring beans, not entity hooks.
- Make external calls idempotent and retry-aware.

Read [references/examples.md](references/examples.md) and `../onno/reference/connectors.md`.
