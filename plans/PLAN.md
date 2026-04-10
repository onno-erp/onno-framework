# Java 1C:Enterprise Framework — Roadmap

## Design Principles

- **Spring Boot-native** — Spring is the runtime, not an optional adapter
- **Annotations for metadata** (`@Catalog`, `@Document`, `@Attribute`, etc.)
- **Interfaces for behavior** (`Postable`, `BeforeWriteHandler`, etc.) — compile-time enforcement
- **Base classes for standard fields** (`CatalogObject`, `DocumentObject`)
- **`Ref<T>` for lightweight references** — like 1C's `CatalogRef.xxx`
- **JDBI 3** as persistence layer — thin JDBC wrapper, we control schema
- **Addon architecture** — each feature area is a separate Spring Boot starter

## Module Structure

```
onec-framework/                    # core: annotations, metadata, model, persistence, managers
onec-spring-boot-starter/          # auto-configuration for core
onec-ui-starter/                   # (future) admin UI addon
onec-auth-keycloak-starter/        # (future) Keycloak auth addon
onec-kafka-starter/                # (future) microservices/messaging addon
example/                           # demo app
```

## Plans

| Plan | Scope | Status |
|------|-------|--------|
| [Plan #1](PLAN-1.md) | Catalog CRUD — minimal vertical slice | ✅ Done |
| [Plan #2](PLAN-2.md) | Documents + Tabular Sections + Lifecycle Hooks | ✅ Done |
| [Plan #3](PLAN-3.md) | Accumulation Registers + Posting Engine | Next |
| [Plan #4](PLAN-4.md) | Information Registers, Enumerations, Constants | Planned |
| [Plan #5](PLAN-5.md) | Hierarchical Catalogs, Autonumbering, Optimistic Locking | Planned |
| [Plan #6](PLAN-6.md) | Microservices Support (Kafka starter addon) | Planned |
