# Extending onno-framework

How to build, publish, and list a community extension. The framework is designed to be extended
**without forking**: you ship a separate artifact that the host application opts into. This guide is
the public companion to the architecture reference in [ARCHITECTURE.md](ARCHITECTURE.md) and the
modeling playbook in [AGENTS.md](../AGENTS.md).

> Built something? Jump to [Get your extension listed](#get-your-extension-listed).

## The four extension surfaces

Pick the one that matches what you're adding. Most "integrations" are **connectors**.

| Surface | You're adding… | Mechanism |
| --- | --- | --- |
| **Connector** | a binding to an external system (a PMS, a bank, a marketplace, an ERP) | a Spring Boot auto-configuration starter that exposes a typed client + sync service |
| **SPI** | a pluggable implementation of a framework contract | a `@Bean` implementing an SPI interface (`MediaStorage`, `MailDispatcher`, `MailEventVerifier`, an additive `AuthMethodsContributor` login button, a custom `SecurityFilterChain`/`UserDetailsService`, a Kafka `EventHandler`) |
| **UI** | a dashboard widget, page, or action | `Page`/`Layout`/`EntityView` beans and app-registered custom widgets/actions (see [onno-ui-starter/README.md](../onno-ui-starter/README.md)) |
| **Skill / plugin** | guidance that makes an AI agent good at your domain | a Claude skill published through a plugin marketplace (see [.claude-plugin/marketplace.json](../.claude-plugin/marketplace.json)) |

A connector and an SPI both ship as a starter; the difference is whether you wrap an outside system
or satisfy a framework contract. The rest of this guide focuses on the **starter** shape since it
covers both.

## Key idea: a connector wraps an external system, it does not model the business

A connector defines **zero** framework metadata — no `@Catalog`/`@Document`/registers/posting/UI.
The catalogs, documents, registers, posting, and UI live in the **consuming application**. The only
framework types a connector typically touches are `su.onno.types.Ref` and
`su.onno.types.RefResolver`. A connector is a Spring Boot **auto-configuration starter that exposes
a typed client + service for one external system**; the host app wires it into its domain.

This keeps the seam clean: your integration is reusable across any app built on the framework, and
the app owns its own model.

## The starter shape

```
onno-<name>-starter/
  <Name>Properties.java          @ConfigurationProperties(prefix = "onno.<name>")
  <Name>Client.java              the typed client interface
  Default<Name>Client.java       typed HTTP/SDK client implementation
  <Name>Service.java             convenience facade (pagination, polling, mapping)
  Onno<Name>AutoConfiguration.java   @AutoConfiguration, beans @ConditionalOnMissingBean
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports   # lists your @AutoConfiguration class
  README.md
```

The auto-configuration:

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "onno.shopify", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ShopifyProperties.class)
public class OnnoShopifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RestClient.class)
    public ShopifyClient shopifyClient(ShopifyProperties props) {
        return new DefaultShopifyClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ShopifyService shopifyService(ShopifyClient client) {
        return new ShopifyService(client);
    }
}
```

- Gate the whole starter with `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")`.
- Make **every** bean `@ConditionalOnMissingBean` so the host can override any of them.
- List the auto-configuration class in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  (Spring Boot 3's mechanism — one fully-qualified class name per line). Adding the dependency is then
  enough; no `@Import` needed in the host.
- Build with `java-library` + `withSourcesJar()` / `withJavadocJar()`. Depend on the published core:
  `su.onno:onno-framework` (`api`) and, if you need auto-config helpers,
  `su.onno:onno-framework-starter` (`implementation`).

Study a real starter in this repo for the full shape — [`onno-mail-starter/`](../onno-mail-starter)
is a good, self-contained example (properties, conditional beans, pluggable dispatcher SPI, imports
file).

## Conventions that keep the ecosystem clean

These are the rules that let many independent extensions coexist. Follow them so installs don't
collide and config stays predictable.

- **Use your own Maven group.** `su.onno` (official) and `su.onno.enterprise`
  (commercial) are **reserved** — do not publish under them. Use your own, e.g.
  `io.github.<you>` or `com.<yourcompany>`.
- **Use your own Java package.** The `su.onno.*` package space is **reserved** for the framework.
  Put your code under your own package (e.g. `com.acme.onno.shopify`).
- **Name the artifact `onno-<name>-starter`.** It signals an onno extension and sorts well.
- **Namespace config as `onno.<name>.*`** and include an `enabled` flag (default off is the safe
  choice for a starter that needs credentials). Bind it with `@ConfigurationProperties` and document
  every property in your README.
- **Declare the onno-framework version you support.** State the version (or range) you build and test
  against in your README and in your registry entry — the published surface is the
  `su.onno:*` artifacts on Maven Central.
- **Tag your repo** `onno-framework` and `onno-extension` on GitHub so others can find it.
- **Reserved plugin id:** the Gradle plugin id `su.onno.desktop` is the framework's; don't reuse it.

## Reuse these domain seams (don't reinvent them)

When your extension reacts to the host's business data, use the framework's seams — they're covered
in depth in [ARCHITECTURE.md](ARCHITECTURE.md):

- **`Ref<T>` + `RefResolver`** are the canonical bridge between app domain objects and your code.
  `refResolver.resolve(ref).orElse(null)` dereferences a `Ref<Customer>` into the catalog entity;
  map enums to the external system's codes with a `switch`.
- **React to a posted document with a Spring `@EventListener` on `DocumentPostedEvent`** (full
  dependency injection), not from inside `handlePosting`. Guard external calls so a failure logs but
  doesn't block the host's save/post.
- **Posting runs in its own transaction.** If your host-side glue saves a document and then posts it,
  let the save commit first — don't wrap save+post in one `@Transactional` (it silently leaves the
  document unposted).
- **Async external workflows are usually submit-then-reconcile.** If the external system doesn't push
  webhooks, model a submit call plus a scheduled reconcile job, and keep an idempotency ledger
  (a starter may own its own `onno_`-prefixed table).

## Definition of done

Before you publish and ask to be listed:

- [ ] Builds against a supported onno-framework version (state which one).
- [ ] Has a README: what it does, the `onno.<name>.*` properties, a minimal setup snippet.
- [ ] Has a declared license (an SPDX id in the repo).
- [ ] Uses your own Maven group and Java package (not `su.onno` / `su.onno.*`).
- [ ] For a starter: gated by `onno.<name>.enabled`, every bean `@ConditionalOnMissingBean`, and a
      valid `AutoConfiguration.imports` file.
- [ ] `./gradlew publishToMavenLocal` produces a consumable artifact **with sources, javadoc, and a
      POM** — a build can pass yet still fail to produce these.

## Get your extension listed

The community catalog is [INTEGRATIONS.md](../INTEGRATIONS.md), generated from a machine-readable
registry. To get listed:

1. Add an entry to [`community/registry.json`](../community/registry.json) (it validates against
   [`community/registry.schema.json`](../community/registry.schema.json)).
2. Regenerate the catalog: `./gradlew generateIntegrationsDoc`.
3. Open a PR with both files. See the
   [listing criteria](../CONTRIBUTING.md#listing-a-community-integration) in `CONTRIBUTING.md`, or
   open a [community integration submission](https://github.com/onno-erp/onno-framework/issues/new?template=integration-submission.yml)
   issue and a maintainer will help.

Listed projects are maintained by their authors and are not endorsed by the onno-framework team.
