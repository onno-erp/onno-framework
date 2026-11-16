# Consumer gotchas

The short list of things that bite when building an app on the published `su.onno:*` libraries —
each of these has cost a real debugging session. Verified against **v1.11.1**. Companion pages:
[CONFIGURATION.md](CONFIGURATION.md) for every property, [HEADLESS_READ_API.md](HEADLESS_READ_API.md)
for the wire contract, [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit.

> **Keep this current.** When a gotcha below is fixed or its behaviour changes, update this page in
> the same PR. See [Keeping docs in sync](../AGENTS.md#keeping-docs-in-sync).

## Auth & session

- **Add public paths with `onno.auth.additional-public-paths`.** The framework's own public paths
  (`/error`, `/api/theme`, `/api/config`, `/api/branding`, `/api/auth/login`, `/api/auth/me`,
  `/api/auth/csrf`, `/api/ui/login`) always stay
  public; your patterns are added to them. `onno.auth.csrf-ignored-paths` defaults to just
  `[/api/auth/login]` — SSO callback paths (e.g. `/api/auth/telegram/**`) must be added to **both**.
- **A blank `onno.auth.session.remember-me.key` fails startup on purpose.** Earlier versions
  silently rotated the key per boot, which invalidated every session cookie on redeploy. Now the app
  refuses to start with a clear message; for dev, `onno.auth.session.remember-me.allow-ephemeral-key: true`
  opts into a fixed, non-secret built-in key (cookies survive restarts, but never use it in prod).
  Remember-me is on by default, validity 14d; idle session timeout is `onno.auth.session.timeout`
  (default 8h, sliding).
- **Demo login buttons live under `onno.ui.login.demo-accounts`** (list of
  `{label, username, password}`) — under `onno.ui`, not `onno.auth`. They render on the password
  step of the login screen.

## Record-level access

- **Typed repositories are not record-scoped.** `RecordAccessPolicy` is enforced by the generic
  REST/UI/MCP surfaces and the service layer's query/command services (`su.onno.service`). `CatalogRepository`,
  `DocumentRepository` and `RegisterRepository` are trusted code (posting, jobs, processes) and see
  every record. A custom controller, command handler or `@McpTool` that serves an external user from a
  repository must check with `RecordAccess.require(subject, Type.class, id, AccessMode.READ)` (or
  `filter(...)` for many ids).
- **Never pass `AccessSubject.system()` on a user's behalf.** It bypasses every policy. Request code
  declares an `AccessSubject` controller parameter (or calls `AccessSubjectResolver.resolve(principal)`);
  only background work uses `system()`.
- **A scoped user without an identity record sees nothing.** `Subject.recordId()` comes from the
  `IdentityLink` bean; if the login matches no identity row, every scope that reads it is false.
- **Refs into unreadable entities render restricted — even without policies.** Since 3.4 a ref whose
  target entity the viewer can't read shows `—` with `{field}Restricted: true` and no id. Grant read
  access on the target if users need to see those names.
- **Media is not record-bound.** `/api/media/{key}` is served to any signed-in user who has the key.
  Don't store customer-private files as media when external users exist.
- **On PostgreSQL, scope indexes are built in the background.** With policies declared and
  `onno.schema.mode` at its `apply` default (blank counts as `apply`), a missing index on a scoped
  column is built with `CREATE INDEX CONCURRENTLY`. Neither startup nor writes wait for it, but until
  it finishes, scoped queries on a large table scan it, and a schema change to that table (say, the
  next deploy's) waits for it while blocking the table's reads and writes. See
  [Startup validation](RECORD_ACCESS_POLICIES.md#startup-validation).

## Configuration

- **Read `onno.*` settings through their typed properties beans, not `@Value` or
  `Environment.getProperty`.** A default lives on the `@ConfigurationProperties` field
  (`OnnoProperties`, `UiProperties`, …), not in the `Environment`, so
  `environment.getProperty("onno.schema.mode", "update")` returns your fallback, not `apply`, whenever
  the property is unset. Inject the properties bean instead; interpret the schema mode with
  `SchemaMode.fromString`, where a blank value means `apply`.

## UI chrome & localization

- **`onno.ui.messages` is a fixed key namespace (~190 keys); unknown keys are silently ignored.**
  The authoritative list is `su.onno.web.UiMessages.DEFAULTS` — grep it before a localization pass.
  Resolution is three layers: English defaults → `onno.ui.locale` bundle (`ru` ships complete; a
  consumer app can add `onno/messages/messages-<locale>.properties` on the classpath, which wins) →
  explicit per-key `onno.ui.messages.*` overrides.
- **Entity/attribute `name` is the API contract — keep it ASCII/URL-safe.** It is the REST path
  segment and the write-path field key. Localize through `title`/`displayName`/`label(...)`/
  `@EnumLabel`, never by renaming `name` (renaming it breaks every client). Metadata-derived table
  and column identifiers must match `[A-Za-z_][A-Za-z0-9_]*`; startup rejects unsafe punctuation
  before it can reach generated SQL. The old presence/SSE 403 on non-ASCII route segments is fixed,
  but the route naming rule still stands.

## Model & lifecycle

- **There is no `OffsetDateTime`/`ZonedDateTime`** — use `Instant` for an absolute moment.
  Schema generation throws at boot with the supported list: `String`, `int/Integer`, `long/Long`,
  `boolean/Boolean`, `double/Double`, `float/Float`, `BigDecimal`, `UUID`, `LocalDate`,
  `LocalDateTime`, `LocalTime`, `Instant`, enums, `Ref<T>`.
- **Changing a `LocalDateTime` field to `Instant` is a flagged (unsafe) type change.** PostgreSQL
  converts the existing wall times using the database session zone; check that zone (or migrate the
  values yourself) before applying it.
- **No dependency injection inside `rules()`, `beforeWrite`, `beforePost`, `handlePosting`,
  `afterPost`.** In-transaction hooks run on plain reflectively-built instances — `@Autowired`
  fields are null. Anything needing Spring beans belongs in an `@EventListener` on
  `DocumentPostedEvent` / `DocumentUnpostedEvent` / `EntityChangedEvent`.

## Wire contract

- **Logical entity JSON is the default; storage JSON is an explicit compatibility mode.**
  Catalog/document reads and writes use `id`, `description`, `posted`, attribute `fieldName`s, and
  sidecars such as `customerDisplay`. Add `?representation=storage` only for a legacy read client.
  Writes also accept storage aliases during migration, but conflicting logical/storage values are
  `400`; updates remain partial. Full tables are in [HEADLESS_READ_API.md](HEADLESS_READ_API.md).
- **An enum value is its deterministic UUID, never the constant name.** The UUID is derived from
  `FQCN.CONSTANT`, so it is stable across databases; the write path rejects `"NEW"` where it
  expects the UUID. Display strings/colors come from `@EnumLabel` and ride the `FieldDisplay` /
  `FieldColor` sidecars (`{col}_display` / `{col}_color` in storage compatibility mode).
- **Temporal reads and writes are ISO-8601 wall-clock values.** `LocalDate` reads/writes as
  `yyyy-MM-dd`; `LocalDateTime` reads/writes as offset-free `yyyy-MM-ddTHH:mm[:ss[.fraction]]`.
  Since `LocalDateTime` has no zone, writes containing `Z` or a numeric offset are rejected with a
  field-specific `400`; choose the intended wall time and send it without an offset. The read API
  still normalizes PostgreSQL/JDBC timestamp representations and the bundled form normalizes loaded
  values before resubmitting them. Default logical reads can round-trip writable values without
  renaming keys; display/ref/color companions are read-only and ignored by partial writes.
- **`LocalTime` is `HH:mm[:ss[.fraction]]`; `Instant` is the opposite of `LocalDateTime`.** An
  `Instant` write must carry `Z` or a numeric offset (an offset-free value is a `400` — the server
  never guesses a zone); reads always come back as ISO UTC (`…Z`). The UI shows and edits instants
  in `onno.ui.time-zone` when set, else in each viewer's browser zone.

## SPA & static assets

- **The SPA fallback is navigation-only.** A `GET` under `onno.ui.path` falls back to
  `index.html` only when the request accepts `text/html` and the path does not look like a file.
  Missing assets and unknown `/api/**` routes return `404`. Spring Boot's standard consumer static
  locations remain available; the UI's assets keep their own handler. If an API call returns HTML, verify that a proxy has not rewritten it to a UI route.

## Live updates (SSE)

- **`/api/events` emits only NAMED events — `EventSource.onmessage` receives nothing.** Subscribe
  with `addEventListener` per event name. Current names: `created`, `updated`, `deleted`, `posted`,
  `unposted`, `changed` (entity changes; payload `{type, entityType, entityName, id, naturalKey,
  timestamp}`), plus `ready` (carries `bootId`/`devMode`), `reload`, `presence`, `notification`,
  and audience-scoped `tasks-changed`.
  Keepalives are SSE comments.
- **The stream is lossy by design.** There is no `Last-Event-ID`/since replay; on reconnect
  (client retries every 3s) refetch the surfaces you care about. Events are role-filtered per
  subscriber. In a browser, `@onno/ui` already multiplexes one connection per origin (Web Locks
  leader + BroadcastChannel) — don't open a second one per tab.
- **App components never open an `EventSource`.** `@onno/ui`'s data hooks (`useRecords`,
  `useAggregate`, …) refresh themselves on entity changes; `useUiEvents(listener, filter)` covers
  other events. Both reuse the shell's authenticated, reconnecting, cross-tab stream; a raw
  connection bypasses that sharing and session recovery.

## App UI

- **Import host primitives from `@onno/ui`** — `Button`, `Badge`, `Input`, `Label`, `Textarea`,
  `Checkbox`, `Switch`, `Segmented`, `DatePicker`, `Card`, `Popover`, `Select`, … — instead of
  rebuilding lookalikes. The app's Vite config is `plugins: [onno()]` from `@onno/ui/vite`, which
  sets up React and Tailwind with the shell's theme tokens; use literal class strings (Tailwind
  can't see runtime-concatenated names like `` `text-${c}` ``) or inline `style` with
  `hsl(var(--primary))`.
- **`model.gen.ts` is generated — never edit or commit it.** `generateUiModel` rewrites it from the
  compiled Java model before every UI build; after changing an entity, run it (or any build) so the
  types catch up.
- **Enum values in `where` and on records are UUIDs.** Compare with the generated constants
  (`order.status === OrderStatus.NEW`), not the constant name.

## Forms

- **The New form prefills from query params.** Append write-path field names to the New route —
  `/ui/documents/reservations/new?room=<uuid>&startsAt=2026-07-16T19:00`, or build it with
  `links.newRecord("Reservations", { room, startsAt })`. `Ref`/enum values are UUID strings,
  temporals ISO, everything else verbatim; unknown keys and bad values are skipped silently. Prefill applies after `OnFillingHandler` and
  field initializers. This is the way to seed a `Ref` default (a `Ref` can't be a literal field
  initializer).


## Live lists and transaction visibility

The local `EventPublisher` dispatches entity-change SSE notifications after the publishing Spring
transaction commits (and immediately outside a transaction). Never push a controller-only SSE event
from the middle of a transaction: a fast browser can refetch before the inserted row is visible.
Rolled-back repository changes must not emit a client invalidation.

## Live refresh during slow inbox reads

List windows coalesce live invalidations while a first-page or pagination request is in flight.
The completed window renders before one queued refresh runs, so continuous connector imports
cannot starve initial loading. Query and filter changes still invalidate stale responses.
A failed soft refresh retains visible rows.

Inbox page reads authorize workspace members before counting or pagination. Queries using raw
conversation fields (including channel filters and message-time sorting) resolve full customer and
reference details only for the selected page. Customer-name search and display-field sorting retain
the fully decorated query path so their matching and authorization semantics remain unchanged.
