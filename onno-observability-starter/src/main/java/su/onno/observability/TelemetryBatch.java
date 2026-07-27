package su.onno.observability;

import java.time.Instant;
import java.util.List;

/** Wire envelope sent from one deployed onno application to a telemetry collector. */
public record TelemetryBatch(
        int schemaVersion,
        String deploymentId,
        String applicationVersion,
        String frameworkVersion,
        Instant sentAt,
        List<TelemetryEvent> events) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
