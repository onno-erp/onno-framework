package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DivKitControllerListDefaultsTest {

    @Test
    void resolvesFirstOptionAndDateGranularityIntoDescriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("filters", List.of(Map.of(
                "key", "assignedTo",
                "type", "multiOptions",
                "options", List.of(
                        Map.of("value", "employee-1", "label", "Ada"),
                        Map.of("value", "employee-2", "label", "Linus")))));

        DivKitController.applyListDefaults(descriptor, Map.of(
                "groupBy", "created_at",
                "groupByDateGranularity", "day",
                "defaultFirstFilterOptions", List.of("assignedTo")));

        assertThat(descriptor)
                .containsEntry("defaultGroupBy", "created_at")
                .containsEntry("defaultGroupByDateGranularity", "day")
                .containsEntry("defaultFilters", Map.of("assignedTo", List.of("employee-1")));
    }

    @Test
    void explicitFilterDefaultWinsOverFirstOption() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("filters", List.of(Map.of(
                "key", "assignedTo",
                "options", List.of(Map.of("value", "employee-1")))));

        DivKitController.applyListDefaults(descriptor, Map.of(
                "defaultFilters", Map.of("assignedTo", List.of("employee-2")),
                "defaultFirstFilterOptions", List.of("assignedTo")));

        assertThat(descriptor.get("defaultFilters"))
                .isEqualTo(Map.of("assignedTo", List.of("employee-2")));
    }
}
