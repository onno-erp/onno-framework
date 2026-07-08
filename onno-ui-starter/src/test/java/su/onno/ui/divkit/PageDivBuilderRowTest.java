package su.onno.ui.divkit;

import su.onno.metadata.PageWidgetDescriptor;
import su.onno.ui.PageComponent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The general layout primitive: a page {@code row} of columns renders side by side (a horizontal
 * band) on desktop and stacks vertically on mobile. Column widths become flex weights.
 */
class PageDivBuilderRowTest {

    private static PageWidgetDescriptor widget(String title) {
        return new PageWidgetDescriptor(title, "count", 0, "full", null, null, 10,
                "", "", Map.of(), "", false);
    }

    private static PageDivBuilder.Region col(String title) {
        return new PageDivBuilder.Region(List.of(widget(title)), 1, List.<PageComponent>of());
    }

    private static Map<String, Object> build(boolean desktop) {
        PageDivBuilder.Row row = new PageDivBuilder.Row(List.of(
                new PageDivBuilder.Column("2/3", col("a")),
                new PageDivBuilder.Column("1/3", col("b"))));
        PageDivBuilder.Region main = new PageDivBuilder.Region(
                List.of(), 2, List.<PageComponent>of(), List.of(row));
        return PageDivBuilder.build("T", "S", false, main, null, desktop,
                w -> "0", w -> true, Palette.of("dark"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void desktopRendersColumnsSideBySideWithWeights() {
        List<Map<String, Object>> items = items(build(true));
        Map<String, Object> band = items.stream()
                .filter(i -> "horizontal".equals(i.get("orientation")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> cells = (List<Map<String, Object>>) band.get("items");
        assertThat(cells).hasSize(2);
        // "2/3" and "1/3" → flex weights 2 and 1.
        assertThat(((Map<String, Object>) cells.get(0).get("width")).get("weight")).isEqualTo(2.0);
        assertThat(((Map<String, Object>) cells.get(1).get("width")).get("weight")).isEqualTo(1.0);
    }

    @Test
    void mobileStacksColumns_noHorizontalBand() {
        List<Map<String, Object>> items = items(build(false));
        assertThat(items.stream().anyMatch(i -> "horizontal".equals(i.get("orientation")))).isFalse();
    }
}
