# Building a commercial connector (onno-enterprise)

The commercial vertical connectors live in the separate, commercially licensed
[onno-enterprise](https://github.com/onno-erp/onno-enterprise) repo (Maven group
`su.onno.enterprise`, onno Commercial License). Today: `onno-guesty-starter` (Guesty short-term
rental PMS), `onno-hospedajes-starter` (Spanish SES.HOSPEDAJES guest-registration authority, SOAP),
`onno-tochka-starter` (Tochka Bank API). Use this when adding or editing a connector.

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

## The starter shape (all three follow it)

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
and (for HTTP clients) `@ConditionalOnClass(RestClient.class)`; every bean is
`@ConditionalOnMissingBean` so the host can override any of them. Build files use `java-library` +
`maven-publish` with `withSourcesJar()`/`withJavadocJar()`, depend on the core as
`su.onno:onno-framework` (`api`) + `onno-framework-starter` (`implementation`), and
publish to the private `onno-enterprise` GitHub Packages repo.

## Robust external-HTTP-client template (Guesty/Tochka)

`Default*Client.execute()`: attach the bearer token, map non-2xx to a typed
`*ApiException`, **refresh the token once on 401 then retry** (the refresh does not consume a retry
attempt), and **exponential backoff on 429/5xx**. Use `ParameterizedTypeReference` for generic
response envelopes (Tochka wraps everything in `{Data, Links, Meta}`; the client unwraps it to plain
records). Token managers cache the token **in memory and on disk** when the provider rate-limits
token issuance (Guesty caps at 5/24h) or **rotates the refresh token** on every renewal (Tochka — so
persist the latest refresh token, not the seed).

## Async external workflows are poll-based

Neither Guesty nor Tochka nor SES.HOSPEDAJES pushes webhooks here. SES.HOSPEDAJES `registrar()`
returns a `numeroLote`; outcomes are polled later via `consultaLote`. Tochka `statement(...)` polls
every 2s up to 60s until `status == Ready`. Model these as a submit-then-reconcile pair, with a
scheduled reconcile job on the host side.

## Stateful connectors own their own table

`onno-hospedajes-starter` keeps an audit ledger (`HospedajesCommunicationLog`, JDBI, table
`onno_hospedajes_comunicacion`) tracking SUBMITTED → REGISTERED|REJECTED → CANCELLED keyed by
`(numeroLote, orden)` with the caller's `referencia` for **idempotency**. Its auto-config orders
itself `@AutoConfiguration(after = DataSourceAutoConfiguration.class)` and makes the ledger
`@ConditionalOnBean(DataSource.class)`, then creates its schema with raw JDBI DDL (`onno_`-prefixed
table, `_`-prefixed columns). The service takes the log as an `ObjectProvider` so it degrades
gracefully with no DataSource.

## Host-side integration idioms (the `reference/` examples)

These show how the *consuming app* wires a connector to its domain (illustrative source, not built):

- **React to a domain state change → call the external authority.** A Spring Data
  `AfterSaveCallback<Booking>` (or, in framework terms, an `@EventListener` on `DocumentPostedEvent`)
  fires when a `Booking` reaches `CHECKED_IN`, maps it to a *parte*, and submits it. Guard with the
  ledger for idempotency (`ledger.hasActiveSubmission(referencia)`), and **log external failures
  without blocking the save**.
- **Map `Ref<T>` to the external schema with `RefResolver`.** `refResolver.resolve(ref).orElse(null)`
  dereferences `Ref<Property>`/`Ref<Client>`/`Ref<Country>` into catalog entities; enums map to
  external code systems via `switch`. `Ref<T>` + `RefResolver` are the canonical seam between app
  domain and connector code.
- **Validate before submit.** Run cross-field business rules the external XSD can't express, record
  invalid records as locally REJECTED (still retryable), and submit only the valid subset — turning
  opaque async rejections into precise local ones.
- **One switch gates both sides.** The host integration `@Configuration` reuses the same
  `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")` as the starter, so one flag
  turns on the connector and its host wiring together.

## When editing onno-enterprise, keep its docs in sync too

Update the connector's module README and the repo's top-level README module table when you add or
change a connector. (Known gap to fix when touching it: the top-level README table omits
`onno-tochka-starter`, and that module still references the old `su.onno:*` core coordinates instead
of `su.onno:*`.)
