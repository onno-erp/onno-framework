package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;
import su.onno.ui.ResolvedListView;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code onno-list} descriptor's custom-renderer contract: a resolved
 * {@link ResolvedListView.CustomView} travels as the {@code custom} map (type, label, defaultView)
 * the React island resolves against its widget registry, and a list without one emits no
 * {@code custom} key at all — old clients and the default grid see an unchanged descriptor.
 */
class ListDescriptorCustomViewTest {

    private static ResolvedListView view(ResolvedListView.CustomView custom) {
        return new ResolvedListView("Books",
                List.of(new ResolvedListView.Column("Title", "_description", "")),
                true, null, false, List.of(), null, "infinite", 50,
                ResolvedListView.Grouping.none(), custom);
    }

    @Test
    @SuppressWarnings("unchecked")
    void customRendererTravelsInTheDescriptor() {
        ResolvedListView v = view(new ResolvedListView.CustomView("bookTiles", "Shelf", true));

        Map<String, Object> descriptor = SurfaceDivBuilder.listDescriptor(
                v, "catalogs", "books", null, true, List.of(), List.of());

        Map<String, Object> custom = (Map<String, Object>) descriptor.get("custom");
        assertThat(custom).isNotNull();
        assertThat(custom.get("type")).isEqualTo("bookTiles");
        assertThat(custom.get("label")).isEqualTo("Shelf");
        assertThat(custom.get("defaultView")).isEqualTo(true);
    }

    @Test
    void noCustomRendererEmitsNoCustomKey() {
        Map<String, Object> descriptor = SurfaceDivBuilder.listDescriptor(
                view(null), "catalogs", "books", null, true, List.of(), List.of());

        assertThat(descriptor).doesNotContainKey("custom");
    }

    @Test
    void backCompatConstructorHasNoCustomView() {
        ResolvedListView v = new ResolvedListView("Books",
                List.of(new ResolvedListView.Column("Title", "_description", "")),
                true, null, false, List.of(), null, "infinite", 50,
                ResolvedListView.Grouping.none());

        assertThat(v.customView()).isNull();
    }
}
