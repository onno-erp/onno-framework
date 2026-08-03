---
name: onno-extensions
description: >-
  Build or document onno-framework extensions: community integrations, commercial connectors,
  Spring Boot starters, SPI implementations, UI add-ons, skills/plugins, Kafka/outbox boundaries,
  and onno-enterprise connector patterns. Use when adding a starter, connector, widget package,
  auth/media/mail/print/storage SPI implementation, community registry entry, or deciding what
  belongs in open-core versus a separate extension artifact.
---

# onno Extensions

Extend onno without forking. Ship a separate artifact that a host app opts into.

## Extension Types

| Type | Shape |
| --- | --- |
| Connector | Spring Boot starter wrapping an external API |
| SPI implementation | Bean implementing a framework extension point |
| UI add-on | Additive `Page`/`Layout`/`EntityView`, actions, or custom widgets |
| Skill/plugin | Agent guidance for a domain or integration |
| Observability exporter | Consumer of the observability export SPI |
| MCP tool pack | Additive `@McpTool` methods or `McpToolProvider` beans |

Use `docs/EXTENDING.md` for public community integrations and `../onno/reference/connectors.md` for
commercial connector patterns.

## Starter Checklist

- `java-library`
- `maven-publish`
- `withSourcesJar()` and `withJavadocJar()`
- Auto-config imports file at
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `@ConfigurationProperties(prefix = "onno.<name>")` with Javadoc plus the Spring configuration
  processor. Third-party metadata stays in that artifact; it does not enter this repository's
  generated `docs/CONFIGURATION.md`.
- `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")`
- `@ConditionalOnMissingBean` only on singleton replacement SPIs. Additive `Page`, `Layout`,
  `EntityView`, contributor, and tool beans must coexist.
- Order a replacement auto-configuration before the framework fallback it replaces.
- Use the publisher's own Maven group and Java package; `su.onno.*` is reserved.
- README or root docs explaining how consumers enable it
- Unit and `ApplicationContextRunner` tests, artifact-content checks, `publishToMavenLocal`, and a
  tiny external-consumer smoke test

## Connector Principle

A connector wraps an external API; it does not model the host business. The consuming app owns
catalogs, documents, registers, posting, and UI. Connector code may use `Ref<T>` and `RefResolver` to
map app-domain references into external payloads.

Robust HTTP connectors should map non-2xx responses to typed exceptions, refresh credentials once on
401, back off on 429/5xx, and keep stateful audit/idempotency tables under an `onno_` prefix when
needed.

## Community Listing

To list a community extension, update `community/registry.json`, run:

```bash
./gradlew generateIntegrationsDoc
```

Validate the registry against `community/registry.schema.json` first. Then verify
`INTEGRATIONS.md` is regenerated from the registry, not edited by hand. Create one entry per
independently installable artifact and include its supported onno version, license, public README,
coordinates, and repository.

## Docs Sync

Changing an extension surface, naming convention, module boundary, or public starter contract means
updating `docs/EXTENDING.md`, `README.md`, `docs/ARCHITECTURE.md`, and any owning module README in the
same change.
