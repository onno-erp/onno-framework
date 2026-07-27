# onno-observability-starter

Privacy-safe, best-effort OpenTelemetry instrumentation for onno deployments. It provides:

- semantic OTel metrics and spans for business outcomes and application-defined operations;
- an authenticated same-origin endpoint for browser UX signals;
- `TelemetryRecorder` for application-defined business outcomes;
- automatic privacy-safe ERP lifecycle signals.

The starter is disabled by default. Enable Onno's semantic instruments with:

```yaml
onno:
  telemetry:
    enabled: true
    browser-sample-rate: 1.0
```

The sink does not implement a proprietary transport. It writes to the process-wide OpenTelemetry
API. Install the OpenTelemetry Java agent (recommended for complete HTTP, JDBC, runtime, and log
instrumentation) or configure an OpenTelemetry SDK, then use standard `OTEL_*` settings:

```bash
export OTEL_SERVICE_NAME=acme
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_RESOURCE_ATTRIBUTES='service.namespace=onno,onno.tenant.id=acme,deployment.environment.name=production'
```

The SDK or agent owns OTLP encoding, batching, retry, sampling, and export. Browser events still go
to the authenticated same-origin `/api/telemetry/events` endpoint; the backend records them through
the same OTel API, so collector credentials are never exposed to the browser.

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

The stable instruments are:

| Instrument | Type | Meaning |
|---|---|---|
| `onno.event.count` | counter | Count by `onno.event.kind`, `onno.event.name`, and `onno.outcome` |
| `onno.business.quantity` | up/down counter | Domain quantity by named business outcome |
| `onno.business.value` | double up/down counter | Numeric business value by named outcome |
| `onno.operation.duration` | histogram (`ms`) | Latency by named event/operation |

Each semantic event also produces a completed span. Session ids and normalized routes are span
attributes only; they are deliberately excluded from metrics to avoid unbounded cardinality.

Only configured low-cardinality dimensions leave the application. Do not put customer data, record
identifiers, field contents, email addresses, or free-form errors into event names or dimensions.
