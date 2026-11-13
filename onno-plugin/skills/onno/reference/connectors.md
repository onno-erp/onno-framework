# Building a commercial connector (onno-enterprise)

The commercial vertical connectors live in the separate, commercially licensed
[onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo (Maven group
`su.onno.enterprise`, onno Commercial License). Today: `onno-tochka-starter` (Tochka Bank API),
`onno-yookassa-starter`, `onno-digitalkassa-starter`, `onno-telegram-starter`, `onno-amocrm-starter`,
`onno-metrica-starter`, `onno-wb-starter`, `onno-ozon-starter` and `onno-indexnow-starter`. Use this when adding or editing a connector.

> Building an **open-source / community** integration instead (any external system, not the
> commercial verticals)? The public, contributor-facing guide is
> [`docs/EXTENDING.md`](https://github.com/onno-erp/onno-framework/blob/main/docs/EXTENDING.md) —
> same starter shape, plus naming/namespace conventions and how to get listed in `INTEGRATIONS.md`.

## Key insight: a connector wraps an external API, it does not model the business

The connectors define **zero** framework metadata — no `@Catalog`/`@Document`/registers/posting/UI.
The only framework types they touch are `su.onno.types.Ref` and `su.onno.types.RefResolver`. The
catalogs, documents, registers, posting, and UI live in the **consuming application** (e.g. a
rentals ERP). A connector is a Spring Boot **auto-configuration starter that exposes a typed client +
sync service for one external system**; the host app wires it into its domain.

## The starter shape (every connector follows it)

```
onno-<name>-starter/
  <Name>Properties.java          @ConfigurationProperties(prefix = "onno.<name>")
  <Name>TokenManager.java        OAuth/credential lifecycle (where applicable)
  Default<Name>Client.java       typed HTTP client implementing <Name>Client
  <Name>Service.java             convenience facade (pagination, polling, mapping)
  Onno<Name>AutoConfiguration.java   @AutoConfiguration, beans @ConditionalOnMissingBean
  resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Auto-configuration is gated by `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")`
and (for HTTP clients) `@ConditionalOnClass(RestClient.class)`; replaceable singleton beans are
`@ConditionalOnMissingBean` so the host can override them. Build files use `java-library` +
`maven-publish` with `withSourcesJar()`/`withJavadocJar()`, depend on the core as
`su.onno:onno-framework` (`api`) + `onno-framework-starter` (`implementation`), and
publish to the private `onno-enterprise` GitHub Packages repo.

Credentialed community connectors default `enabled=false`. Add
`spring-boot-configuration-processor` so the artifact packages its own property metadata. Apply
`@ConditionalOnMissingBean` to replaceable singleton client/token/service beans—not additive host UI,
tool, or contributor beans. If a bean injects `RestClient.Builder`, also condition on that bean.

## Robust external-HTTP-client template (e.g. Tochka)

`Default*Client.execute()`: attach the bearer token, map non-2xx to a typed
`*ApiException`, **refresh the token once on 401 then retry** (the refresh does not consume a retry
attempt), and **exponential backoff on 429/5xx**. Use `ParameterizedTypeReference` for generic
response envelopes (Tochka wraps everything in `{Data, Links, Meta}`; the client unwraps it to plain
records). Token managers cache the token **in memory and on disk** when the provider rate-limits
token issuance (some APIs cap issuance per day) or **rotates the refresh token** on every renewal (Tochka — so
persist the latest refresh token, not the seed).

Bound attempts, honor `Retry-After`, add jitter, and automatically retry only idempotent GET/pull
operations or writes that reuse the same provider idempotency key. Never log credentials.

For inbound synchronization, keep connector APIs in external DTOs/pages/cursors. The host owns
domain mapping, repositories, `RefResolver`, projections, and jobs. Advance a checkpoint only after
all host projections commit; replay must upsert by external id plus revision and define tombstone
handling.

## Async external workflows are poll-based

Many provider workflows are submit-then-poll: a submission returns a batch/operation id and the
outcome is polled later. Tochka `statement(...)` polls
every 2s up to 60s until `status == Ready`. Model these as a submit-then-reconcile pair, with a
scheduled reconcile job on the host side.

## Stateful connectors own their own table

A connector that submits to an external authority keeps an audit ledger (JDBI, an
`onno_<name>_*` table) tracking SUBMITTED → ACCEPTED|REJECTED → CANCELLED keyed by the provider's
batch id with the caller's reference for **idempotency**. Its auto-config orders
itself `@AutoConfiguration(after = DataSourceAutoConfiguration.class)` and makes the ledger
`@ConditionalOnBean(DataSource.class)`, then creates its schema with raw JDBI DDL (`onno_`-prefixed
table, `_`-prefixed columns). The service takes the log as an `ObjectProvider` so it degrades
gracefully with no DataSource.

## Host-side integration idioms

The consuming app wires a connector to its domain:

- **React to a domain state change → call the external system.** Use an `@EventListener` on
  `DocumentPostedEvent` (or an `AfterWriteHandler`), map the record to the external payload and
  submit it. Guard with a ledger for idempotency, and **log external failures without blocking the
  save**.
- **Map `Ref<T>` to the external schema with `RefResolver`.** `refResolver.resolve(ref).orElse(null)`
  dereferences catalog references; enums map to external code systems via `switch`. `Ref<T>` +
  `RefResolver` are the canonical seam between app domain and connector code.
- **Validate before submit.** Run cross-field rules the external schema can't express, record
  invalid records as locally REJECTED (still retryable), and submit only the valid subset — turning
  opaque async rejections into precise local ones.
- **One switch gates both sides.** The host integration `@Configuration` reuses the same
  `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")` as the starter, so one flag
  turns on the connector and its host wiring together.

`reference/example-oauth-connect/` in onno-enterprise shows the interactive "Connect with X" flow.

## When editing onno-enterprise, keep its docs in sync too

Update the connector's module README and the repo's top-level README module table whenever you add
or change a connector — a connector that is missing from the module table is effectively
undiscoverable. Confirm the module depends on the core as `su.onno:onno-framework` /
`su.onno:onno-framework-starter`, not any stale coordinates.

Test token expiry/single-flight refresh, HTTP error/retry policy, pagination/replay, idempotency and
checkpoint ordering, datasource-optional ledger wiring, bean override, configuration metadata, and
published JAR/POM contents. Run `publishToMavenLocal` plus a tiny external consumer before release.
