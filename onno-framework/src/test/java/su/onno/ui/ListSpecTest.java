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
    void optionsFilterCanBeMadeMultiple() {
        ListSpec spec = new ListSpec();
        spec.filter("city").options("Madrid", "Paris").multiple();

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.MULTI_OPTIONS);
        assertThat(f.options()).extracting(ListSpec.Option::value, ListSpec.Option::label)
                .containsExactly(tuple("Madrid", "Madrid"), tuple("Paris", "Paris"));
    }

    @Test
    void multiOptionsFilterCanBeMadeSingleAgain() {
        ListSpec spec = new ListSpec();
        spec.filter("city").multiOptions("Madrid", "Paris").single();

        ListSpec.Filter f = spec.filters().get(0);
        assertThat(f.type()).isEqualTo(ListSpec.FilterType.OPTIONS);
        assertThat(f.options()).extracting(ListSpec.Option::value, ListSpec.Option::label)
                .containsExactly(tuple("Madrid", "Madrid"), tuple("Paris", "Paris"));
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

    @Test
    void feedModeAndPageSizeUnsetByDefault() {
        ListSpec spec = new ListSpec();
        // Unset → null / 0, so the resolver falls back to the global onno.ui.list.* defaults.
        assertThat(spec.feedMode()).isNull();
        assertThat(spec.pageSize()).isZero();
    }

    @Test
    void feedModeAndPageSizeAreAuthored() {
        ListSpec spec = new ListSpec();
        spec.feed(ListSpec.FeedMode.PAGED).pageSize(25);

        assertThat(spec.feedMode()).isEqualTo(ListSpec.FeedMode.PAGED);
        assertThat(spec.pageSize()).isEqualTo(25);
    }

    @Test
    void noCustomRendererByDefault() {
        assertThat(new ListSpec().customSpec()).isNull();
    }

    @Test
    void customRendererCarriesTypeLabelAndDefaultView() {
        ListSpec spec = new ListSpec();
        spec.custom("bookTiles").label("Shelf").defaultView();

        ListSpec.CustomSpec c = spec.customSpec();
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo("bookTiles");
        assertThat(c.label()).isEqualTo("Shelf");
        assertThat(c.isDefaultView()).isTrue();
    }

    @Test
    void repeatedCustomCallsAccumulateOnOneSpec() {
        ListSpec spec = new ListSpec();
        spec.custom("tiles").label("Tiles");
        // A later call replaces the type but keeps the same spec (mirrors map()).
        spec.custom("cards");

        ListSpec.CustomSpec c = spec.customSpec();
        assertThat(c.type()).isEqualTo("cards");
        assertThat(c.label()).isEqualTo("Tiles");
        assertThat(c.isDefaultView()).isFalse();
    }

    @Test
    void noGroupingByDefault() {
        ListSpec spec = new ListSpec();
        assertThat(spec.groupable()).isEmpty();
        assertThat(spec.aggregates()).isEmpty();
    }

    @Test
    void groupableColumnsKeepDeclarationOrder() {
        ListSpec spec = new ListSpec();
        spec.groupable("status", "assignedTo", "date");
        assertThat(spec.groupable()).containsExactly("status", "assignedTo", "date");
    }

    @Test
    void aggregatesCarryFieldFunctionAndLabel() {
        ListSpec spec = new ListSpec();
        spec.aggregate("total", ListSpec.Agg.SUM)
                .aggregate("amount", ListSpec.Agg.AVG, "Average");

        assertThat(spec.aggregates())
                .extracting(ListSpec.Aggregate::field, ListSpec.Aggregate::fn, ListSpec.Aggregate::label)
                .containsExactly(
                        tuple("total", ListSpec.Agg.SUM, null),      // label defaults later (to the field label)
                        tuple("amount", ListSpec.Agg.AVG, "Average"));
    }
}
