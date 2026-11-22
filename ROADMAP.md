# onno Framework Roadmap

This project is a modern Java/Spring framework for modeling business processes with explicit domain concepts:
catalogs, documents, tabular sections, registers, constants, enumerations, background jobs, commands,
a React UI driven by the model, an agent-readable model surface, and future service boundaries.

## Current State

Implemented:
- Core metadata annotations and scanners
- Catalogs, documents, tabular sections, accumulation registers
- Information registers, enumerations, constants, background jobs
- JDBI-based schema generation and persistence primitives
- Spring Boot starter auto-configuration
- Web starter (`onno-web-starter`) with the generic catalog, document and register APIs and the `/api/ui` model
- UI auth foundation with login screen, protected routes, `GET /api/auth/me`, JSON `POST /api/auth/login` and `/api/auth/logout`, session cookies, CSRF
- Production auth modes shipped in `onno-auth-starter`: `in-memory`, `oidc` (Keycloak/Zitadel/custom SSO), and `resource-server` (stateless JWT bearer)
- Typed role grants: roles are an application `RoleKey` enum and one `RoleGrants` bean declares each role's entity rights (read < write < post/unpost) and capability `Permission`s (`settings.edit`, `users.manage`, `periodClose.manage`, …), enforced across REST, UI, MCP and SSE with no superuser; an app without grants runs open, IdP roles map by normalized name or `fromIdp(...)`, and `model.gen.ts` types roles and permissions
- Document post/unpost rights separate from write access: `RoleGrants` grants `post(...)` and `unpost(...)` on their own, deciding who may post, re-post or unpost (and delete a posted document) on REST, MCP and the built-in UI actions; `BeforeUnpostHandler` lets the domain veto an unpost
- Record-level access policies (`RecordAccessPolicy` + `RecordScope`): declarative, query-pushed-down "which records" rules for external users, enforced on every generic read/write path (lists, aggregates, search, refs, writes, actions, import, comments, tags, presence, notifications, SSE, MCP, CRM inbox) with a shipped leak-test conformance suite
- Structured reference resolution for API rows via `{column}_display` and `{column}_ref`
- Record change history: opt-in `@Catalog/@Document(history = true)` field-level audit log (who, when, old and new value; tabular-section rows; post/unpost/delete events) recorded on the generic command path and typed repository saves, with a History panel on the default record page (`<RecordHistory />`) and `GET /api/history/{kind}/{name}/{id}` under the record's own read access
- Server-sent UI event stream (`GET /api/events`) for live refresh of catalogs, documents, registers, and comment threads
- Agent-readable business model surface via the MCP server (`onno-mcp-starter`, `describe_metadata` tool) — there is no anonymous HTTP manifest endpoint
- Application-defined MCP tools via authenticated, role-scoped `@McpTool` Spring bean methods, with an SDK-level `McpToolProvider` extension point
- Hierarchical catalog fields and generated schema
- Configurable catalog/document autonumbering prefixes
- Optimistic locking fields and generic UI conflict checks
- Durable outbox table and core outbox writer
- Typed-Java posting (`Postable.handlePosting`) writing document-to-register movements
- Complete durable typed business processes: version-pinned definitions and explicit typed
  migrations; human, automatic, decision, timer, structured parallel fork/join, subprocess, and
  terminal nodes; token-based execution with nested branches; cancellation; persisted
  instances/tokens/transitions; role/user candidate work items; claim/delegate/complete commands;
  durable task audit; authenticated process/task APIs; stable identity-record ownership; typed
  task-to-domain links; employee-backed delegation picker; background timer/subprocess advancement;
  and the live task inbox (`TaskInbox`)
- Shared typed field tokens across Java APIs: getter references for register queries, command
  predicates and titles, and check dependencies
