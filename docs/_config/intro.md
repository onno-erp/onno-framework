Every `onec.*` configuration property, by module, with type and default. Each integration starter is
auto-configured on the classpath and gated by its own `onec.<module>.enabled` flag (default `true`,
except Kafka inbound). Standard Spring keys (`spring.datasource.*`, `spring.mail.*`,
`spring.security.oauth2.client.*`) are used where noted and are not repeated here.

> **You don't edit the tables below.** They are generated from each starter's
> `@ConfigurationProperties` Javadoc via `spring-configuration-metadata.json`. To change a row, edit
> the property's Javadoc (description) or add a default in that module's
> `META-INF/additional-spring-configuration-metadata.json`, then run `./gradlew generateConfigDocs`.
> Editorial prose lives in `docs/_config/`.
