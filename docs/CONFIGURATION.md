# Configuration Reference

<!-- GENERATED FILE — do not edit by hand.
     Tables come from each starter's spring-configuration-metadata.json (the
     @ConfigurationProperties Javadoc). Prose comes from docs/_config/. Regenerate with
     `./gradlew generateConfigDocs`; `./gradlew check` fails if this file drifts. -->

Every `onno.*` configuration property, by module, with type and default. Each integration starter is
auto-configured on the classpath and gated by its own `onno.<module>.enabled` flag (default `true`,
except Kafka inbound). Standard Spring keys (`spring.datasource.*`, `spring.mail.*`,
`spring.security.oauth2.client.*`) are used where noted and are not repeated here.

> **You don't edit the tables below.** They are generated from each starter's
> `@ConfigurationProperties` Javadoc via `spring-configuration-metadata.json`. To change a row, edit
> the property's Javadoc (description) or add a default in that module's
> `META-INF/additional-spring-configuration-metadata.json`, then run `./gradlew generateConfigDocs`.
> Editorial prose lives in `docs/_config/`.

## Core — `onno-framework-starter` (`OnnoProperties`, prefix `onno`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.repository.deletion-check` | `String` | `warn` | Boot-time check that flags catalog/document repository finders which may return soft-deleted (`deletionMark = true`) rows into business logic: `warn` (default — log a warning), `strict` (fail startup), or `off`. A finder is exempt when it is deletion-scoped (a `...AndDeletionMarkFalse` derived query, a `@Query` filtering `deletion_mark`, or a delegate to `findAllActive()` / `findActiveBy*`) or annotated `@su.onno.repository.IncludesDeleted`. |
| `onno.scan-packages` | `List<String>` | — | Packages scanned for `@Catalog`, `@Document`, `@AccumulationRegister`, `@InformationRegister`, `@Enumeration`, and `@Constant` types. Leave unset to scan from your `@SpringBootApplication` package. This is the core scan property — <strong>not</strong> `onno.base-packages` (which only exists for mail/print templates). |
| `onno.schema.allow-destructive` | `Boolean` | `false` | Allow `apply` to execute data-losing changes (dropped tables/columns, narrowing type changes). Off by default: such changes are logged and skipped. |
| `onno.schema.mode` | `String` | `apply` | What to do about differences between the metadata model and the database at startup: `apply` (default — execute safe changes, report destructive ones), `plan` (log the plan, change nothing), `validate` (fail startup on any difference or unapplied migration), or `off`. |
| `onno.security.secret-key` | `String` | — | Encryption key for `@Attribute(secret = true)` values. Any passphrase works (it is hashed to a 256-bit AES key). Required only when an entity declares a secret attribute; supply it from an environment variable, never hard-code it. |

