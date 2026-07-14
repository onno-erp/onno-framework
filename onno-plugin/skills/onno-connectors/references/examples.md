# Connector Examples

## Table Of Contents

- Starter Shape
- Properties
- Auto-Configuration
- HTTP Client Pattern
- Host Event Listener
- Audit Ledger

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
    private boolean enabled = true;

    /** Base URL of the Acme API. */
    private URI baseUrl = URI.create("https://api.acme.example");

    /** OAuth client id. */
    private String clientId;

    /** OAuth client secret. */
    private String clientSecret;
}
```

Property Javadoc feeds generated config docs. Do not hand-edit generated config tables.

## Auto-Configuration

```java
@AutoConfiguration
@EnableConfigurationProperties(AcmeProperties.class)
@ConditionalOnProperty(prefix = "onno.acme", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OnnoAcmeAutoConfiguration {

    @Bean
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
    AcmeService acmeService(AcmeClient client, RefResolver refs) {
        return new AcmeService(client, refs);
    }
}
```

Every replaceable bean should be `@ConditionalOnMissingBean`.

## HTTP Client Pattern

```java
public final class DefaultAcmeClient implements AcmeClient {
    private final RestClient rest;
    private final AcmeTokenManager tokens;

    public <T> T execute(Function<RequestHeadersSpec<?>, T> call) {
        String token = tokens.accessToken();
        try {
            return call.apply(withAuth(token));
        } catch (AcmeUnauthorizedException ex) {
            String refreshed = tokens.refresh();
            return call.apply(withAuth(refreshed));
        } catch (AcmeRateLimitedException | AcmeServerException ex) {
            return retryWithBackoff(call);
        }
    }
}
```

Map non-2xx responses to typed exceptions. Refresh once on 401. Back off on 429/5xx.

## Host Event Listener

```java
@Component
@ConditionalOnProperty(prefix = "onno.acme", name = "enabled", havingValue = "true",
        matchIfMissing = true)
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

The host app owns `Booking`. The connector only knows how to call Acme.

## Audit Ledger

Use a connector-owned table when the external workflow is asynchronous or must be idempotent:

```text
onno_acme_submission
  id
  local_document_id
  external_id
  status
  submitted_at
  last_error
```

Use an `onno_` prefix and create this connector-owned table in the connector, not through framework
metadata. Keep it small, diagnostic, and tied to external communication state.
