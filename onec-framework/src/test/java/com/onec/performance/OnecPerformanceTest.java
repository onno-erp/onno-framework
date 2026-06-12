package com.onec.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnecPerformanceTest {

    @Test
    void recordReturnsSupplierResult() {
        String result = OnecPerformance.record("test.operation", 1, () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void recordPropagatesFailures() {
        assertThatThrownBy(() -> OnecPerformance.record("test.operation", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }
}
