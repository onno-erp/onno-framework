package su.onno.observability;

import static org.assertj.core.api.Assertions.assertThat;

import su.onno.events.EntityChangedEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class FrameworkTelemetryListenerTest {

    @Test
    void recordsDocumentPostingAsAutomaticBusinessThroughputWithoutRecordIdentifiers() {
        List<TelemetryEvent> events = new ArrayList<>();
        FrameworkTelemetryListener listener =
                new FrameworkTelemetryListener(new TelemetryRecorder(events::add));

        listener.onEntityChanged(new EntityChangedEvent(
                EntityChangedEvent.POSTED, EntityChangedEvent.DOCUMENT, "Orders",
                UUID.randomUUID(), "ORD-123"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).kind()).isEqualTo("erp");
        assertThat(events.get(1).kind()).isEqualTo("business");
        assertThat(events.get(1).name()).isEqualTo("document.posted");
        assertThat(events.get(1).quantity()).isEqualTo(1);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.dimensions()).containsEntry("entityName", "Orders");
            assertThat(event.dimensions().values()).doesNotContain("ORD-123");
        });
    }
}
