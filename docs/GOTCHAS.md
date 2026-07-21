# Consumer gotchas

The short list of things that bite when building an app on the published `su.onno:*` libraries —
each of these has cost a real debugging session. Verified against **v1.11.1**. Companion pages:
[CONFIGURATION.md](CONFIGURATION.md) for every property, [HEADLESS_READ_API.md](HEADLESS_READ_API.md)
for the wire contract, [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit.

> **Keep this current.** When a gotcha below is fixed or its behaviour changes, update this page in
> the same PR. See [Keeping docs in sync](../AGENTS.md#keeping-docs-in-sync).

## Auth & session

- **`onno.auth.public-paths` REPLACES the built-in defaults — it does not append.** Set it without
  re-listing the defaults and login itself starts returning 401. The current default list (repeat
  all of it when overriding): `/error`, `/api/theme`, `/api/config`, `/api/branding`,
  `/api/auth/login`, `/api/auth/me`, `/api/auth/csrf`, `/api/divkit/login`, `/api/desktop/ready`,
  `/api/desktop/manifest`. Same trap does *not* apply to `onno.auth.csrf-ignored-paths` semantics,
  but its default is just `[/api/auth/login]` — SSO callback paths (e.g.
  `/api/auth/telegram/**`) must be added to **both** lists.
- **A blank `onno.auth.session.remember-me.key` fails startup on purpose.** Earlier versions
  silently rotated the key per boot, which invalidated every session cookie on redeploy. Now the app
  refuses to start with a clear message; for dev, `onno.auth.session.remember-me.allow-ephemeral-key: true`
  opts into a fixed, non-secret built-in key (cookies survive restarts, but never use it in prod).
  Remember-me is on by default, validity 14d; idle session timeout is `onno.auth.session.timeout`
  (default 8h, sliding).
- **Demo login buttons live under `onno.ui.login.demo-accounts`** (list of
  `{label, username, password}`) — under `onno.ui`, not `onno.auth`. They render on the password
  step of the login screen.

## UI chrome & localization

- **`onno.ui.messages` is a fixed key namespace (~190 keys); unknown keys are silently ignored.**
  The authoritative list is `su.onno.ui.UiMessages.DEFAULTS` — grep it before a localization pass.
  Resolution is three layers: English defaults → `onno.ui.locale` bundle (`ru` ships complete; a
  consumer app can add `onno/messages/messages-<locale>.properties` on the classpath, which wins) →
  explicit per-key `onno.ui.messages.*` overrides.
- **Entity/attribute `name` is the API contract — keep it ASCII/URL-safe.** It is the REST path
  segment and the write-path field key. Localize through `title`/`displayName`/`label(...)`/
  `@EnumLabel`, never by renaming `name` (renaming it breaks every client). The old presence/SSE
  403 on non-ASCII route segments is fixed, but the naming rule stands.

## Model & lifecycle

- **There is no `LocalTime`** (nor `Instant`/`OffsetDateTime`/`ZonedDateTime`). Schema generation
  throws at boot with the supported list: `String`, `int/Integer`, `long/Long`, `boolean/Boolean`,
  `double/Double`, `float/Float`, `BigDecimal`, `UUID`, `LocalDate`, `LocalDateTime`, enums,
  `Ref<T>`.
- **No dependency injection inside `rules()`, `beforeWrite`, `beforePost`, `handlePosting`,
  `afterPost`.** In-transaction hooks run on plain reflectively-built instances — `@Autowired`
  fields are null. Anything needing Spring beans belongs in an `@EventListener` on
  `DocumentPostedEvent` / `DocumentUnpostedEvent` / `EntityChangedEvent`.

## Wire contract

- **Reads are snake_case, writes are camelCase and partial.** Reads carry system columns (`_id`,
  `_code`, `_description`, `_posted`, …) and sidecars (`{col}_display`, `{col}_ref`, `{col}_code`,
  `{col}_color`); writes use the attribute `fieldName` and touch only the keys you send. Do not
  echo a read payload back as a write. Full tables in
  [HEADLESS_READ_API.md](HEADLESS_READ_API.md).
- **An enum value is its deterministic UUID, never the constant name.** The UUID is derived from
  `FQCN.CONSTANT`, so it is stable across databases; the write path rejects `"NEW"` where it
  expects the UUID. Display strings/colors come from `@EnumLabel` and ride the `{col}_display` /
  `{col}_color` sidecars.
- **Temporal reads and writes are ISO-8601 wall-clock values.** `LocalDate` reads/writes as
  `yyyy-MM-dd`; `LocalDateTime` reads/writes as offset-free `yyyy-MM-ddTHH:mm[:ss[.fraction]]`.
  Since `LocalDateTime` has no zone, an accepted transport offset (`Z`, `+03:00`) is ignored without
  shifting the local fields: `2026-06-04T10:00+03:00` persists as `2026-06-04T10:00`. Since v1.11.1
  the read API normalizes PostgreSQL/JDBC timestamp representations and the bundled form normalizes
  loaded values before resubmitting them. Headless clients must still map snake_case read columns
  to camelCase write fields; do not echo the full read object as a write.

## SPA & static assets

- **The SPA fallback swallows unknown paths — including your static assets.** Anything not found
  under `classpath:/static/ui/` returns `index.html` with HTTP 200 `text/html`; only
  `{onno.ui.path}/plugins/**` is exempt. A kiosk page or extra asset directory needs its own
  `@GetMapping` controller. Corollary for API work: **if a call returns HTML, you hit a wrong path
  or aren't authenticated — not a working endpoint.**

## Live updates (SSE)

- **`/api/events` emits only NAMED events — `EventSource.onmessage` receives nothing.** Subscribe
  with `addEventListener` per event name. Current names: `created`, `updated`, `deleted`, `posted`,
  `unposted`, `changed` (entity changes; payload `{type, entityType, entityName, id, naturalKey,
  timestamp}`), plus `ready` (carries `bootId`/`devMode`), `reload`, `presence`, `notification`.
  Keepalives are SSE comments.
- **The stream is lossy by design.** There is no `Last-Event-ID`/since replay; on reconnect
  (client retries every 3s) refetch the surfaces you care about. Events are role-filtered per
  subscriber. In a browser, the SPA already multiplexes one connection per origin (Web Locks
  leader + BroadcastChannel) — don't open a second one per tab.

## Custom widgets

- **Host UI primitives are exposed to widgets** (contract v2+): `Button`, `Badge`, `Input`,
  `Label`, `Textarea`, `Checkbox`, `Switch`, `Segmented`, `DatePicker`, `Card`, `Popover`,
  `Select` — import them from `@onno/widget-sdk` instead of rebuilding lookalikes.
- **Tailwind in widgets works, with two caveats.** The `su.onno.widgets` Gradle plugin runs
  Tailwind over widget sources (utilities-only, preflight off, host tokens), but it scans only
  `src/main/widgets`, and runtime-concatenated class names (`` `text-${c}` ``) are invisible to
  it — use literal class strings, or inline `style` with `hsl(var(--primary))`.
- **Plugin CSS must load before the host stylesheet.** `onno-widgets.css` is a second, unscoped
  Tailwind utilities pass; its selectors tie with the host's on specificity, so document order
  decides every conflict. Appended after the host sheet, a plugin's bare utility (`.flex-col`)
  silently beats the host's responsive variant of the same property (`sm:flex-row`) on any host
  element carrying both classes — this once collapsed the desktop date-range popover (presets
  rail + calendar) into a stacked column. `injectPluginStyles` therefore inserts plugin `<link>`s
  *before* the first host style; keep that invariant if you touch style injection, and never
  append third-party utility CSS to the end of `<head>`.

## Forms

- **The New form prefills from query params.** Append write-path field names to the New route:
  `/ui/documents/Reservations/new?room=<uuid>&startsAt=2026-07-16T19:00`. `Ref`/enum values are
  UUID strings, temporals ISO, everything else verbatim; `viewport`/`theme`/`profile` are reserved;
  unknown keys and bad values are skipped silently. Prefill applies after `OnFillingHandler` and
  field initializers. This is the way to seed a `Ref` default (a `Ref` can't be a literal field
  initializer).
