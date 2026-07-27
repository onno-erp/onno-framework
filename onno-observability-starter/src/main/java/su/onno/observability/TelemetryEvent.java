package su.onno.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One privacy-safe product or operational signal.
 *
 * <p>Names and dimension values must be low-cardinality metadata, never customer data, record
 * identifiers, field contents, email addresses, or free-form error messages.</p>
 */
public record TelemetryEvent(
        String id,
        String kind,
        String name,
        Instant occurredAt,
        String outcome,
        Long durationMs,
        BigDecimal value,
        Long quantity,
        String sessionId,
        String route,
        Map<String, String> dimensions) {
}
