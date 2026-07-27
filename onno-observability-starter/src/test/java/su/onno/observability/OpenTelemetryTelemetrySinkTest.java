package su.onno.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryTelemetrySinkTest {

    @Test
    void emitsNamedMetricsAndACompletedSpan() {
        InMemoryMetricExporter metrics = InMemoryMetricExporter.create();
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(metrics).build())
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .build();
        TelemetryProperties properties = new TelemetryProperties();
        OpenTelemetryTelemetrySink sink = new OpenTelemetryTelemetrySink(openTelemetry, properties);

        sink.accept(new TelemetryEvent(
                "event-1", "business", "order.shipped", Instant.parse("2026-07-27T10:00:00Z"),
                "success", 42L, new BigDecimal("1250.50"), 3L, null, null,
                Map.of("channel", "wildberries")));
        meterProvider.forceFlush().join(5_000, TimeUnit.MILLISECONDS);

        assertThat(metrics.getFinishedMetricItems())
                .extracting(item -> item.getName())
                .contains(
                        OpenTelemetryTelemetrySink.EVENT_COUNT,
                        OpenTelemetryTelemetrySink.BUSINESS_QUANTITY,
                        OpenTelemetryTelemetrySink.BUSINESS_VALUE,
                        OpenTelemetryTelemetrySink.OPERATION_DURATION);
        assertThat(spans.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("onno.business order.shipped");
            assertThat(span.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("onno.event.name")))
                    .isEqualTo("order.shipped");
        });

        meterProvider.close();
        tracerProvider.close();
    }

    @Test
    void normalizesIdentifiersOutOfRoutes() {
        assertThat(OpenTelemetryTelemetrySink.normalizeRoute(
                "/documents/550e8400-e29b-41d4-a716-446655440000?tab=lines"))
                .isEqualTo("/documents/:id");
        assertThat(OpenTelemetryTelemetrySink.normalizeRoute("/catalog/42/edit"))
                .isEqualTo("/catalog/:id/edit");
    }
}
