package su.onno.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The default list descriptor: unsorted, searchable, flat; late-bound commands advertised only when present. */
class ListDescriptorTest {

    private static DefaultListView view() {
        return new DefaultListView("Books",
                List.of(new DefaultListView.Column("Title", "_description", "description", "", "", "", "", "")),
                List.of(new DefaultListView.Filter("status", "Status", "status", "multiOptions",
                        List.of(new DefaultListView.Option("id-1", "New", "#00ff00", "")))),
                0);
    }

    @Test
    void defaultListOpensUnsortedWithItsFilters() {
        Map<String, Object> descriptor = ViewDescriptors.listDescriptor(view(), "catalogs", "books", null, true, List.of());

        assertThat(descriptor).containsEntry("searchable", true).containsEntry("pageSize", 50)
                .doesNotContainKeys("custom", "map", "groupable", "selectionWidget");
        assertThat(((Map<?, ?>) descriptor.get("sort")).get("column")).isNull();
        assertThat(descriptor.get("filters")).asList().singleElement().satisfies(f ->
                assertThat(((Map<?, ?>) f).get("options")).asList().singleElement()
                        .isEqualTo(Map.of("value", "id-1", "label", "New", "color", "#00ff00")));
    }

    @Test
    void lateBoundCommandsAdvertiseRefreshOnlyWhenPresent() {
        Map<String, Object> dynamic = ViewDescriptors.listDescriptor(view(), "catalogs", "books", null, true, List.of(), true);
        Map<String, Object> staticOnly = ViewDescriptors.listDescriptor(view(), "catalogs", "books", null, true, List.of());

        assertThat(dynamic).containsEntry("dynamicCommands", true);
        assertThat(staticOnly).doesNotContainKey("dynamicCommands");
    }
}
