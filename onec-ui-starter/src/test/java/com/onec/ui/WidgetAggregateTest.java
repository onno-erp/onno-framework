package com.onec.ui;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetAggregateTest {

    private static final Set<String> COLUMNS = Set.of("gross", "net");

    @Test
    void countNeedsNoField() {
        assertThat(WidgetAggregate.expression("count", null, COLUMNS)).isEqualTo("COUNT(*)");
        assertThat(WidgetAggregate.expression(null, null, COLUMNS)).isEqualTo("COUNT(*)");
        assertThat(WidgetAggregate.expression("  ", null, COLUMNS)).isEqualTo("COUNT(*)");
    }

    @Test
    void sumCoalescesToZero() {
        assertThat(WidgetAggregate.expression("sum", "gross", COLUMNS)).isEqualTo("COALESCE(SUM(gross), 0)");
    }

    @Test
    void otherFunctionsPassThrough() {
        assertThat(WidgetAggregate.expression("avg", "net", COLUMNS)).isEqualTo("AVG(net)");
        assertThat(WidgetAggregate.expression("MAX", "gross", COLUMNS)).isEqualTo("MAX(gross)");
    }

    @Test
    void rejectsUnknownMetric() {
        assertThatThrownBy(() -> WidgetAggregate.expression("median", "gross", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrUnknownField() {
        assertThatThrownBy(() -> WidgetAggregate.expression("sum", null, COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WidgetAggregate.expression("sum", "salary; DROP TABLE x", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WidgetAggregate.expression("sum", "unknown", COLUMNS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
