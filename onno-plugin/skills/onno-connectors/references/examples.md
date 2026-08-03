# Connector Examples

## Table Of Contents

- Starter Shape
- Properties
- Auto-Configuration
- HTTP Client Pattern
- Pull Sync Boundary
- Host Event Listener
- Audit Ledger
- Build, Tests, And Publication

## Starter Shape

```text
onno-acme-starter/
  build.gradle.kts
  src/main/java/com/acme/onno/AcmeProperties.java
  src/main/java/com/acme/onno/AcmeTokenManager.java
  src/main/java/com/acme/onno/AcmeClient.java
  src/main/java/com/acme/onno/DefaultAcmeClient.java
  src/main/java/com/acme/onno/AcmeService.java
  src/main/java/com/acme/onno/OnnoAcmeAutoConfiguration.java
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## Properties

```java
@ConfigurationProperties(prefix = "onno.acme")
public class AcmeProperties {
    /** Whether the Acme connector is enabled. */
    private boolean enabled = false;

    /** Base URL of the Acme API. */
    private URI baseUrl = URI.create("https://api.acme.example");

    /** OAuth client id. */
    private String clientId;

    /** OAuth client secret. */
    private String clientSecret;
}
```

Add `spring-boot-configuration-processor` as both `compileOnly` and `annotationProcessor`. Property
Javadoc feeds metadata packaged in this connector's JAR; it does not enter onno-framework's
generated `docs/CONFIGURATION.md`. Document the properties in the connector README.

## Auto-Configuration

```java
@AutoConfiguration
@EnableConfigurationProperties(AcmeProperties.class)
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = "onno.acme", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class OnnoAcmeAutoConfiguration {

    @Bean
    @ConditionalOnBean(RestClient.Builder.class)
    @ConditionalOnMissingBean
    AcmeTokenManager acmeTokenManager(AcmeProperties properties) {
        return new AcmeTokenManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    AcmeClient acmeClient(RestClient.Builder builder, AcmeProperties properties,
            AcmeTokenManager tokens) {
        return new DefaultAcmeClient(builder.baseUrl(properties.getBaseUrl().toString()).build(),
                tokens);
    }

    @Bean
    @ConditionalOnMissingBean
    AcmePullService acmePullService(AcmeClient client) {
        return new AcmePullService(client);
    }
}
```

Every replaceable singleton should be `@ConditionalOnMissingBean`. Keep optional datasource-backed
ledger wiring in a separate auto-configuration ordered after `DataSourceAutoConfiguration` and
conditional on a `DataSource`; the HTTP client must still work without a database.

## HTTP Client Pattern

```java
public final class DefaultAcmeClient implements AcmeClient {
    private final RestClient rest;
    private final AcmeTokenManager tokens;

    public <T> T executeIdempotent(Function<String, T> call) {
        String token = tokens.accessToken();
        boolean refreshed = false;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                return call.apply(token);
            } catch (AcmeUnauthorizedException ex) {
                if (refreshed) throw ex;
                token = tokens.refresh();
                refreshed = true;
            } catch (AcmeRateLimitedException ex) {
                if (attempt == 4) throw ex;
                sleep(ex.retryAfter().orElseGet(() -> backoffWithJitter(attempt)));
            } catch (AcmeServerException ex) {
                if (attempt == 4) throw ex;
                sleep(backoffWithJitter(attempt));
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
```

Map non-2xx responses to typed exceptions. Refresh once on 401. Bound exponential retry with jitter
and honor `Retry-After` for 429. Automatically retry only GET/pull calls or writes carrying the same
provider idempotency key. Never log tokens or secrets.

## Pull Sync Boundary

The connector returns external DTO pages and cursors:

```java
public record AcmePage<T>(List<T> items, String nextCursor) {}

public final class AcmePullService {
    private final AcmeClient client;

    public AcmePullService(AcmeClient client) {
        this.client = client;
    }

    public AcmePage<AcmeContact> pullContacts(String cursor) {
        return client.contacts(cursor);
    }
}
```

The host app maps `AcmeContact` into its `Customer` catalog and owns repositories, `RefResolver`,
scheduled jobs, and transaction boundaries. Upsert by `(connection, externalType, externalId)` and
external revision. Advance the checkpoint only after every projection commits. A crash before the
checkpoint is safe because replay applies the same idempotent upsert. Define tombstone/deletion
behavior explicitly.

## Host Event Listener

```java
@Component
@ConditionalOnProperty(prefix = "onno.acme", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class AcmeBookingExport {
    private final AcmeService acme;

    public AcmeBookingExport(AcmeService acme) {
        this.acme = acme;
    }

    @EventListener
    public void onPosted(DocumentPostedEvent event) {
        if (event.document() instanceof Booking booking) {
            acme.exportBooking(booking);
        }
    }
}
```

This class belongs in the host app: it owns `Booking`, mapping, `RefResolver`, and orchestration. The
reusable connector only knows Acme DTOs and calls.

## Audit Ledger

Use a connector-owned table when the external workflow is asynchronous or must be idempotent:

```text
onno_acme_submission
  id
  connection
  external_type
  external_id
  external_revision
  local_document_id
  status
  attempt_count
  checkpoint
  submitted_at
  updated_at
  last_error
```

Use an `onno_` prefix and create this connector-owned table in the connector, not through framework
metadata. Enforce a unique provider idempotency key or unique
`(connection, external_type, external_id, external_revision)`. Keep it small, diagnostic, and tied
to external communication state.

## Build, Tests, And Publication

Use Java 21, `java-library`, `maven-publish`, `withSourcesJar()`, `withJavadocJar()`, the publisher's
own Maven group/package, and released onno coordinates. Test token expiry/single-flight refresh,
DTO/page parsing, one 401 refresh, bounded 429/5xx behavior, unsafe-write non-retry, cursor replay,
partial projection failure, checkpoint-after-commit, ledger uniqueness, and auto-configuration
disabled/enabled/override/no-datasource cases. Run `publishToMavenLocal` and compile a tiny external
consumer; inspect configuration metadata and `AutoConfiguration.imports` in the JAR.

After a public release, validate a `category: "connector"` entry against
`community/registry.schema.json`, update `community/registry.json`, run
`./gradlew generateIntegrationsDoc`, and submit the registry plus generated `INTEGRATIONS.md`.
