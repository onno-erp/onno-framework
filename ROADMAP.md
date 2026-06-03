# onec Framework Roadmap

This project is a modern Java/Spring framework for modeling business processes with explicit domain concepts:
catalogs, documents, tabular sections, registers, constants, enumerations, background jobs, UI metadata,
agent-readable manifests, and future service boundaries.

## Current State

Implemented:
- Core metadata annotations and scanners
- Catalogs, documents, tabular sections, accumulation registers
- Information registers, enumerations, constants, background jobs
- JDBI-based schema generation and persistence primitives
- Spring Boot starter auto-configuration
- React/Vite UI starter with generic metadata, catalog, document, and register APIs
- UI auth foundation with login screen, protected routes, `/api/ui/auth/me`, JSON `/login` and `/logout`, session cookies, CSRF
- Structured reference resolution for API rows via `{column}_display` and `{column}_ref`
- Server-sent UI event stream for live refresh of catalogs, documents, and registers
- Agent-readable business model manifest at `/api/ui/metadata/manifest`
- Hierarchical catalog fields and generated schema
- Configurable catalog/document autonumbering prefixes
- Optimistic locking fields and generic UI conflict checks
- Durable outbox table and core outbox writer
- Kafka starter foundation with CloudEvent relay, service registry, and remote reference client
- Declarative posting rules for simple document-to-register movements
- Declarative business rule metadata and lightweight validation
- Additive schema migration for generated columns and attributes
- Hierarchical catalog children/tree APIs
- Dry-run posting previews
- Domain event metadata and outbox publication hooks
- `onec-auth-spring-boot-starter` with session-based defaults, JSON login/logout, CSRF cookie, in-memory users via `onec.auth.users`
- UI configuration decoupled from domain: sidebar sections live in `Layout` beans, dashboard widgets live in `Page` beans, and per-field hints live in `EntityView` or `Layout` configuration. The `@UiHint`, `@UiSection`, and `@DashboardWidget` annotations are deprecated; authored UI configuration overrides the annotations when both are present. `/api/ui/metadata/dashboard` and `/manifest` both read from authored pages/layouts.

## Design Direction

The framework should help a user or agent model the business first, then generate conventional Java/Spring
runtime behavior from that model.

It should remain friendly to normal Java:
- Use Spring services for complex business policies.
- Use lifecycle hooks for entity-local validation and calculations.
- Use registers for auditable balances and turnovers.
- Use Spring Security and conventional identity providers for authentication and authorization.
- Use contexts to mark future service boundaries.
- Use the outbox and Kafka starter when a monolith starts growing into services.

## Near-Term Next Work

Good next slices:
- Role-aware UI metadata and backend authorization rules for catalogs, documents, registers, and actions
- OIDC/Keycloak profile for production authentication
- UI widgets for hierarchy browsing and posting preview inspection
- Richer live collaboration signals, such as record-level locks and stale-record banners
- Migration snapshots and model diffs, not only additive column migration
- Scheduled/retrying outbox relay
- More generated test fixtures from business manifests
- Tabular-section field hints in the authored UI DSL so `@UiHint` can be deleted entirely when custom row-field hints are needed.
- DivKit renderer prototype: emit DivKit JSON from existing descriptors + layout, mount alongside the React renderer behind a `?renderer=divkit` flag, validate against list/form/dashboard surfaces

## Auth Direction

Current auth ships in `onec-auth-spring-boot-starter`:
- JSON `POST /api/ui/auth/login` and `/logout`, session-backed (`JSESSIONID`), CSRF via `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header.
- 401 JSON entry point for unauthenticated API calls; SPA login screen wired to it.
- In-memory users configured via `onec.auth.users[*]`; the example seeds admin/sales/warehouse from `application.yaml`.
- Any consumer can override the `SecurityFilterChain`, `UserDetailsService`, or `PasswordEncoder` bean to customize.

Production direction:
- Support OIDC/Keycloak as the main production path (additional starter profile).
- Add role-aware metadata so agents can model who can view, edit, post, unpost, delete, and administer each business object.
- Replace the demo `InMemoryUserDetailsManager` with a JDBC-backed `UserDetailsService` for real deployments.

## Future Service Work

The Kafka starter is a foundation, not a full distributed business engine yet.

Remaining deeper work:
- Automatic remote `Ref<T>` resolution inside the framework resolvers
- Distributed posting protocol between bounded contexts
- Avro/schema-registry support
- CDC/Debezium production outbox option
- Two-service executable example, such as Sales plus Inventory

## Agent Workflow

Use [AGENTS.md](AGENTS.md) as the playbook for interviewing a user and translating business docs into framework code.
