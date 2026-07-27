package su.onno.observability;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authenticated same-origin endpoint that enriches and forwards privacy-safe browser signals. */
@RestController
@RequestMapping("/api/telemetry")
public class BrowserTelemetryController {

    private static final int MAX_EVENTS = 100;
    private static final Set<String> ALLOWED_NAMES =
            Set.of("route.viewed", "api.request", "ui.error");

    private final TelemetrySink sink;

    public BrowserTelemetryController(TelemetrySink sink) {
        this.sink = sink;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void events(@RequestBody BrowserBatch batch) {
        if (batch == null || batch.events() == null) {
            return;
        }
        batch.events().stream().limit(MAX_EVENTS)
                .filter(event -> event != null && ALLOWED_NAMES.contains(event.name()))
                .forEach(event -> sink.accept(new TelemetryEvent(
                        event.id() == null ? UUID.randomUUID().toString() : event.id(),
                        "ux",
                        event.name(),
                        event.occurredAt() == null ? Instant.now() : event.occurredAt(),
                        event.outcome(),
                        event.durationMs(),
                        null,
                        null,
                        batch.sessionId(),
                        event.route(),
                        event.dimensions() == null ? Map.of() : event.dimensions())));
    }

    public record BrowserBatch(String sessionId, List<BrowserEvent> events) {
    }

    public record BrowserEvent(
            String id,
            String name,
            Instant occurredAt,
            String outcome,
            Long durationMs,
            String route,
            Map<String, String> dimensions) {
    }
}
