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
| UI add-on | `Page`/`Layout`/`EntityView`, actions, or custom widgets |
| Skill/plugin | Agent guidance for a domain or integration |

Use `docs/EXTENDING.md` for public community integrations and `../onno/reference/connectors.md` for
commercial connector patterns.

## Starter Checklist

- `java-library`
- `maven-publish`
- `withSourcesJar()` and `withJavadocJar()`
- Auto-config imports file at
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `@ConfigurationProperties(prefix = "onno.<name>")` with Javadoc for generated config docs
- `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")`
- `@ConditionalOnMissingBean` on replaceable beans
- README or root docs explaining how consumers enable it

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

Then verify `INTEGRATIONS.md` is regenerated from the registry, not edited by hand.

## Docs Sync

Changing an extension surface, naming convention, module boundary, or public starter contract means
updating `docs/EXTENDING.md`, `README.md`, `docs/ARCHITECTURE.md`, and any owning module README in the
same change.
