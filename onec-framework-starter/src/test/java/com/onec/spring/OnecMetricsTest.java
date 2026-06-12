package com.onec.spring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnecMetricsTest {

    @Test
    void recordsTimedOperationsAsMicrometerMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OnecMetrics metrics = new OnecMetrics(registry);

        metrics.time("onec.document.post", 3, () -> "ok");

        assertThat(registry.get(OnecMetrics.DURATION_METER)
                .tag("operation", "onec.document.post")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get(OnecMetrics.ITEMS_METER)
                .tag("operation", "onec.document.post")
                .summary()
                .totalAmount()).isEqualTo(3.0);
    }
}
