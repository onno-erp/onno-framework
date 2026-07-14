package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;
import su.onno.ui.ResolvedListView;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code onno-list} descriptor's default-grouping contract (issue #259): a resolved
 * {@link ResolvedListView.Grouping#defaultColumn()} travels as the {@code defaultGroupBy} key the
 * React island seeds its group-by state from (the same key a page-embedded list's defaults use),
 * and a grouping without one emits no {@code defaultGroupBy} at all — the list opens flat.
 */
class ListDescriptorDefaultGroupByTest {

    private static ResolvedListView view(ResolvedListView.Grouping grouping) {
        return new ResolvedListView("Books",
                List.of(new ResolvedListView.Column("Title", "_description", "")),
                true, null, false, List.of(), null, "infinite", 50, grouping, null);
    }

    @Test
    void defaultGroupByTravelsInTheDescriptor() {
        var grouping = new ResolvedListView.Grouping(
                List.of(new ResolvedListView.GroupColumn("type_label", "Type", false)),
                List.of(), "type_label");

        Map<String, Object> descriptor = SurfaceDivBuilder.listDescriptor(
                view(grouping), "catalogs", "books", null, true, List.of(), List.of());

        assertThat(descriptor.get("defaultGroupBy")).isEqualTo("type_label");
    }

    @Test
    void noDefaultGroupingEmitsNoKey() {
        var grouping = new ResolvedListView.Grouping(
                List.of(new ResolvedListView.GroupColumn("type_label", "Type", false)),
                List.of());

        Map<String, Object> descriptor = SurfaceDivBuilder.listDescriptor(
                view(grouping), "catalogs", "books", null, true, List.of(), List.of());

        assertThat(descriptor).doesNotContainKey("defaultGroupBy");
        assertThat(grouping.defaultColumn()).isEmpty();
    }
}
