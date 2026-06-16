# Configuration Reference

<!-- GENERATED FILE — do not edit by hand.
     Tables come from each starter's spring-configuration-metadata.json (the
     @ConfigurationProperties Javadoc). Prose comes from docs/_config/. Regenerate with
     `./gradlew generateConfigDocs`; `./gradlew check` fails if this file drifts. -->

Every `onec.*` configuration property, by module, with type and default. Each integration starter is
auto-configured on the classpath and gated by its own `onec.<module>.enabled` flag (default `true`,
except Kafka inbound). Standard Spring keys (`spring.datasource.*`, `spring.mail.*`,
`spring.security.oauth2.client.*`) are used where noted and are not repeated here.

> **You don't edit the tables below.** They are generated from each starter's
> `@ConfigurationProperties` Javadoc via `spring-configuration-metadata.json`. To change a row, edit
> the property's Javadoc (description) or add a default in that module's
> `META-INF/additional-spring-configuration-metadata.json`, then run `./gradlew generateConfigDocs`.
> Editorial prose lives in `docs/_config/`.

## Core — `onec-framework-starter` (`OnecProperties`, prefix `onec`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.scan-packages` | `List<String>` | — | Packages scanned for `@Catalog`, `@Document`, `@AccumulationRegister`, `@InformationRegister`, `@Enumeration`, and `@Constant` types. Leave unset to scan from your `@SpringBootApplication` package. This is the core scan property — <strong>not</strong> `onec.base-packages` (which only exists for mail/print templates). |
| `onec.schema.allow-destructive` | `Boolean` | `false` | Allow `apply` to execute data-losing changes (dropped tables/columns, narrowing type changes). Off by default: such changes are logged and skipped. |
| `onec.schema.mode` | `String` | `apply` | What to do about differences between the metadata model and the database at startup: `apply` (default — execute safe changes, report destructive ones), `plan` (log the plan, change nothing), `validate` (fail startup on any difference or unapplied migration), or `off`. |
| `onec.security.secret-key` | `String` | — | Encryption key for `@Attribute(secret = true)` values. Any passphrase works (it is hashed to a 256-bit AES key). Required only when an entity declares a secret attribute; supply it from an environment variable, never hard-code it. |

