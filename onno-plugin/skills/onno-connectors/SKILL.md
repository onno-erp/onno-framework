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
- Credentialed connectors default disabled and require explicit opt-in.
- Keep connector services in external DTOs. The host owns entity mapping, repositories,
  `RefResolver`, projections, listeners, and scheduled jobs.
- React to domain events with Spring beans, not entity hooks.
- Advance pull cursors only after host projections commit; make replay idempotent by external id and
  revision.
- Bound retries. Refresh once on 401; honor `Retry-After` plus jitter for 429/5xx; retry writes only
  with a stable provider idempotency key.
- Package Spring configuration metadata with the connector and test auto-configuration, token
  refresh, pagination/replay, artifact contents, and a Maven-local external consumer.
- After public release, validate and add one community registry entry per installable artifact, then
  regenerate `INTEGRATIONS.md`.

Read [references/examples.md](references/examples.md) and `../onno/reference/connectors.md`.
