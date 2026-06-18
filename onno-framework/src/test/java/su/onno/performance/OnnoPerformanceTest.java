package su.onno.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnoPerformanceTest {

    @Test
    void recordReturnsSupplierResult() {
        String result = OnnoPerformance.record("test.operation", 1, () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void recordPropagatesFailures() {
        assertThatThrownBy(() -> OnnoPerformance.record("test.operation", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }
}
