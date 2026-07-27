package su.onno.observability;

import su.onno.events.EntityChangedEvent;

import org.springframework.context.event.EventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the framework's existing privacy-safe lifecycle event into immediately useful ERP
 * throughput. It intentionally ignores ids and natural keys.
 */
public final class FrameworkTelemetryListener {

    private final TelemetryRecorder recorder;

    public FrameworkTelemetryListener(TelemetryRecorder recorder) {
        this.recorder = recorder;
    }

    @EventListener
    public void onEntityChanged(EntityChangedEvent event) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        put(dimensions, "action", event.changeType());
        put(dimensions, "entityKind", event.entityType());
        put(dimensions, "entityName", event.entityName());
        recorder.event("erp", "entity.changed", "success", dimensions);
        if (EntityChangedEvent.DOCUMENT.equals(event.entityType())
                && EntityChangedEvent.POSTED.equals(event.changeType())) {
            recorder.outcome("document.posted", null, 1L, dimensions);
        }
    }

    private static void put(Map<String, String> dimensions, String key, String value) {
        if (value != null) dimensions.put(key, value);
    }
}
