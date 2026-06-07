package com.onec.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListSpecTest {

    @Test
    void optionsFilterCarriesItsChoices() {
        ListSpec spec = new ListSpec();
        spec.filter("season").options("2024", "2025", "2026");

        assertThat(spec.filters()).hasSize(1);
        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.field()).isEqualTo("season");
        assertThat(f.label()).isEqualTo("season"); // defaults to the field name
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.OPTIONS);
        assertThat(f.options()).containsExactly("2024", "2025", "2026");
    }

    @Test
    void dateRangeFilterHasNoOptions() {
        ListSpec spec = new ListSpec();
        spec.filter("checkIn").label("Check-in").dateRange();

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.field()).isEqualTo("checkIn");
        assertThat(f.label()).isEqualTo("Check-in");
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.DATE_RANGE);
        assertThat(f.options()).isEmpty();
    }

    @Test
    void filtersKeepDeclarationOrder() {
        ListSpec spec = new ListSpec();
        spec.filter("a").options("1");
        spec.filter("b").dateRange();

        assertThat(spec.filters()).extracting(ListSpec.Filter::field).containsExactly("a", "b");
    }

    @Test
    void noFiltersByDefault() {
        assertThat(new ListSpec().filters()).isEmpty();
    }
}
