package su.onno.spring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnoMetricsTest {

    @Test
    void recordsTimedOperationsAsMicrometerMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OnnoMetrics metrics = new OnnoMetrics(registry);

        metrics.time("onno.document.post", 3, () -> "ok");

        assertThat(registry.get(OnnoMetrics.DURATION_METER)
                .tag("operation", "onno.document.post")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get(OnnoMetrics.ITEMS_METER)
                .tag("operation", "onno.document.post")
                .summary()
                .totalAmount()).isEqualTo(3.0);
    }
}
