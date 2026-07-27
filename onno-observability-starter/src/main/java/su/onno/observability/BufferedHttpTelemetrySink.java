package su.onno.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, best-effort exporter. The caller only offers to an in-memory queue; HTTP is performed on
 * one daemon thread and failures are retried without ever blocking or failing business work.
 */
public final class BufferedHttpTelemetrySink implements TelemetrySink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BufferedHttpTelemetrySink.class);
    private static final int MAX_TEXT = 160;
    private static final int MAX_DIMENSIONS = 12;

    private final TelemetryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final ArrayBlockingQueue<TelemetryEvent> queue;
    private final ScheduledExecutorService worker;
    private final AtomicLong dropped = new AtomicLong();
    private final URI ingestUri;

    public BufferedHttpTelemetrySink(TelemetryProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .build());
    }

    BufferedHttpTelemetrySink(TelemetryProperties properties, ObjectMapper objectMapper, HttpClient http) {
        validate(properties);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
        this.ingestUri = URI.create(stripTrailingSlash(properties.getEndpoint())
                + "/telemetry/v1/"
                + URLEncoder.encode(properties.getTenant(), StandardCharsets.UTF_8)
                + "/batches");
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "onno-telemetry-export");
            thread.setDaemon(true);
            return thread;
        });
        this.worker.scheduleWithFixedDelay(
                this::flushSafely,
                properties.getFlushIntervalMs(),
                properties.getFlushIntervalMs(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void accept(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        if (!queue.offer(sanitize(event))) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                log.warn("Dropped {} telemetry event(s): bounded queue is full", count);
            }
        }
    }

    @Override
    public double browserSampleRate() {
        return Math.max(0, Math.min(1, properties.getBrowserSampleRate()));
    }

    void flush() {
        List<TelemetryEvent> batch = new ArrayList<>(Math.max(1, properties.getBatchSize()));
        queue.drainTo(batch, Math.max(1, properties.getBatchSize()));
        if (batch.isEmpty()) {
            return;
        }
        try {
            TelemetryBatch envelope = new TelemetryBatch(
                    TelemetryBatch.CURRENT_SCHEMA_VERSION,
                    properties.getDeploymentId(),
                    properties.getApplicationVersion(),
                    properties.getFrameworkVersion(),
                    Instant.now(),
                    List.copyOf(batch));
            HttpRequest request = HttpRequest.newBuilder(ingestUri)
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Authorization", "Bearer " + properties.getToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(envelope)))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("collector returned HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            // Preserve order where capacity allows. Delivery is explicitly best effort.
            for (TelemetryEvent event : batch) {
                if (!queue.offer(event)) {
                    dropped.incrementAndGet();
                }
            }
            log.debug("Telemetry delivery failed; retained {} event(s) for retry: {}",
                    batch.size(), ex.toString());
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException ex) {
            log.debug("Telemetry flush failed: {}", ex.toString());
        }
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
                truncate(event.kind()),
                truncate(event.name()),
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

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        return clean.length() <= MAX_TEXT ? clean : clean.substring(0, MAX_TEXT);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void validate(TelemetryProperties properties) {
        if (blank(properties.getEndpoint()) || blank(properties.getTenant()) || blank(properties.getToken())) {
            throw new IllegalStateException(
                    "onno.telemetry.enabled=true requires endpoint, tenant, and token");
        }
        if (properties.getFlushIntervalMs() <= 0 || properties.getRequestTimeoutMs() <= 0) {
            throw new IllegalStateException("onno.telemetry intervals must be positive");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void close() {
        worker.shutdown();
        flushSafely();
    }
}