## UI — `onno-ui-starter` (`UiProperties` prefix `onno.ui`, `MediaProperties` prefix `onno.media`, `CommentProperties` prefix `onno.comments`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.comments.enabled` | `Boolean` | `true` | Whether the comments endpoint, its storage table, and the detail-page comments panel are wired at all. Turn it off to drop the feature from every entity without touching the model. |
| `onno.comments.max-length` | `Integer` | `4000` | Largest comment body accepted, in characters. The server rejects a longer body with 422; the compose box mirrors the limit client-side. Defaults to 4000. |
| `onno.comments.mentions.enabled` | `Boolean` | `true` | Whether `@`-mentions are parsed, resolved and offered in the compose typeahead. Turn it off to keep plain-text comments without touching `onno.comments.enabled`; existing mention tokens then degrade to their plain label text. |
| `onno.comments.mentions.per-entity-limit` | `Integer` | `5` | Largest number of matches pulled from any one entity before the suggestions are merged and ranked, bounding the per-keystroke scan. Defaults to 5. |
| `onno.comments.mentions.suggestion-limit` | `Integer` | `8` | Largest number of suggestions a single `/api/mentions` typeahead response returns across all readable entities. Defaults to 8. |
| `onno.media.allowed-content-types` | `List<String>` | — | Content types the endpoint accepts. Entries may be exact (`image/png`) or a wildcard subtype (`image/*`). Empty means accept any type — fine for an authenticated admin endpoint; set it to lock uploads down to, say, images only. |
| `onno.media.enabled` | `Boolean` | `true` | Whether the upload endpoint and the default filesystem storage are wired at all. |
| `onno.media.filesystem.directory` | `String` | — | Directory the filesystem backend writes uploads beneath. Defaults to `onno-media` under the JVM temp dir; set an absolute, persistent path in production. |
| `onno.media.max-file-size` | `DataSize` | `10MB` | Largest single upload accepted. Also raises Spring's 1&nbsp;MB multipart default to match, so uploads up to this size reach the controller instead of being rejected by the container. |
| `onno.media.public-base-path` | `String` | `/api/media` | URL prefix the filesystem backend builds stored-media URLs from, and the path `GET /api/media/{key}` serves from. Other backends (e.g. S3) ignore it. |
| `onno.notifications.assignments.enabled` | `Boolean` | `true` | Whether writing an AssigneeField pointing at a user raises a notification. |
| `onno.notifications.enabled` | `Boolean` | `true` | Whether the notifications endpoint, its storage table, the built-in producers, and the shell's bell + timeline panel are wired at all. Turn it off to drop the feature entirely without touching the model. |
| `onno.notifications.mentions.enabled` | `Boolean` | `true` | Whether comment `@`-mentions of a user raise a notification. |
| `onno.notifications.page-size` | `Integer` | `30` | Rows fetched per timeline window (the bell panel scrolls these keyset windows). Defaults to 30. |
| `onno.notifications.replies.enabled` | `Boolean` | `true` | Whether replying to a user's comment raises a notification for that user. |
| `onno.notifications.retention-days` | `Integer` | `90` | How many days a <em>read</em> notification is kept before the daily retention sweep deletes it. Unread notifications are never pruned. `0` disables pruning (keep read history forever). Defaults to 90. |
| `onno.ui.dashboard.widget-parallelism` | `Integer` | `8` | Maximum number of widget aggregates resolved in parallel per dashboard render. Bounded so a wide dashboard can't exhaust the JDBC connection pool — keep it comfortably below the datasource's `maximum-pool-size`. `1` forces the old sequential behaviour. |
| `onno.ui.dev-mode` | `Boolean` | — | Live-reload dev mode. When on, the `ready` ack of the `/api/events` SSE stream marks the server as a development instance, and the web client answers a server restart (a changed `bootId` across a stream reconnect) with a full page reload — so a devtools restart cycle (recompile → context restart → schema re-diff → layout/page rebuild) ends with the browser refreshing itself: save a file, see the change. Unset (the default) auto-detects: dev mode turns on exactly when `spring-boot-devtools` is on the classpath — present under `bootRun`/exploded-classpath runs, absent from the production boot jar — so a plain deployment never reloads a user's page on redeploy. Set explicitly to force it either way (e.g. `true` for a hosted preview instance that rebuilds without devtools). |
| `onno.ui.dev-reload-trigger` | `String` | `.onno-reload` | The dev-mode reload trigger file, resolved against the working directory. While dev mode is on, touching this file (`touch .onno-reload`) broadcasts a `reload` event over the `/api/events` SSE stream and every connected browser does a full refresh — an explicit "refresh now" for scripts and agents, with no HTTP endpoint or CSRF dance. Complements the automatic reload a devtools restart already performs. Ignored outside dev mode. |
| `onno.ui.enabled` | `Boolean` | `true` | Master switch for the UI starter. Also gated on a `MetadataRegistry` bean being present. |
| `onno.ui.list.default-feed` | `FeedMode` | `infinite` | Default feed mode for lists that don't declare one. `INFINITE` (the out-of-the-box default) cursor-scrolls a keyset stream — fast at any depth, no exact total; `PAGED` shows numbered offset pages with a Prev/Next pager and an exact total. Bind from config as `onno.ui.list.default-feed: paged` (relaxed/case-insensitive). |
| `onno.ui.list.page-size` | `Integer` | `50` | Default rows fetched per window (infinite) or per page (paged), for lists that don't set their own. Clamped to the server's list ceiling (500). Default `50`. |
| `onno.ui.locale` | `String` | `en` | The chrome language: a built-in message bundle to base every chrome string on, so a deployment localizes the whole shell with one line instead of a full `onno.ui.messages` map. Resolution layers three levels, later wins: the English DEFAULTS → the `locale` bundle → any explicit getMessages per-key override. `"en"` (the default) uses the built-in English defaults with no bundle file. Other values load `classpath:/su/onno/ui/messages/messages-<locale>.properties` (a UTF-8 properties file); onno ships `ru`. An app can add its own locale — or override a shipped one — by putting `messages-<locale>.properties` on the classpath at `onno/messages/` (that location wins over the bundled file). A missing bundle is a no-op (falls back to the English defaults). |
| `onno.ui.login.demo-accounts` | `List<DemoAccount>` | — | One-tap sign-in shortcuts shown on the login screen; empty (default) shows none. |
| `onno.ui.messages` | `Map<String,String>` | — | Overrides for the framework's own chrome strings — action buttons, confirmation dialogs, the login screen, empty/loading states, and client-side validation messages. Keys come from DEFAULTS (e.g. `login.title`, `action.new`); each value replaces the English default. The resolved map renders the server-side DivKit chrome and is handed to the web client via `GET /api/config`, so a one-language deployment can fully localize the shell without patching framework code. Because the keys contain dots, quote them in YAML so they bind as literal map keys (e.g. `"action.new": "Новый"` nested under `onno.ui.messages`); in a properties file use bracket notation instead (`onno.ui.messages[action.new]=Новый`). NOTE: the key set is fixed. Only keys present in DEFAULTS are ever read — an unknown key (a guessed `form.save` / `list.new`) is stored but never consumed, so it silently does nothing. The authoritative list lives in `UiMessages.DEFAULTS` (real keys are `action.save` — whose default is "Write" — `action.new`, `list.search`, …). Grep that class before writing a localization pass. |
| `onno.ui.path` | `String` | `/ui` | URL prefix the SPA is mounted under. Baked into the served `index.html` (and returned as `basePath` from `GET /api/config`) so the web client adopts it as its router basename and deep-link prefix; the bare root redirects here. Default `/ui`; set to `/` to mount the app at the web root. |
| `onno.ui.plugins.enabled` | `Boolean` | `true` | Whether to scan for, serve, and advertise custom widget plugins. |
| `onno.ui.plugins.extra-urls` | `List<String>` | — | Extra absolute plugin-module URLs to load in addition to the classpath ones — e.g. a CDN-hosted widget or one served by another app. Appended verbatim to `pluginScripts`. |
| `onno.ui.plugins.location` | `String` | `classpath*:/onno-plugins/` | Classpath location holding the compiled plugin modules (`*.js`). The Gradle plugin emits here; change it only if you stage plugin bundles somewhere non-standard. Must be a `classpath:`/`classpath*:` location ending in `/`. |
| `onno.ui.read-only` | `Boolean` | `false` | When true, every mutating REST call is rejected with `403 UI is in read-only mode`. |
| `onno.ui.theme` | `Map<String,String>` | — | Free-form theme key/values served verbatim from `GET /api/theme`. |
| `onno.ui.update-check.enabled` | `Boolean` | `true` | Master switch. When false no outbound call is ever made and the notice never appears. |
| `onno.ui.update-check.initial-delay` | `Duration` | `1m` | Delay before the first check, so startup is never blocked on a network round-trip. |
| `onno.ui.update-check.interval` | `Duration` | `24h` | How often to poll after the first check. Floored at 60s. |
| `onno.ui.update-check.url` | `String` | `https://cloud.onno.su/releases/v1/latest` | The onno-cloud endpoint that announces the latest release (see onno-cloud's ReleaseController). |

## Auth — `onno-auth-starter` (`OnnoAuthProperties`, prefix `onno.auth`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.auth.csrf-ignored-paths` | `List<String>` | — | Request paths exempted from CSRF protection in the cookie-based modes (IN_MEMORY and OIDC). Defaults to just the login endpoint. Add a path here to expose an anonymous, CSRF-free `POST` (e.g. a public lead/intake form) without having to override the whole `SecurityFilterChain` (issue #30). Ant patterns are supported (e.g. `/api/public/**`). Ignored in RESOURCE_SERVER, where CSRF is already disabled. |
| `onno.auth.enabled` | `Boolean` | `true` | Master switch for the auth starter. When false, no SecurityFilterChain is contributed and the application can wire its own. |
| `onno.auth.mode` | `Mode` | `in-memory` | Which authentication backend the starter wires. Selecting a mode only changes how identities are authenticated — the `/api/**`-requires-auth model is the same across all of them. <ul> <li>IN_MEMORY (default) — username/password against `onno.auth.users`, session cookie, JSON `/api/auth/login`. Zero external dependencies.</li> <li>OIDC — server-side OpenID Connect authorization-code login against any standard provider (Keycloak, Zitadel, …). Keeps the session-cookie model; "login" becomes a redirect to `/oauth2/authorization/{registrationId}`. Configure the provider with the standard `spring.security.oauth2.client.*` properties.</li> <li>RESOURCE_SERVER — stateless bearer-token validation. The client obtains tokens from the IdP directly and sends `Authorization: Bearer ...`. Configure with `spring.security.oauth2.resourceserver.jwt.issuer-uri`.</li> </ul> |
| `onno.auth.oidc.logout-path` | `String` | `/logout` | Path the SPA navigates to for RP-initiated logout in OIDC mode. A GET here clears the local session and redirects to the IdP's end-session endpoint. Surfaced to the SPA via `/api/auth/me` as `logoutUrl`. |
| `onno.auth.oidc.post-logout-redirect-uri` | `String` | `{baseUrl}` | Where the IdP sends the browser after ending its session. `{baseUrl}` expands to the app's own origin (scheme://host:port), so the user lands back on the SPA shell. This value must be registered under the IdP client's valid post-logout redirect URIs. |
| `onno.auth.oidc.principal-claim` | `String` | — | Token claim used as the authenticated principal name. Defaults from the preset. |
| `onno.auth.oidc.provider` | `Provider` | `keycloak` | Provider preset that supplies default registration id, principal claim, and role sources. |
| `onno.auth.oidc.registration-id` | `String` | — | `spring.security.oauth2.client.registration.*` id used to build the login URL (`/oauth2/authorization/{registrationId}`) surfaced to the SPA. Defaults from the preset (e.g. `keycloak`, `zitadel`); required for CUSTOM. |
| `onno.auth.oidc.roles.client-id` | `String` | — | Keycloak preset: client id whose roles are mapped; required when `clientRoles` is true. |
| `onno.auth.oidc.roles.client-roles` | `Boolean` | `false` | Keycloak preset: also map client-level roles (`resource_access.<clientId>.roles`). |
| `onno.auth.oidc.roles.prefix` | `String` | `ROLE_` | Prefix prepended to each mapped role so `hasRole(..)` works (Spring convention). |
| `onno.auth.oidc.roles.realm-roles` | `Boolean` | `true` | Keycloak preset: map realm-level roles (`realm_access.roles`). |
| `onno.auth.oidc.roles.sources` | `List<RoleSource>` | — | Explicit role sources. When empty, the Provider preset supplies defaults. Each source names a claim and the shape of its value. |
| `onno.auth.public-paths` | `List<String>` | — | Public API/config endpoints permitted without authentication so the login screen can render and authenticate. The SPA shell itself (everything outside `/api/**`) is public by default; only `/api/**` requires a session. NOTE: setting `onno.auth.public-paths` REPLACES this list — it does not append to it. If you set your own value to expose one extra path, repeat every default or you will silently drop `/api/config`, `/api/auth/login`, `/api/auth/me`, … and the login screen 401s. The defaults are: `/error`, `/api/theme`, `/api/config`, `/api/branding`, `/api/auth/login`, `/api/auth/me`, `/api/auth/csrf`, `/api/divkit/login`, `/api/desktop/ready`, `/api/desktop/manifest`. |
| `onno.auth.session.remember-me.allow-ephemeral-key` | `Boolean` | `false` | When key is blank, allow the app to start anyway with a built-in non-secret dev key. Off by default so a multi-node deployment fails fast instead of silently signing cookies a load-balanced peer can't verify. Turn on only for single-node/dev — never with a real secret expectation. |
| `onno.auth.session.remember-me.enabled` | `Boolean` | `true` | Whether to issue and honour the persistent remember-me cookie. |
| `onno.auth.session.remember-me.key` | `String` | — | Secret that signs the remember-me cookie. Set a stable, non-guessable value in production so cookies survive restarts, can't be forged, and validate across every node of a horizontally-scaled deployment. When blank, startup fails fast unless allowEphemeralKey is set (a blank key would otherwise sign cookies with a secret that peer nodes reject, breaking login under a load balancer). |
| `onno.auth.session.remember-me.validity` | `Duration` | `14d` | How long the remember-me cookie stays valid. Defaults to 14 days. |
| `onno.auth.session.timeout` | `Duration` | `8h` | Idle session timeout for the cookie-based modes, applied to the servlet container. Slides on each request. Defaults to 8 hours (a working day) instead of Spring's 30 minutes so parked-but-open tabs don't silently lose their session. Ignored in RESOURCE_SERVER. |
| `onno.auth.users` | `List<User>` | — | In-memory user accounts. Empty by default — the consuming app supplies them via `onno.auth.users[*]`. Production deployments should disable in-memory users and configure their own UserDetailsService. Only used in IN_MEMORY. |

OIDC and resource-server modes also read the standard `spring.security.oauth2.client.*` /
`spring.security.oauth2.resourceserver.*` properties.

## MCP — `onno-mcp-starter` (`OnnoMcpProperties`, prefix `onno.mcp`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.mcp.enabled` | `Boolean` | `true` | Master switch. When false, no MCP transport, server, or tools are contributed. |
| `onno.mcp.endpoint` | `String` | `/mcp` | Servlet path the streamable-HTTP MCP transport is mounted at. MCP clients connect here. |
| `onno.mcp.instructions` | `String` | `` | Optional instructions string sent to clients describing how to use the server. When blank, a sensible default is generated. |
| `onno.mcp.posting-enabled` | `Boolean` | `true` | Expose posting tools (post/unpost a document, posting preview). Posting has ledger side-effects, so this is gated separately from ordinary writes. |
| `onno.mcp.server-name` | `String` | `onno` | Name advertised to MCP clients in the initialize handshake. |
| `onno.mcp.server-version` | `String` | `0.1.0` | Version advertised to MCP clients. |
| `onno.mcp.writes-enabled` | `Boolean` | `true` | Expose write tools (create/update catalog and document records). |

## Import — `onno-import-starter` (`OnnoImportProperties`, prefix `onno.import`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.import.enabled` | `Boolean` | `true` | Master switch for import endpoints and services. |
| `onno.import.max-file-bytes` | `Long` | `0` | Maximum accepted CSV file size in bytes. Defaults to 5 MiB. |
| `onno.import.max-rows` | `Integer` | `10000` | Maximum data rows processed by one import request. |
| `onno.import.preview-rows` | `Integer` | `20` | Maximum data rows returned from preview. |

## Cluster — `onno-cluster-starter` (`OnnoClusterProperties`, prefix `onno.cluster`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.cluster.channel` | `String` | `onno_cluster_events` | Postgres `LISTEN`/`NOTIFY` channel carrying cross-node entity-change notices. Must be a bare identifier (`[A-Za-z0-9_]`); an invalid value falls back to the default. |
| `onno.cluster.enabled` | `Boolean` | `true` | Master switch for the cross-node event bus. When `false`, a local-only no-op bus is used and live-UI (SSE) updates do not propagate between nodes. |
| `onno.cluster.max-payload-bytes` | `Integer` | `7000` | Soft cap (bytes) kept below Postgres's 8000-byte `NOTIFY` limit. A larger event first drops its natural key, then degrades to a coarse "something changed" notice rather than failing. |
| `onno.cluster.node-id` | `String` | — | Stable id identifying this node when filtering out its own `NOTIFY` echoes. Defaults to a random per-JVM UUID; set it only if you want a deterministic id in logs. |
| `onno.cluster.poll-timeout` | `Duration` | `5s` | How long the listener blocks waiting for notifications before looping to re-check for shutdown. Bounds shutdown latency; does not affect delivery speed. |
| `onno.cluster.reconnect-backoff-max` | `Duration` | `30s` | Upper bound on the exponential backoff between reconnect attempts after the listener drops. |

## Kafka — `onno-kafka-starter` (`OnnoKafkaProperties`, prefix `onno.kafka`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.kafka.enabled` | `Boolean` | `true` | Master switch for the outbound relay beans. |
| `onno.kafka.inbound.auto-offset-reset` | `String` | `latest` | Kafka offset reset policy applied when no committed offset exists (`latest` / `earliest`). |
| `onno.kafka.inbound.concurrency` | `Integer` | `1` | Listener container concurrency (number of consumer threads). |
| `onno.kafka.inbound.dead-letter-topic` | `String` | — | When set, messages that fail handling (or are malformed) are published here instead of being redelivered. |
| `onno.kafka.inbound.enabled` | `Boolean` | `false` | Opt-in switch for the inbound consumer. Off by default. |
| `onno.kafka.inbound.group-id` | `String` | — | Consumer group id. When blank, defaults to `<serviceName>-inbound`. |
| `onno.kafka.inbound.topics` | `List<String>` | — | Topics to consume. When empty, defaults to the outbound `onno.kafka.topic`. |
| `onno.kafka.relay-batch-size` | `Integer` | `100` | Maximum number of outbox rows drained per `relayPending()` call. |
| `onno.kafka.remote-services` | `Map<String,String>` | — | Service name to base-URL map used by `RemoteRefClient` to resolve cross-service refs. |
| `onno.kafka.service-name` | `String` | `onno-service` | CloudEvent `source` for emitted events; also the prefix for the default inbound group id. |
| `onno.kafka.topic` | `String` | `onno.domain-events` | Outbound topic events are published to (and the inbound default when no inbound topics are set). |

The outbound relay is **not** auto-scheduled — call `OutboxRelay.relayPending()` from your own
`@Scheduled` bean. Requires a `KafkaTemplate` and an `OutboxWriter` (from the core).

## Mail — `onno-mail-starter` (`MailProperties`, prefix `onno.mail`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.mail.base-packages` | `List<String>` | — | Packages scanned for `MailTemplate`. Defaults to the application's base packages. |
| `onno.mail.default-from` | `String` | — | Default From: address when a MailMessage doesn't set one. |
| `onno.mail.derive-plain-text` | `Boolean` | `true` | When true and a template renders HTML only, a plain-text alternative is derived so mail is multipart. |
| `onno.mail.enabled` | `Boolean` | `true` | Master switch for the mail starter. |
| `onno.mail.encoding` | `String` | `UTF-8` | Charset used when rendering templates and building the MIME message. |
| `onno.mail.failover.providers` | `List<String>` | — | Ordered provider names to try, e.g. `[ses, smtp]`. Active when `provider=failover`. |
| `onno.mail.file.directory` | `String` | `build/mail` | Directory where `.eml` files are written. |
| `onno.mail.http.body-template` | `String` | — | Thymeleaf body template producing the provider-specific JSON payload. Resolved by the resource loader; the MailMessage is exposed as `msg`. Example: `classpath:/mail/http/sendgrid.json`. |
| `onno.mail.http.headers` | `Map<String,String>` | — | Static headers added to every request, e.g. `Authorization: Bearer xxx`. |
| `onno.mail.http.method` | `String` | `POST` | HTTP method (defaults to POST). |
| `onno.mail.http.success-status-max` | `Integer` | `299` | Highest HTTP status (inclusive) still treated as success. |
| `onno.mail.http.url` | `String` | — | Endpoint URL the message is POSTed to. |
| `onno.mail.preview.enabled` | `Boolean` | `false` | Enables the dev-only template preview endpoints. Off by default. |
| `onno.mail.preview.path` | `String` | `/onno/mail/preview` | Base path for the preview endpoints. |
| `onno.mail.provider` | `String` | `smtp` | Selects which `MailDispatcher` bean is active by its `name()`. |
| `onno.mail.relay-batch-size` | `Integer` | `50` | Outbox relay batch size. |
| `onno.mail.relay.enabled` | `Boolean` | `true` | Whether the scheduled relay is active. Requires an outbox (DataSource). |
| `onno.mail.relay.interval-ms` | `Long` | `30000` | Delay between relay runs, in milliseconds. |
| `onno.mail.relay.lease-timeout-ms` | `Long` | `300000` | How long a message claimed by a relay may stay in `SENDING` before another worker reclaims it. Guards against a worker that crashed mid-send; set comfortably above the slowest provider send time. |
| `onno.mail.relay.max-attempts` | `Integer` | `5` | Max delivery attempts before a message is marked FAILED. |
| `onno.mail.use-outbox` | `Boolean` | `true` | Whether `MailService.queue(...)` writes to the outbox (true) or dispatches synchronously (false). |
| `onno.mail.webhook.enabled` | `Boolean` | `false` | Enables the inbound delivery-event webhook that feeds the suppression list. Off by default. |
| `onno.mail.webhook.path` | `String` | `/onno/mail/events` | Path the provider posts delivery events to. |

Also reads Spring Boot's `spring.mail.*` (host/port/credentials) for the SMTP dispatcher.

## Print — `onno-print-starter` (`PrintProperties`, prefix `onno.print`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.print.base-packages` | `List<String>` | — | Packages scanned for PrintTemplate. Defaults to the application's base packages. |
| `onno.print.enabled` | `Boolean` | `true` | Master switch for the print starter (PDF rendering endpoints and services). |
| `onno.print.encoding` | `String` | `UTF-8` | Character encoding used when rendering HTML templates. |

## Desktop — `onno-desktop-starter` (`DesktopProperties`, prefix `onno.desktop`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onno.desktop.enabled` | `Boolean` | `true` | Whether the desktop endpoints and data relocation are active. |
| `onno.desktop.home` | `String` | `` | Per-user data home the shell passes at launch (via `--onno.desktop.home`). When set, an embedded H2 file datasource is relocated under `<home>/data` so the database lives in the OS app-data directory rather than next to the binary. Left unset during `bootRun`/dev, so normal runs are untouched. |

Window appearance is configured in code via a `DesktopApp` bean (`DesktopSpec`), not properties.
The `onno-desktop-gradle-plugin` is configured through the `onnoDesktop { … }` Gradle extension
(`productName`, `identifier`, `bundleTargets`, `iconSource`, macOS signing) — see
[onno-desktop-starter/README.md](../onno-desktop-starter/README.md).

## Enterprise connectors (`su.onno.enterprise`, separate repo)

Gated by `onno.guesty.enabled` / `onno.hospedajes.enabled` / `onno.tochka.enabled`. Each reads its
own `onno.<connector>.*` block (base URL, credentials/tokens, timeouts, retry, token cache). See the
[onno-enterprise](https://github.com/onno-erp/onno-enterprise) module READMEs.
