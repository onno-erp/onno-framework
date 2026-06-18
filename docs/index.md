---
layout: home

hero:
  name: onno-framework
  text: Model the business. Generate the rest.
  tagline: >-
    Reusable Spring Boot starters that turn typed Java metadata — catalogs, documents, registers —
    into a database schema, a generic REST API, a server-driven UI, and an MCP tool surface for AI agents.
  actions:
    - theme: brand
      text: Architecture
      link: /ARCHITECTURE
    - theme: alt
      text: Configuration reference
      link: /CONFIGURATION
    - theme: alt
      text: View on GitHub
      link: https://github.com/onno-erp/onno-framework

features:
  - icon: 🗂️
    title: Model the business
    details: Catalogs, documents, tabular sections, accumulation/information registers, enumerations, and constants — all typed Java, compiler-checked. No tables or DTOs by hand.
  - icon: ⚙️
    title: Generated, not hand-written
    details: Schema, repositories, a type-safe query layer, the generic REST API, and migration history are all derived from the model at boot.
  - icon: 🖥️
    title: Server-driven UI + MCP
    details: A DivKit admin UI and a Model Context Protocol tool surface for AI agents, both generated from the same metadata and gated by the same RBAC.
  - icon: 📄
    title: Docs from the source of truth
    details: The configuration reference is generated from the @ConfigurationProperties Javadoc — and the build fails if it ever drifts from the code.
---

## Get started

Add the starter (Java 21, Spring Boot 3.4.x). Packages are `su.onno.*`; the Maven group is
`su.onno` (open core, Apache-2.0).

```xml
<dependency>
  <groupId>su.onno</groupId>
  <artifactId>onno-framework-starter</artifactId>
  <version>0.10.0</version>
</dependency>
```

Then read **[Architecture](/ARCHITECTURE)** for how it fits together, or the
**[handoff guide for building an ERP on the published libraries](https://github.com/onno-erp/onno-framework/blob/main/BUILDING_ERPS_WITH_AGENTS.md)**.

## How these docs are built

This site mixes **hand-written guides** with **reference generated from the source of truth**, so the
reference can't drift from the code:

| Surface | Source of truth | How it's produced |
| --- | --- | --- |
| [Configuration properties](/CONFIGURATION) | the `@ConfigurationProperties` Javadoc | `./gradlew generateConfigDocs` renders each starter's `spring-configuration-metadata.json`; `./gradlew check` fails if the committed file drifts |
| [Java API (Javadoc)](https://docs.onno.su/api/) | the Java source | `./gradlew aggregateJavadoc` |
| Architecture, guides | maintained by hand | written Markdown |

Changing a property? Edit its Javadoc and run `./gradlew generateConfigDocs` — never hand-edit the
table. See [Keeping docs in sync](https://github.com/onno-erp/onno-framework/blob/main/AGENTS.md#keeping-docs-in-sync).
