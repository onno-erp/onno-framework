# Configuration Reference

Every `onec.*` configuration property, by module, with type and default. Each integration starter is
auto-configured on the classpath and gated by its own `onec.<module>.enabled` flag (default `true`,
except Kafka inbound). Standard Spring keys (`spring.datasource.*`, `spring.mail.*`,
`spring.security.oauth2.client.*`) are used where noted and are not repeated here.

> **Keep this current.** When you add or rename a `@ConfigurationProperties` field, update this table
> in the same change. See [Keeping docs in sync](../AGENTS.md#keeping-docs-in-sync).

## Core — `onec-framework-starter` (`OnecProperties`, prefix `onec`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.scan-packages` | `List<String>` | empty → Spring Boot auto-config base packages | Packages scanned for `@Catalog`/`@Document`/`@AccumulationRegister`/`@InformationRegister`/`@Enumeration`/`@Constant`. Leave unset to scan from your `@SpringBootApplication` package. **Not** `onec.base-packages`. |
| `onec.schema.mode` | `String` | `apply` | `apply` (execute safe changes, skip+log destructive), `plan` (log only), `validate` (fail on drift/unapplied migrations), `off`. |
| `onec.schema.allow-destructive` | `boolean` | `false` | Let `apply` execute drops and narrowing type changes. |
| `onec.security.secret-key` | `String` | _(none)_ | Passphrase (hashed to a 256-bit AES key) encrypting `@Attribute(secret = true)` values. Required only when a secret attribute exists; supply via env var, never hard-code. |

## UI — `onec-ui-starter` (`UiProperties` prefix `onec.ui`, `MediaProperties` prefix `onec.media`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.ui.enabled` | `boolean` | `true` | Master switch (also gated on a `MetadataRegistry` bean). |
| `onec.ui.path` | `String` | `/ui` | SPA base path, returned as `basePath` from `GET /api/config`. |
| `onec.ui.read-only` | `boolean` | `false` | When true, every mutating REST call returns `403 UI is in read-only mode`. |
| `onec.ui.theme.*` | `Map<String,String>` | empty | Free-form theme key/values served from `GET /api/theme`. |
| `onec.media.enabled` | `boolean` | `true` | Wire `POST /api/media` and the default filesystem storage. |
| `onec.media.max-file-size` | `DataSize` | `10MB` | Largest accepted upload; also raises Spring's multipart limit. |
| `onec.media.allowed-content-types` | `List<String>` | empty (any) | Exact (`image/png`) or wildcard-subtype (`image/*`) allow-list. |
| `onec.media.public-base-path` | `String` | `/api/media` | URL prefix the filesystem backend builds reference URLs from. |
| `onec.media.filesystem.directory` | `String` | `${java.io.tmpdir}/onec-media` | Where the filesystem backend writes (date-sharded). Set a persistent path in production. |

## Auth — `onec-auth-starter` (`OnecAuthProperties`, prefix `onec.auth`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.auth.enabled` | `boolean` | `true` | Contribute a `SecurityFilterChain`; false lets you wire your own. |
| `onec.auth.mode` | enum | `in-memory` | `in-memory` \| `oidc` \| `resource-server`. |
| `onec.auth.users[*].username` / `.password` / `.roles` | `String` / `String` / `List<String>` | — | In-memory users (password auto-BCrypted). `ADMIN` is a UI superuser. |
| `onec.auth.public-paths` | `List<String>` | `/error, /api/theme, /api/config, /api/branding, /api/auth/login, /api/auth/me, /api/divkit/login, /api/desktop/**` | Ant patterns served without authentication. |
| `onec.auth.csrf-ignored-paths` | `List<String>` | `/api/auth/login` | POST paths exempt from CSRF in cookie modes. |
| `onec.auth.session.timeout` | `Duration` | `8h` | Idle session timeout. |
| `onec.auth.session.remember-me.enabled` | `boolean` | `true` | Persistent remember-me cookie (in-memory mode). |
| `onec.auth.session.remember-me.validity` | `Duration` | `14d` | Remember-me lifetime. |
| `onec.auth.session.remember-me.key` | `String` | random (warns) | Signing key — set a stable value in production. |
| `onec.auth.oidc.provider` | enum | `KEYCLOAK` | `KEYCLOAK` \| `ZITADEL` \| `CUSTOM` (presets for registration-id / principal-claim / role sources). |
| `onec.auth.oidc.registration-id` | `String` | from preset | Spring OAuth2 client registration id. |
| `onec.auth.oidc.principal-claim` | `String` | from preset | Token claim used as the principal name. |
| `onec.auth.oidc.logout-path` | `String` | `/logout` | RP-initiated logout path. |
| `onec.auth.oidc.post-logout-redirect-uri` | `String` | `{baseUrl}` | Where the IdP redirects after logout. |
| `onec.auth.oidc.roles.prefix` | `String` | `ROLE_` | Prefix prepended to mapped roles. |
| `onec.auth.oidc.roles.realm-roles` / `.client-roles` / `.client-id` | `boolean` / `boolean` / `String` | `true` / `false` / — | Keycloak realm vs client role mapping. |
| `onec.auth.oidc.roles.sources[*].claim` / `.shape` | `String` / enum | from preset | Token claim path and `ARRAY` \| `OBJECT_KEYS` shape. |

OIDC and resource-server modes also read the standard `spring.security.oauth2.client.*` /
`spring.security.oauth2.resourceserver.*` properties.

## MCP — `onec-mcp-starter` (`OnecMcpProperties`, prefix `onec.mcp`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.mcp.enabled` | `boolean` | `true` | Master switch for the MCP server and transport. |
| `onec.mcp.endpoint` | `String` | `/mcp` | Servlet path for the streamable-HTTP transport. |
| `onec.mcp.writes-enabled` | `boolean` | `true` | Expose `create_*`/`update_*`/`delete_*` tools. |
| `onec.mcp.posting-enabled` | `boolean` | `true` | Expose `post_document`/`unpost_document` (ledger side effects). |
| `onec.mcp.server-name` | `String` | `onec` | Name advertised in the MCP handshake. |
| `onec.mcp.server-version` | `String` | `0.1.0` | Version advertised in the handshake. |
| `onec.mcp.instructions` | `String` | _(generated)_ | Client-facing usage instructions; auto-generated if blank. |

## Import — `onec-import-starter` (`OnecImportProperties`, prefix `onec.import`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.import.enabled` | `boolean` | `true` | Master switch for import endpoints/services. |
| `onec.import.max-file-bytes` | `long` | `5242880` (5 MiB) | Max accepted CSV size. |
| `onec.import.preview-rows` | `int` | `20` | Max rows returned from preview. |
| `onec.import.max-rows` | `int` | `10000` | Max rows processed per import. |

## Kafka — `onec-kafka-starter` (`OnecKafkaProperties`, prefix `onec.kafka`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.kafka.enabled` | `boolean` | `true` | Master switch for the outbound relay beans. |
| `onec.kafka.service-name` | `String` | `onec-service` | CloudEvent `source`; prefix for the default inbound group id. |
| `onec.kafka.topic` | `String` | `onec.domain-events` | Outbound topic (and inbound default). |
| `onec.kafka.relay-batch-size` | `int` | `100` | Max outbox rows per `relayPending()`. |
| `onec.kafka.remote-services` | `Map<String,String>` | empty | Service name → base URL for `RemoteRefClient`. |
| `onec.kafka.inbound.enabled` | `boolean` | `false` | Opt-in inbound consumer. |
| `onec.kafka.inbound.topics` | `List<String>` | empty → `onec.kafka.topic` | Topics to consume. |
| `onec.kafka.inbound.group-id` | `String` | `<service-name>-inbound` | Consumer group id. |
| `onec.kafka.inbound.concurrency` | `int` | `1` | Listener container concurrency. |
| `onec.kafka.inbound.auto-offset-reset` | `String` | `latest` | Kafka offset reset policy. |
| `onec.kafka.inbound.dead-letter-topic` | `String` | — | Topic for failed/malformed messages. |

The outbound relay is **not** auto-scheduled — call `OutboxRelay.relayPending()` from your own
`@Scheduled` bean. Requires a `KafkaTemplate` and an `OutboxWriter` (from the core).

## Mail — `onec-mail-starter` (`MailProperties`, prefix `onec.mail`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.mail.enabled` | `boolean` | `true` | Master switch. |
| `onec.mail.provider` | `String` | `smtp` | Active dispatcher: `smtp`, `http`, `log`, `file`, `failover`. |
| `onec.mail.default-from` | `String` | — | Default From address. |
| `onec.mail.base-packages` | `List<String>` | app auto-config packages | Packages scanned for `@MailTemplate`. |
| `onec.mail.use-outbox` | `boolean` | `true` | Queue via `onec_mail_outbox` vs send synchronously. |
| `onec.mail.relay-batch-size` | `int` | `50` | Messages claimed per relay run. |
| `onec.mail.encoding` | `String` | `UTF-8` | Charset for rendering and MIME. |
| `onec.mail.derive-plain-text` | `boolean` | `true` | Derive a plain-text alternative for HTML-only templates. |
| `onec.mail.relay.enabled` | `boolean` | `true` | Scheduled outbox relay (needs a DataSource). |
| `onec.mail.relay.interval-ms` | `long` | `30000` | Delay between relay runs. |
| `onec.mail.relay.max-attempts` | `int` | `5` | Attempts before marking FAILED. |
| `onec.mail.relay.lease-timeout-ms` | `long` | `300000` | How long a claimed message may sit before reclaim. |
| `onec.mail.file.directory` | `String` | `build/mail` | Output dir for the `file` dispatcher. |
| `onec.mail.http.url` / `.method` / `.headers` / `.body-template` / `.success-status-max` | `String` / `String` / `Map` / `String` / `int` | — / `POST` / empty / — / `299` | `http` provider: endpoint, method, static headers, Thymeleaf JSON body (`msg`), success ceiling. |
| `onec.mail.failover.providers` | `List<String>` | empty | Ordered provider names (required when `provider=failover`). |
| `onec.mail.preview.enabled` / `.path` | `boolean` / `String` | `false` / `/onec/mail/preview` | Dev-only template preview endpoints. |
| `onec.mail.webhook.enabled` / `.path` | `boolean` / `String` | `false` / `/onec/mail/events` | Inbound delivery-event webhook feeding the suppression list. |

Also reads Spring Boot's `spring.mail.*` (host/port/credentials) for the SMTP dispatcher.

## Print — `onec-print-starter` (`PrintProperties`, prefix `onec.print`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.print.enabled` | `boolean` | `true` | Master switch. |
| `onec.print.base-packages` | `List<String>` | app auto-config packages | Packages scanned for `@PrintTemplate`. |
| `onec.print.encoding` | `String` | `UTF-8` | Charset for templates and HTML output. |

## Desktop — `onec-desktop-starter` (`DesktopProperties`, prefix `onec.desktop`)

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `onec.desktop.enabled` | `boolean` | `true` | Master switch for the manifest/readiness endpoints and data relocation. |
| `onec.desktop.home` | `String` | empty | Per-user data home (the Tauri shell passes `--onec.desktop.home`). When set, relocates the H2 file under `<home>/data/` and enables JDBC session persistence. |

Window appearance is configured in code via a `DesktopApp` bean (`DesktopSpec`), not properties.
The `onec-desktop-gradle-plugin` is configured through the `onecDesktop { … }` Gradle extension
(`productName`, `identifier`, `bundleTargets`, `iconSource`, macOS signing) — see
[onec-desktop-starter/README.md](../onec-desktop-starter/README.md).

## Enterprise connectors (`com.onec.enterprise`, separate repo)

Gated by `onec.guesty.enabled` / `onec.hospedajes.enabled` / `onec.tochka.enabled`. Each reads its
own `onec.<connector>.*` block (base URL, credentials/tokens, timeouts, retry, token cache). See the
[onec-enterprise](https://github.com/onec-erp/onec-enterprise) module READMEs.
