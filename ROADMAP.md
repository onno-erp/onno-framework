# onno Framework Roadmap

This project is a modern Java/Spring framework for modeling business processes with explicit domain concepts:
catalogs, documents, tabular sections, registers, constants, enumerations, background jobs, UI metadata,
agent-readable manifests, and future service boundaries.

## Current State

Implemented:
- Core metadata annotations and scanners
- Catalogs, documents, tabular sections, accumulation registers
- Information registers, enumerations, constants, background jobs
- JDBI-based schema generation and persistence primitives
- Unified type-safe query layer (`QueryEngine`) over catalogs, documents, and registers, with `Ref`-navigation auto-joins, a declarative `QuerySpec` AST, a fluent builder, and a shared `SqlRenderer` that also backs register virtual tables
- Spring Boot starter auto-configuration
- React/Vite UI starter with generic metadata, catalog, document, and register APIs
- UI auth foundation with login screen, protected routes, `GET /api/auth/me`, JSON `POST /api/auth/login` and `/api/auth/logout`, session cookies, CSRF
- Production auth modes shipped in `onno-auth-starter`: `in-memory`, `oidc` (Keycloak/Zitadel/custom SSO), and `resource-server` (stateless JWT bearer)
- Per-entity, deny-by-default RBAC via `@AccessControl(readRoles, writeRoles)` enforced across REST, UI, and MCP (`ADMIN` is a superuser)
- Structured reference resolution for API rows via `{column}_display` and `{column}_ref`
- Server-sent UI event stream (`GET /api/events`) for live refresh of catalogs, documents, registers, and comment threads
- Agent-readable business model surface via the MCP server (`onno-mcp-starter`, `describe_metadata` tool) — there is no anonymous HTTP manifest endpoint
- Hierarchical catalog fields and generated schema
- Configurable catalog/document autonumbering prefixes
- Optimistic locking fields and generic UI conflict checks
- Durable outbox table and core outbox writer
- Kafka starter foundation with CloudEvent relay, service registry, and remote reference client
- Typed-Java posting (`Postable.handlePosting`) writing document-to-register movements
- Typed business-process language prototype: enum step keys, typed human-task outcomes, node-handle
  transitions, structural validation, transition history, and an executable in-memory engine
- Declarative business rule metadata (`Validated` / `BusinessRule`) and lightweight validation
- Diff-based schema migration: `onno_schema_history` with metadata snapshots, renames via `previousNames`, modes (`apply`/`plan`/`validate`/`off`), destructive-change gating, and versioned `AppMigration` data migrations
- Hierarchical catalog children/tree APIs
- Dry-run posting previews
- Domain event metadata and outbox publication hooks
- `onno-auth-starter` with session-based defaults, JSON login/logout, CSRF cookie, in-memory users via `onno.auth.users`, plus OIDC and resource-server modes
- Integration starters: MCP server (`onno-mcp-starter`), CSV import (`onno-import-starter`), Kafka outbox relay (`onno-kafka-starter`), transactional mail (`onno-mail-starter`), PDF/print (`onno-print-starter`), native desktop packaging (`onno-desktop-starter` + Gradle plugin)
- Horizontal scale-out: cross-node delivery of live-UI entity-change events via a pluggable `ClusterEventBus` (`onno-cluster-starter`, default Postgres `LISTEN`/`NOTIFY`), an advisory-locked schema apply, and a fail-fast remember-me key guard
- Server-driven DivKit UI layer (`/api/divkit/**`) alongside the bundled React/Vite SPA, plus media uploads with a pluggable `MediaStorage` SPI
- UI configuration decoupled from domain: sidebar sections live in `Layout` beans, dashboard widgets live in `Page` beans, and per-field hints live in `EntityView` or `Layout` configuration.
- Unified pages ("everything is a page"): the home dashboard, settings, entity list surfaces, and arbitrary custom routes are all `Page` beans served through one pipeline (`GET /api/divkit/{*route}` catch-all). The framework renders a sensible default at every route; a registered `Page` bean overrides it — including at a default surface route (`/catalogs|documents|registers/{name}`). Pages support `bare()` (no header) and are linked in the nav with `section(...).page(route, label, icon)`.
- Custom widgets: a consumer authors a React component in `src/main/widgets/*.tsx` (via `@onno/widget-sdk`) and applies the `su.onno.widgets` Gradle plugin, which compiles it (managed Node + esbuild, React aliased to the host SPA) into an `onno-plugins/*.js` module served under `{onno.ui.path}/plugins/**` and auto-loaded at boot — a widget type the framework has no built-in for, with no frontend project to maintain.

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
- Turn the typed business-process prototype into a durable runtime: persisted process instances,
  work items, assignee/candidate resolution, claim/complete commands, timers, and a task inbox.
  Persistence and UI must preserve the typed definition API; the current `InMemoryProcessEngine`
  is explicitly a language/semantics spike, not a production workflow engine.
- Add automatic steps, typed decisions, parallel fork/join, and subprocess nodes to the process graph
- UI widgets for hierarchy browsing and posting-preview inspection
- Richer live collaboration signals, such as record-level locks and stale-record banners (record-level **presence markers** now ship — see below)
- Auto-scheduled / retrying Kafka outbox relay (today `OutboxRelay.relayPending()` is driven by the app's own `@Scheduled` bean)
- More generated test fixtures from business models
- Tabular-section field hints in the authored UI DSL
- A native (Flutter) client driven by the same DivKit contract the React SPA uses today

Recently shipped (formerly on this list): role-aware deny-by-default authorization
(`@AccessControl`), OIDC/Keycloak production auth, diff-based migration with snapshots and model
diffs, the server-driven DivKit UI layer, horizontal scale-out (`onno-cluster-starter`), and
record-level presence markers (live "who else is viewing this record" avatars, cluster-relayed).

## Auth Direction

Current auth ships in `onno-auth-starter` with three modes selected by `onno.auth.mode`:
- `in-memory` (default): JSON `POST /api/auth/login` and `/api/auth/logout`, session-backed (`JSESSIONID`), CSRF via `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header, optional remember-me. In-memory users from `onno.auth.users[*]`; the example seeds admin/rentals/finance/cleaner from `application.yaml`.
- `oidc`: server-side OpenID Connect SSO (Keycloak/Zitadel/custom) with realm/client role mapping and RP-initiated logout.
- `resource-server`: stateless JWT bearer validation for API-only clients.
- 401 JSON entry point for unauthenticated API calls; SPA login screen wired to it.
- Per-entity authorization is deny-by-default via `@AccessControl(readRoles, writeRoles)`; `ADMIN` is a superuser.
- Any consumer can set `onno.auth.enabled=false` and override the `SecurityFilterChain`, `UserDetailsService`, or `PasswordEncoder` bean.
- Delegated "connect external account" / "Connect with X" flows (distinct from logging a user *in*) build on generic core UI primitives — action-button `.logo(...)` and `ActionResult.redirect(...)` for a full-page consent redirect — so an app can hand-roll the callback on just the core; the turnkey authorization-code mechanics (`AuthorizationCodeFlow` + `TokenStore` that seeds a connector's `OAuthTokenManager`) live in the commercial `onno-connector-support`.

Production direction:
- Replace the demo `InMemoryUserDetailsManager` with a JDBC-backed `UserDetailsService` for real deployments.
- Finer-grained action-level authorization (post/unpost/administer) layered on the entity-level roles.

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
