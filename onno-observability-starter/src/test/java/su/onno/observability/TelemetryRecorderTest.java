package su.onno.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class TelemetryRecorderTest {

    @Test
    void recordsSemanticBusinessOutcomeWithoutCustomerPayload() {
        List<TelemetryEvent> events = new ArrayList<>();
        Instant now = Instant.parse("2026-07-27T10:00:00Z");
        TelemetryRecorder recorder = new TelemetryRecorder(events::add, Clock.fixed(now, ZoneOffset.UTC));

        recorder.outcome("order.shipped", new BigDecimal("1250.50"), 3L, Map.of("channel", "wb"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("business");
            assertThat(event.name()).isEqualTo("order.shipped");
            assertThat(event.outcome()).isEqualTo("success");
            assertThat(event.occurredAt()).isEqualTo(now);
            assertThat(event.value()).isEqualByComparingTo("1250.50");
            assertThat(event.quantity()).isEqualTo(3);
            assertThat(event.dimensions()).containsEntry("channel", "wb");
        });
    }
}
