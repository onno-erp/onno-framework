package su.onno.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class BrowserTelemetryControllerTest {

    @Test
    void onlyForwardsAllowlistedBrowserSignals() {
        List<TelemetryEvent> received = new ArrayList<>();
        BrowserTelemetryController controller = new BrowserTelemetryController(received::add);
        Instant now = Instant.parse("2026-07-27T10:00:00Z");

        controller.events(new BrowserTelemetryController.BrowserBatch("session-1", List.of(
                new BrowserTelemetryController.BrowserEvent(
                        "one", "api.request", now, "success", 42L, "/api/orders/123", Map.of("method", "GET")),
                new BrowserTelemetryController.BrowserEvent(
                        "two", "customer.payload", now, null, null, null, Map.of()))));

        assertThat(received).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("ux");
            assertThat(event.name()).isEqualTo("api.request");
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.durationMs()).isEqualTo(42L);
        });
    }
}
