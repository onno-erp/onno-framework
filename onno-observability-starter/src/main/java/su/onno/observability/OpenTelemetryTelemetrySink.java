package su.onno.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Maps onno's stable semantic telemetry API to standard OpenTelemetry metrics and spans.
 *
 * <p>Export, batching, retry, sampling, and resource attributes are owned by the OpenTelemetry SDK
 * or Java agent. Hosted applications receive the agent and standard {@code OTEL_*} configuration
 * from onno-cloud.</p>
 */
final class OpenTelemetryTelemetrySink implements TelemetrySink {

    static final String SCOPE = "su.onno.observability";
    static final String EVENT_COUNT = "onno.event.count";
    static final String BUSINESS_QUANTITY = "onno.business.quantity";
    static final String BUSINESS_VALUE = "onno.business.value";
    static final String OPERATION_DURATION = "onno.operation.duration";

    private static final int MAX_TEXT = 160;
    private static final int MAX_DIMENSIONS = 12;

    private final TelemetryProperties properties;
    private final LongCounter eventCount;
    private final LongUpDownCounter businessQuantity;
    private final DoubleUpDownCounter businessValue;
    private final DoubleHistogram operationDuration;
    private final Tracer tracer;

    public OpenTelemetryTelemetrySink(OpenTelemetry openTelemetry, TelemetryProperties properties) {
        this.properties = properties;
        Meter meter = openTelemetry.getMeter(SCOPE);
        this.eventCount = meter.counterBuilder(EVENT_COUNT)
                .setDescription("Count of named onno semantic events")
                .setUnit("{event}")
                .build();
        this.businessQuantity = meter.upDownCounterBuilder(BUSINESS_QUANTITY)
                .setDescription("Domain quantity attached to named onno business outcomes")
                .setUnit("{item}")
                .build();
        this.businessValue = meter.upDownCounterBuilder(BUSINESS_VALUE)
                .ofDoubles()
                .setDescription("Unitless business value attached to named onno outcomes")
                .setUnit("1")
                .build();
        this.operationDuration = meter.histogramBuilder(OPERATION_DURATION)
                .setDescription("Duration of named onno operations")
                .setUnit("ms")
                .build();
        this.tracer = openTelemetry.getTracer(SCOPE);
    }

    @Override
    public void accept(TelemetryEvent rawEvent) {
        if (rawEvent == null) {
            return;
        }
        try {
            TelemetryEvent event = sanitize(rawEvent);
            Attributes metricAttributes = metricAttributes(event);
            eventCount.add(1, metricAttributes);
            if ("business".equals(event.kind()) && event.quantity() != null) {
                businessQuantity.add(event.quantity(), metricAttributes);
            }
            if ("business".equals(event.kind()) && event.value() != null) {
                businessValue.add(event.value().doubleValue(), metricAttributes);
            }
            if (event.durationMs() != null) {
                operationDuration.record(event.durationMs().doubleValue(), metricAttributes);
            }
            recordSpan(event);
        } catch (RuntimeException ignored) {
            // Telemetry is explicitly best effort and must never fail an ERP operation.
        }
    }

    @Override
    public double browserSampleRate() {
        return Math.max(0, Math.min(1, properties.getBrowserSampleRate()));
    }

    private void recordSpan(TelemetryEvent event) {
        Instant occurredAt = event.occurredAt();
        SpanBuilder builder = tracer.spanBuilder("onno." + event.kind() + " " + event.name())
                .setStartTimestamp(epochNanos(occurredAt), TimeUnit.NANOSECONDS)
                .setAllAttributes(spanAttributes(event));
        Span span = builder.startSpan();
        if (isFailure(event.outcome())) {
            span.setStatus(StatusCode.ERROR, event.outcome());
        } else if (event.outcome() != null) {
            span.setStatus(StatusCode.OK);
        }
        long durationMs = event.durationMs() == null ? 0 : event.durationMs();
        span.end(epochNanos(occurredAt.plusMillis(durationMs)), TimeUnit.NANOSECONDS);
    }

    private Attributes metricAttributes(TelemetryEvent event) {
        AttributesBuilder attributes = Attributes.builder()
                .put("onno.event.kind", event.kind())
                .put("onno.event.name", event.name());
        put(attributes, "onno.outcome", event.outcome());
        event.dimensions().forEach((key, value) ->
                put(attributes, "onno.dimension." + attributeName(key), value));
        return attributes.build();
    }

    private Attributes spanAttributes(TelemetryEvent event) {
        AttributesBuilder attributes = metricAttributes(event).toBuilder();
        put(attributes, "onno.event.id", event.id());
        put(attributes, "onno.ux.session.id", event.sessionId());
        put(attributes, "url.path", event.route());
        if (event.quantity() != null) {
            attributes.put("onno.business.quantity", event.quantity());
        }
        if (event.value() != null) {
            attributes.put("onno.business.value", event.value().doubleValue());
        }
        if (event.durationMs() != null) {
            attributes.put("onno.operation.duration_ms", event.durationMs());
        }
        return attributes.build();
    }

    private TelemetryEvent sanitize(TelemetryEvent event) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        Set<String> allowed = properties.getAllowedDimensions();
        if (event.dimensions() != null) {
            event.dimensions().forEach((key, value) -> {
                if (dimensions.size() < MAX_DIMENSIONS && allowed.contains(key) && value != null) {
                    dimensions.put(key, truncate(value));
                }
            });
        }
        return new TelemetryEvent(
                truncate(event.id()),
                fallback(truncate(event.kind()), "custom"),
                fallback(truncate(event.name()), "unnamed"),
                event.occurredAt() == null ? Instant.now() : event.occurredAt(),
                truncate(event.outcome()),
                event.durationMs() == null ? null : Math.max(0, event.durationMs()),
                event.value(),
                event.quantity(),
                truncate(event.sessionId()),
                normalizeRoute(event.route()),
                Map.copyOf(dimensions));
    }

    static String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        String normalized = route.split("[?#]", 2)[0]
                .replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", ":id")
                .replaceAll("/\\d+(?=/|$)", "/:id");
        return truncate(normalized);
    }

    private static String attributeName(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_.-]", "_")
                .toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        return clean.length() <= MAX_TEXT ? clean : clean.substring(0, MAX_TEXT);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void put(AttributesBuilder attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(AttributeKey.stringKey(key), value);
        }
    }

    private static long epochNanos(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                instant.getNano());
    }

    private static boolean isFailure(String outcome) {
        if (outcome == null) {
            return false;
        }
        String normalized = outcome.toLowerCase(Locale.ROOT);
        return normalized.equals("error") || normalized.equals("failure") || normalized.equals("failed");
    }
}
