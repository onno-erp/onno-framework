# Plan #1: Minimal Vertical Slice — Catalog CRUD End-to-End

**Status: ✅ Done (needs Spring-native refactor + tests)**

## Context

We're building a Java framework replicating 1C:Enterprise's core concepts. This plan takes a thin vertical slice: one 1C concept (Catalog) working end-to-end against H2, proving the architecture before expanding.

## ORM Choice: JDBI 3

**JDBI** (`org.jdbi:jdbi3-core`) — thin JDBC wrapper, zero opinions on schema or entity lifecycle.

- **Not Hibernate** — it owns schema generation via `@Entity`/`@Table`, conflicts with our custom annotations
- **Not jOOQ** — sweet spot is code-gen from existing schema; we go the opposite direction
- **Not raw JDBC** — boilerplate is exactly what caused the original plan to propose 4 custom helper classes

JDBI gives us named parameters, clean Handle API, and custom RowMapper support while we keep 100% control over DDL and table naming.

## What's Implemented

All core functionality is working:
- Gradle skeleton with `framework`, `framework-spring-boot-starter`, `example` modules
- `@Catalog`, `@Attribute` annotations
- `CatalogObject` base class, `Ref<T>` record
- Full metadata layer: `MetadataScanner`, `MetadataRegistry`, `NamingStrategy`, `TypeMapping`, descriptors
- `SchemaGenerator` — DDL generation + execution
- `CatalogPersistence` — generic CRUD via JDBI + reflection
- `CatalogManager` — developer-facing API
- `InfoBase` — builder bootstrap
- Spring Boot auto-configuration + `Product` example app with REST controller

## Remaining Work

### Spring-native refactor
- `InfoBase` currently takes `jdbcUrl` and creates its own `Jdbi` instance
- Should accept Spring's `DataSource` instead — the auto-configuration already has access to it
- `CatalogPersistence` should use Spring-managed `DataSource` via JDBI's `Jdbi.create(dataSource)`

### Tests (not written yet)
- `MetadataScannerTest` — verify annotation scanning produces correct descriptors
- `SchemaGeneratorTest` — verify DDL output for various attribute types
- `CatalogCrudTest` — full integration: bootstrap → create → read → update → delete

## Verification
1. `./gradlew build` — compiles both modules
2. `./gradlew :framework:test` — MetadataScanner, SchemaGenerator, and full CRUD integration tests pass
3. `./gradlew :example:run` — prints Product CRUD output to console
