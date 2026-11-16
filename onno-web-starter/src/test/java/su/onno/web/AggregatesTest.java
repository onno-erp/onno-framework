package su.onno.web;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregatesTest {

    private static final Set<String> COLUMNS = Set.of("gross", "net");

    @Test
    void countNeedsNoField() {
        assertThat(Aggregates.expression("count", null, COLUMNS)).isEqualTo("COUNT(*)");
        assertThat(Aggregates.expression(null, null, COLUMNS)).isEqualTo("COUNT(*)");
        assertThat(Aggregates.expression("  ", null, COLUMNS)).isEqualTo("COUNT(*)");
    }

    @Test
    void sumCoalescesToZero() {
        assertThat(Aggregates.expression("sum", "gross", COLUMNS)).isEqualTo("COALESCE(SUM(gross), 0)");
    }

    @Test
    void otherFunctionsPassThrough() {
        assertThat(Aggregates.expression("avg", "net", COLUMNS)).isEqualTo("AVG(net)");
        assertThat(Aggregates.expression("MAX", "gross", COLUMNS)).isEqualTo("MAX(gross)");
    }

    @Test
    void rejectsUnknownMetric() {
        assertThatThrownBy(() -> Aggregates.expression("median", "gross", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrUnknownField() {
        assertThatThrownBy(() -> Aggregates.expression("sum", null, COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Aggregates.expression("sum", "salary; DROP TABLE x", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Aggregates.expression("sum", "unknown", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
