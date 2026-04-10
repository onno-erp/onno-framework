# Plan #6: Microservices Support (Kafka Starter Addon)

**Status: Planned**

## Context

The core framework (Plans #1–5) produces a monolith: one app, one DB. This plan adds microservices support as a **separate Spring Boot starter addon** (`onec-kafka-starter`). Drop the starter on your classpath → distributed posting, remote Ref resolution, and event-driven communication auto-configure.

**This is the addon architecture proof-of-concept** — same pattern applies to future addons (UI, auth, etc.).

## Module

```
onec-kafka-starter/
├── build.gradle.kts           # depends on onec-framework, spring-kafka, etc.
└── src/main/java/com/onec/kafka/
    ├── OneCKafkaAutoConfiguration.java
    ├── service/                # ServiceDescriptor, ServiceRegistry, RemoteClient
    ├── messaging/              # MessageBus, OutboxWriter, OutboxRelay, CloudEvent
    └── posting/                # InboundPostingHandler, distributed posting
```

## Design Principle

- **Same developer API** — `@Document`, `Postable`, `Ref<T>` all stay identical
- Only `application.yml` config changes: declare which types are remote + Kafka broker
- Uses Spring Kafka, Spring's RestClient natively
- Outbox pattern for reliable event publishing
- Auto-exposes registered entities as REST endpoints for remote Ref resolution

## Addon Pattern (reusable for future starters)

Each addon:
1. Is a separate Gradle module / Maven artifact
2. Has an `@AutoConfiguration` class with `@ConditionalOnClass` guards
3. Reads from `MetadataRegistry` to discover what's registered
4. Extends behavior without modifying core framework code

## Phases

| Phase | Scope |
|-------|-------|
| M1 | Service identity + remote registry in InfoBase |
| M2 | Remote Ref resolution via Spring RestClient |
| M3 | Outbox table + event publishing |
| M4 | Messaging abstraction (Spring Kafka) |
| M5 | Distributed posting engine |
| M6 | Avro schemas + Schema Registry |
| M7 | Debezium CDC (production outbox) |
| M8 | Example: Invoice + Inventory microservices |

## Verification
1. Two Spring Boot services, each with their own DB
2. Post a document in Service A → movements appear in Service B's register
3. Remote `Ref<T>` resolution works across services
4. Kill Kafka mid-posting → outbox ensures eventual delivery