## UI — `onec-ui-starter` (`UiProperties` prefix `onec.ui`, `MediaProperties` prefix `onec.media`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.media.allowed-content-types` | `List<String>` | — | Content types the endpoint accepts. Entries may be exact (`image/png`) or a wildcard subtype (`image/*`). Empty means accept any type — fine for an authenticated admin endpoint; set it to lock uploads down to, say, images only. |
| `onec.media.enabled` | `Boolean` | `true` | Whether the upload endpoint and the default filesystem storage are wired at all. |
| `onec.media.filesystem.directory` | `String` | — | Directory the filesystem backend writes uploads beneath. Defaults to `onec-media` under the JVM temp dir; set an absolute, persistent path in production. |
| `onec.media.max-file-size` | `DataSize` | `10MB` | Largest single upload accepted. Also raises Spring's 1&nbsp;MB multipart default to match, so uploads up to this size reach the controller instead of being rejected by the container. |
| `onec.media.public-base-path` | `String` | `/api/media` | URL prefix the filesystem backend builds stored-media URLs from, and the path `GET /api/media/{key}` serves from. Other backends (e.g. S3) ignore it. |
| `onec.ui.enabled` | `Boolean` | `true` | Master switch for the UI starter. Also gated on a `MetadataRegistry` bean being present. |
| `onec.ui.path` | `String` | `/ui` | SPA base path, returned as `basePath` from `GET /api/config`. |
| `onec.ui.read-only` | `Boolean` | `false` | When true, every mutating REST call is rejected with `403 UI is in read-only mode`. |
| `onec.ui.settings.enabled` | `Boolean` | `false` | Whether to surface the built-in Settings page and its admin nav entry. |
| `onec.ui.theme` | `Map<String,String>` | — | Free-form theme key/values served verbatim from `GET /api/theme`. |
| `onec.ui.update-check.enabled` | `Boolean` | `true` | Master switch. When false no outbound call is ever made and the notice never appears. |
| `onec.ui.update-check.initial-delay` | `Duration` | `1m` | Delay before the first check, so startup is never blocked on a network round-trip. |
| `onec.ui.update-check.interval` | `Duration` | `24h` | How often to poll after the first check. Floored at 60s. |
| `onec.ui.update-check.url` | `String` | `https://cloud.onno.su/releases/v1/latest` | The onec-cloud endpoint that announces the latest release (see onec-cloud's ReleaseController). |

## Auth — `onec-auth-starter` (`OnecAuthProperties`, prefix `onec.auth`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.auth.csrf-ignored-paths` | `List<String>` | — | Request paths exempted from CSRF protection in the cookie-based modes (IN_MEMORY and OIDC). Defaults to just the login endpoint. Add a path here to expose an anonymous, CSRF-free `POST` (e.g. a public lead/intake form) without having to override the whole `SecurityFilterChain` (issue #30). Ant patterns are supported (e.g. `/api/public/**`). Ignored in RESOURCE_SERVER, where CSRF is already disabled. |
| `onec.auth.enabled` | `Boolean` | `true` | Master switch for the auth starter. When false, no SecurityFilterChain is contributed and the application can wire its own. |
| `onec.auth.mode` | `Mode` | `in-memory` | Which authentication backend the starter wires. Selecting a mode only changes how identities are authenticated — the `/api/**`-requires-auth model is the same across all of them. <ul> <li>IN_MEMORY (default) — username/password against `onec.auth.users`, session cookie, JSON `/api/auth/login`. Zero external dependencies.</li> <li>OIDC — server-side OpenID Connect authorization-code login against any standard provider (Keycloak, Zitadel, …). Keeps the session-cookie model; "login" becomes a redirect to `/oauth2/authorization/{registrationId}`. Configure the provider with the standard `spring.security.oauth2.client.*` properties.</li> <li>RESOURCE_SERVER — stateless bearer-token validation. The client obtains tokens from the IdP directly and sends `Authorization: Bearer ...`. Configure with `spring.security.oauth2.resourceserver.jwt.issuer-uri`.</li> </ul> |
| `onec.auth.oidc.logout-path` | `String` | `/logout` | Path the SPA navigates to for RP-initiated logout in OIDC mode. A GET here clears the local session and redirects to the IdP's end-session endpoint. Surfaced to the SPA via `/api/auth/me` as `logoutUrl`. |
| `onec.auth.oidc.post-logout-redirect-uri` | `String` | `{baseUrl}` | Where the IdP sends the browser after ending its session. `{baseUrl}` expands to the app's own origin (scheme://host:port), so the user lands back on the SPA shell. This value must be registered under the IdP client's valid post-logout redirect URIs. |
| `onec.auth.oidc.principal-claim` | `String` | — | Token claim used as the authenticated principal name. Defaults from the preset. |
| `onec.auth.oidc.provider` | `Provider` | `keycloak` | Provider preset that supplies default registration id, principal claim, and role sources. |
| `onec.auth.oidc.registration-id` | `String` | — | `spring.security.oauth2.client.registration.*` id used to build the login URL (`/oauth2/authorization/{registrationId}`) surfaced to the SPA. Defaults from the preset (e.g. `keycloak`, `zitadel`); required for CUSTOM. |
| `onec.auth.oidc.roles.client-id` | `String` | — | Keycloak preset: client id whose roles are mapped; required when `clientRoles` is true. |
| `onec.auth.oidc.roles.client-roles` | `Boolean` | `false` | Keycloak preset: also map client-level roles (`resource_access.<clientId>.roles`). |
| `onec.auth.oidc.roles.prefix` | `String` | `ROLE_` | Prefix prepended to each mapped role so `hasRole(..)` works (Spring convention). |
| `onec.auth.oidc.roles.realm-roles` | `Boolean` | `true` | Keycloak preset: map realm-level roles (`realm_access.roles`). |
| `onec.auth.oidc.roles.sources` | `List<RoleSource>` | — | Explicit role sources. When empty, the Provider preset supplies defaults. Each source names a claim and the shape of its value. |
| `onec.auth.public-paths` | `List<String>` | — | Public API/config endpoints permitted without authentication so the login screen can render and authenticate. The SPA shell itself (everything outside `/api/**`) is public by default; only `/api/**` requires a session. |
| `onec.auth.session.remember-me.enabled` | `Boolean` | `true` | Whether to issue and honour the persistent remember-me cookie. |
| `onec.auth.session.remember-me.key` | `String` | — | Secret that signs the remember-me cookie. Set a stable, non-guessable value in production so cookies survive restarts and can't be forged. When blank, a random key is generated at startup — remember-me still works within a single run, but every restart invalidates outstanding cookies (and a warning is logged). |
| `onec.auth.session.remember-me.validity` | `Duration` | `14d` | How long the remember-me cookie stays valid. Defaults to 14 days. |
| `onec.auth.session.timeout` | `Duration` | `8h` | Idle session timeout for the cookie-based modes, applied to the servlet container. Slides on each request. Defaults to 8 hours (a working day) instead of Spring's 30 minutes so parked-but-open tabs don't silently lose their session. Ignored in RESOURCE_SERVER. |
| `onec.auth.users` | `List<User>` | — | In-memory user accounts. Empty by default — the consuming app supplies them via `onec.auth.users[*]`. Production deployments should disable in-memory users and configure their own UserDetailsService. Only used in IN_MEMORY. |

OIDC and resource-server modes also read the standard `spring.security.oauth2.client.*` /
`spring.security.oauth2.resourceserver.*` properties.

## MCP — `onec-mcp-starter` (`OnecMcpProperties`, prefix `onec.mcp`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.mcp.enabled` | `Boolean` | `true` | Master switch. When false, no MCP transport, server, or tools are contributed. |
| `onec.mcp.endpoint` | `String` | `/mcp` | Servlet path the streamable-HTTP MCP transport is mounted at. MCP clients connect here. |
| `onec.mcp.instructions` | `String` | `` | Optional instructions string sent to clients describing how to use the server. When blank, a sensible default is generated. |
| `onec.mcp.posting-enabled` | `Boolean` | `true` | Expose posting tools (post/unpost a document, posting preview). Posting has ledger side-effects, so this is gated separately from ordinary writes. |
| `onec.mcp.server-name` | `String` | `onec` | Name advertised to MCP clients in the initialize handshake. |
| `onec.mcp.server-version` | `String` | `0.1.0` | Version advertised to MCP clients. |
| `onec.mcp.writes-enabled` | `Boolean` | `true` | Expose write tools (create/update catalog and document records). |

## Import — `onec-import-starter` (`OnecImportProperties`, prefix `onec.import`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.import.enabled` | `Boolean` | `true` | Master switch for import endpoints and services. |
| `onec.import.max-file-bytes` | `Long` | `0` | Maximum accepted CSV file size in bytes. Defaults to 5 MiB. |
| `onec.import.max-rows` | `Integer` | `10000` | Maximum data rows processed by one import request. |
| `onec.import.preview-rows` | `Integer` | `20` | Maximum data rows returned from preview. |

## Kafka — `onec-kafka-starter` (`OnecKafkaProperties`, prefix `onec.kafka`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.kafka.enabled` | `Boolean` | `true` | Master switch for the outbound relay beans. |
| `onec.kafka.inbound.auto-offset-reset` | `String` | `latest` | Kafka offset reset policy applied when no committed offset exists (`latest` / `earliest`). |
| `onec.kafka.inbound.concurrency` | `Integer` | `1` | Listener container concurrency (number of consumer threads). |
| `onec.kafka.inbound.dead-letter-topic` | `String` | — | When set, messages that fail handling (or are malformed) are published here instead of being redelivered. |
| `onec.kafka.inbound.enabled` | `Boolean` | `false` | Opt-in switch for the inbound consumer. Off by default. |
| `onec.kafka.inbound.group-id` | `String` | — | Consumer group id. When blank, defaults to `<serviceName>-inbound`. |
| `onec.kafka.inbound.topics` | `List<String>` | — | Topics to consume. When empty, defaults to the outbound `onec.kafka.topic`. |
| `onec.kafka.relay-batch-size` | `Integer` | `100` | Maximum number of outbox rows drained per `relayPending()` call. |
| `onec.kafka.remote-services` | `Map<String,String>` | — | Service name to base-URL map used by `RemoteRefClient` to resolve cross-service refs. |
| `onec.kafka.service-name` | `String` | `onec-service` | CloudEvent `source` for emitted events; also the prefix for the default inbound group id. |
| `onec.kafka.topic` | `String` | `onec.domain-events` | Outbound topic events are published to (and the inbound default when no inbound topics are set). |

The outbound relay is **not** auto-scheduled — call `OutboxRelay.relayPending()` from your own
`@Scheduled` bean. Requires a `KafkaTemplate` and an `OutboxWriter` (from the core).

## Mail — `onec-mail-starter` (`MailProperties`, prefix `onec.mail`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.mail.base-packages` | `List<String>` | — | Packages scanned for `MailTemplate`. Defaults to the application's base packages. |
| `onec.mail.default-from` | `String` | — | Default From: address when a MailMessage doesn't set one. |
| `onec.mail.derive-plain-text` | `Boolean` | `true` | When true and a template renders HTML only, a plain-text alternative is derived so mail is multipart. |
| `onec.mail.enabled` | `Boolean` | `true` | Master switch for the mail starter. |
| `onec.mail.encoding` | `String` | `UTF-8` | Charset used when rendering templates and building the MIME message. |
| `onec.mail.failover.providers` | `List<String>` | — | Ordered provider names to try, e.g. `[ses, smtp]`. Active when `provider=failover`. |
| `onec.mail.file.directory` | `String` | `build/mail` | Directory where `.eml` files are written. |
| `onec.mail.http.body-template` | `String` | — | Thymeleaf body template producing the provider-specific JSON payload. Resolved by the resource loader; the MailMessage is exposed as `msg`. Example: `classpath:/mail/http/sendgrid.json`. |
| `onec.mail.http.headers` | `Map<String,String>` | — | Static headers added to every request, e.g. `Authorization: Bearer xxx`. |
| `onec.mail.http.method` | `String` | `POST` | HTTP method (defaults to POST). |
| `onec.mail.http.success-status-max` | `Integer` | `299` | Highest HTTP status (inclusive) still treated as success. |
| `onec.mail.http.url` | `String` | — | Endpoint URL the message is POSTed to. |
| `onec.mail.preview.enabled` | `Boolean` | `false` | Enables the dev-only template preview endpoints. Off by default. |
| `onec.mail.preview.path` | `String` | `/onec/mail/preview` | Base path for the preview endpoints. |
| `onec.mail.provider` | `String` | `smtp` | Selects which `MailDispatcher` bean is active by its `name()`. |
| `onec.mail.relay-batch-size` | `Integer` | `50` | Outbox relay batch size. |
| `onec.mail.relay.enabled` | `Boolean` | `true` | Whether the scheduled relay is active. Requires an outbox (DataSource). |
| `onec.mail.relay.interval-ms` | `Long` | `30000` | Delay between relay runs, in milliseconds. |
| `onec.mail.relay.lease-timeout-ms` | `Long` | `300000` | How long a message claimed by a relay may stay in `SENDING` before another worker reclaims it. Guards against a worker that crashed mid-send; set comfortably above the slowest provider send time. |
| `onec.mail.relay.max-attempts` | `Integer` | `5` | Max delivery attempts before a message is marked FAILED. |
| `onec.mail.use-outbox` | `Boolean` | `true` | Whether `MailService.queue(...)` writes to the outbox (true) or dispatches synchronously (false). |
| `onec.mail.webhook.enabled` | `Boolean` | `false` | Enables the inbound delivery-event webhook that feeds the suppression list. Off by default. |
| `onec.mail.webhook.path` | `String` | `/onec/mail/events` | Path the provider posts delivery events to. |

Also reads Spring Boot's `spring.mail.*` (host/port/credentials) for the SMTP dispatcher.

## Print — `onec-print-starter` (`PrintProperties`, prefix `onec.print`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.print.base-packages` | `List<String>` | — | Packages scanned for PrintTemplate. Defaults to the application's base packages. |
| `onec.print.enabled` | `Boolean` | `true` | Master switch for the print starter (PDF rendering endpoints and services). |
| `onec.print.encoding` | `String` | `UTF-8` | Character encoding used when rendering HTML templates. |

## Desktop — `onec-desktop-starter` (`DesktopProperties`, prefix `onec.desktop`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.desktop.enabled` | `Boolean` | `true` | Whether the desktop endpoints and data relocation are active. |
| `onec.desktop.home` | `String` | `` | Per-user data home the shell passes at launch (via `--onec.desktop.home`). When set, an embedded H2 file datasource is relocated under `<home>/data` so the database lives in the OS app-data directory rather than next to the binary. Left unset during `bootRun`/dev, so normal runs are untouched. |

Window appearance is configured in code via a `DesktopApp` bean (`DesktopSpec`), not properties.
The `onec-desktop-gradle-plugin` is configured through the `onecDesktop { … }` Gradle extension
(`productName`, `identifier`, `bundleTargets`, `iconSource`, macOS signing) — see
[onec-desktop-starter/README.md](../onec-desktop-starter/README.md).

## Enterprise connectors (`com.onec.enterprise`, separate repo)

Gated by `onec.guesty.enabled` / `onec.hospedajes.enabled` / `onec.tochka.enabled`. Each reads its
own `onec.<connector>.*` block (base URL, credentials/tokens, timeouts, retry, token cache). See the
[onec-enterprise](https://github.com/onec-erp/onec-enterprise) module READMEs.