- Declarative business rule metadata (`Validated` / `BusinessRule`) and lightweight validation
- Diff-based schema migration: `onno_schema_history` with metadata snapshots, renames via `previousNames`, modes (`apply`/`plan`/`validate`/`off`), destructive-change gating, and versioned `AppMigration` data migrations
- Hierarchical catalog children/tree APIs
- Dry-run posting previews
- Period close (`PeriodCloseService`): global / per-context / per-register / per-organization, soft or hard change-prohibition dates, `@PeriodAdjustment` documents, enforced in core posting (including chronological restoration), repository saves and the generic command path; audited close, reopen, adjustment and role override; `/api/period-close`, the `period_close_status` MCP tool and the `@onno/ui` `<PeriodClose />` admin component. See `docs/PERIOD_CLOSE.md`.
- Domain event metadata and outbox publication hooks
- `onno-auth-starter` with session-based defaults, JSON login/logout, CSRF cookie, in-memory users via `onno.auth.users`, plus OIDC and resource-server modes
- Integration starters: MCP server (`onno-mcp-starter`), CSV import (`onno-import-starter`), Word/Excel print forms with batch printing, admin-uploaded templates and PDF (`onno-print-starter`)
- Horizontal scale-out: cross-node delivery of live-UI entity-change events via a pluggable `ClusterEventBus` (in `onno-framework-starter`, default Postgres `LISTEN`/`NOTIFY`), an advisory-locked schema apply, and a fail-fast remember-me key guard
- Media uploads with a pluggable `MediaStorage` SPI
- The UI is the npm package `@onno/ui`: a React shell with a default list, record form and register report for every readable entity, built from the model the server describes (`/api/ui/model`, `/api/ui/view/**`). An app's own UI is an ordinary Vite app in `src/main/ui` — one `defineApp({ entities, pages, navigation, extensions })` call with typed entity customizations, React pages composed from `@onno/ui`'s blocks, widgets and typed data hooks, and the starters' packages (`@onno/crm`, `@onno/print`). The `su.onno.ui` Gradle plugin builds it into the jar and generates `model.gen.ts` from the Java model. (This replaces the earlier Java UI DSL — `Layout`/`Page`/`EntityView` beans — the DivKit renderer and the widget plugin.)
- Replaceable UI SDK: the default record host shares its public `useRecordForm` controller with custom hosts; typed field bindings (`useRecordField`/`RecordField`) carry validation and picker context; custom list bodies receive the same pagination, selection and scoped select-all callbacks. Metadata, control props and rendering primitives are public (`docs/UI_SDK.md`).
- Entity behaviour as Java beans exposed on every surface: `Commands` (REST, UI and one MCP tool per command, with roles, inputs and per-record availability enforced by the server), live form `Checks`, reference `PickerOptions`, and the login → record `IdentityLink`.

## Design Direction

The framework should help a user or agent model the business first, then generate conventional Java/Spring
runtime behavior from that model.

It should remain friendly to normal Java:
- Use Spring services for complex business policies.
- Use lifecycle hooks for entity-local validation and calculations.
- Use registers for auditable balances and turnovers.
- Use Spring Security and conventional identity providers for authentication and authorization.
- Use contexts to mark future service boundaries.
- Use the outbox, drained by an application-owned relay, when a monolith starts growing into services.

## Near-Term Next Work

Good next slices:
- UI widgets for hierarchy browsing and posting-preview inspection
- Richer live collaboration signals, such as record-level locks and stale-record banners (record-level **presence markers** now ship — see below)
- More generated test fixtures from business models
- A native mobile client on the same `/api/ui` model and `onno://` link protocol the web UI uses

Recently shipped (formerly on this list): role-aware deny-by-default authorization
(`RoleGrants`), OIDC/Keycloak production auth, diff-based migration with snapshots and model
diffs, the React-only UI on `@onno/ui`, horizontal scale-out (cluster bus in the framework starter), and
record-level presence markers (live "who else is viewing this record" avatars, cluster-relayed).

## Auth Direction

Current auth ships in `onno-auth-starter` with three modes selected by `onno.auth.mode`:
- `in-memory` (default): JSON `POST /api/auth/login` and `/api/auth/logout`, session-backed (`JSESSIONID`), CSRF via `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header, optional remember-me. In-memory users from `onno.auth.users[*]`; the example seeds admin/rentals/finance/cleaner from `application.yaml`.
- `oidc`: server-side OpenID Connect SSO (Keycloak/Zitadel/custom) with realm/client role mapping and RP-initiated logout.
- `resource-server`: stateless JWT bearer validation for API-only clients.
- 401 JSON entry point for unauthenticated API calls; SPA login screen wired to it.
- Authorization comes from the application's `RoleGrants` bean (deny-by-default once declared; no superuser); admin capabilities are named `Permission`s an IdP role can be granted through `fromIdp(...)`.
- Any consumer can set `onno.auth.enabled=false` and override the `SecurityFilterChain`, `UserDetailsService`, or `PasswordEncoder` bean.
- Delegated "connect external account" / "Connect with X" flows (distinct from logging a user *in*) belong to the connector boundary, including its authorization redirect/callback endpoint. Generic command results carry structured feedback, refresh intent and an optional in-app `navigateTo` only; the turnkey authorization-code mechanics (`AuthorizationCodeFlow` + `TokenStore` that seeds a connector's `OAuthTokenManager`) live in the commercial `onno-connector-support`.

Production direction:
- Replace the demo `InMemoryUserDetailsManager` with a JDBC-backed `UserDetailsService` for real deployments.
- Finer-grained action-level authorization (post/unpost/administer) layered on the entity-level roles.

## Future Service Work

The framework ships the transactional outbox table, not a broker transport; service splits are not a
full distributed business engine yet.

Remaining deeper work:
- Automatic remote `Ref<T>` resolution inside the framework resolvers
- Distributed posting protocol between bounded contexts
- Avro/schema-registry support
- CDC/Debezium production outbox option
- Two-service executable example, such as Sales plus Inventory

## Agent Workflow

Use [AGENTS.md](AGENTS.md) as the playbook for interviewing a user and translating business docs into framework code.
