# onno-observability-starter

Privacy-safe, best-effort telemetry for onno deployments. It provides:

- a bounded asynchronous HTTP exporter;
- an authenticated same-origin endpoint for browser UX signals;
- `TelemetryRecorder` for application-defined business outcomes;
- deployment, application, and framework version context on every batch.

The starter is disabled by default. onno-cloud injects these values into hosted tenant workloads:

```yaml
onno:
  telemetry:
    enabled: true
    endpoint: https://cloud.onno.su
    tenant: acme
    token: ${ONNO_TELEMETRY_TOKEN}
    deployment-id: ${ONNO_TELEMETRY_DEPLOYMENT_ID}
    application-version: ${ONNO_TELEMETRY_APPLICATION_VERSION}
    framework-version: ${ONNO_TELEMETRY_FRAMEWORK_VERSION}
```

Application code can record semantic outcomes without knowing where telemetry is stored:

```java
telemetry.outcome(
        "order.shipped",
        order.getAmount(),
        Long.valueOf(order.getPhotoCount()),
        Map.of("channel", "wildberries", "format", "10x15"));
```

The starter automatically records privacy-safe catalog/document/register lifecycle throughput from
`EntityChangedEvent`. A posted document contributes one `document.posted` business outcome; no
record id, number, field, or customer value is included. Application-specific outcomes add money or
domain quantities where that meaning is known.

Only configured low-cardinality dimensions leave the application. Do not put customer data, record
identifiers, field contents, email addresses, or free-form errors into event names or dimensions.
