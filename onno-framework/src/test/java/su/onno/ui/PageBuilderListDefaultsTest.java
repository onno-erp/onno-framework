package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageBuilderListDefaultsTest {

    @Test
    void authorsTypedDateGroupingAndRemovableFilterDefaults() {
        PageBuilder builder = new PageBuilder();

        builder.list(String.class, defaults -> defaults
                .fill()
                .filter("active = true")
                .defaultFilter("status", "READY")
                .defaultFirstFilterOption("assignedTo")
                .groupBy("created_at", DateGranularity.DAY)
                .sort("created_at", true));

        Map<String, Object> payload = builder.components().getFirst().payload();
        assertThat(payload)
                .containsEntry("fill", true)
                .containsEntry("filter", "active = true")
                .containsEntry("groupBy", "created_at")
                .containsEntry("groupByDateGranularity", "day")
                .containsEntry("sort", "created_at")
                .containsEntry("sortDescending", true);
        assertThat(payload.get("defaultFilters"))
                .isEqualTo(Map.of("status", java.util.List.of("READY")));
        assertThat(payload.get("defaultFirstFilterOptions"))
                .isEqualTo(java.util.List.of("assignedTo"));
    }
}
