package su.onno.observability;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Application-facing API for semantic business outcomes and custom operational events.
 */
public class TelemetryRecorder {

    private final TelemetrySink sink;
    private final Clock clock;

    public TelemetryRecorder(TelemetrySink sink) {
        this(sink, Clock.systemUTC());
    }

    TelemetryRecorder(TelemetrySink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    /** Record a successful business outcome, such as {@code order.shipped}. */
    public void outcome(String name, BigDecimal value, Long quantity, Map<String, String> dimensions) {
        record("business", name, "success", null, value, quantity, dimensions);
    }

    /** Record a named application event without attaching business values. */
    public void event(String kind, String name, String outcome, Map<String, String> dimensions) {
        record(kind, name, outcome, null, null, null, dimensions);
    }

    /** Record a timed application operation. */
    public void timing(String kind, String name, String outcome, long durationMs,
                       Map<String, String> dimensions) {
        record(kind, name, outcome, Math.max(0, durationMs), null, null, dimensions);
    }

    private void record(String kind, String name, String outcome, Long durationMs,
                        BigDecimal value, Long quantity, Map<String, String> dimensions) {
        sink.accept(new TelemetryEvent(
                UUID.randomUUID().toString(),
                kind,
                name,
                Instant.now(clock),
                outcome,
                durationMs,
                value,
                quantity,
                null,
                null,
                dimensions == null ? Map.of() : Map.copyOf(dimensions)));
    }
}
