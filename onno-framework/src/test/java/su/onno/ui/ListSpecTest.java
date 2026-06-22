package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
        // A plain-string filter shows each value verbatim (value == label).
        assertThat(f.options()).extracting(ListSpec.Option::value, ListSpec.Option::label)
                .containsExactly(tuple("2024", "2024"), tuple("2025", "2025"), tuple("2026", "2026"));
    }

    @Test
    void multiOptionsFilterCarriesItsChoices() {
        ListSpec spec = new ListSpec();
        spec.filter("role").label("Role").multiOptions("Хирург", "Терапевт");

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.field()).isEqualTo("role");
        assertThat(f.label()).isEqualTo("Role");
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.MULTI_OPTIONS);
        assertThat(f.options()).extracting(ListSpec.Option::value, ListSpec.Option::label)
                .containsExactly(tuple("Хирург", "Хирург"), tuple("Терапевт", "Терапевт"));
    }

    @Test
    void optionsFilterSplitsValueFromLabel() {
        Map<String, String> valueToLabel = new LinkedHashMap<>();
        valueToLabel.put("NEW", "Новый");
        valueToLabel.put("DONE", "Готово");

        ListSpec spec = new ListSpec();
        spec.filter("statusName").label("Статус").options(valueToLabel);

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.OPTIONS);
        // The query matches on the value; the UI renders the label. Map order is preserved.
        assertThat(f.options()).extracting(ListSpec.Option::value, ListSpec.Option::label)
                .containsExactly(tuple("NEW", "Новый"), tuple("DONE", "Готово"));
    }

    @Test
    void multiOptionsFilterSplitsValueFromLabel() {
        Map<String, String> valueToLabel = new LinkedHashMap<>();
        valueToLabel.put("FILES_RECEIVED", "Файлы получены");
        valueToLabel.put("DOWNLOADING", "Загрузка");

        ListSpec spec = new ListSpec();
        spec.filter("statusName").multiOptions(valueToLabel);

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.MULTI_OPTIONS);
        assertThat(f.options()).extracting(ListSpec.Option::value, ListSpec.Option::label)
                .containsExactly(tuple("FILES_RECEIVED", "Файлы получены"),
                        tuple("DOWNLOADING", "Загрузка"));
    }

    @Test
    void containsFilterIsTypeaheadWithNoOptions() {
        ListSpec spec = new ListSpec();
        spec.filter("doctorName").label("Doctor").contains();

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.CONTAINS);
        assertThat(f.options()).isEmpty();
    }

    @Test
    void startsWithFilterIsTypeaheadWithNoOptions() {
        ListSpec spec = new ListSpec();
        spec.filter("doctorName").startsWith();

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.STARTS_WITH);
        assertThat(f.options()).isEmpty();
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
