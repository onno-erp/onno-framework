package su.onno.ui.divkit;

import su.onno.metadata.PageWidgetDescriptor;
import su.onno.ui.PageComponent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page right rail ({@code aside}): on desktop the main content and the rail render side by side
 * in a horizontal row; on mobile (one column) the rail stacks below the main content. With no aside
 * content the page stays a plain vertical flow.
 */
class PageDivBuilderAsideTest {

    private static PageWidgetDescriptor widget(String title) {
        return new PageWidgetDescriptor(title, "count", 0, "full", null, null, 10,
                "", "", Map.of(), "", false);
    }

    private static Map<String, Object> build(List<PageWidgetDescriptor> main,
                                             List<PageWidgetDescriptor> aside, int columns) {
        return PageDivBuilder.build("T", "S", false, main, List.<PageComponent>of(),
                aside, List.<PageComponent>of(), columns, w -> "0", w -> true, Palette.of("dark"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("items");
    }

    private static boolean containsHorizontal(List<Map<String, Object>> items) {
        return items.stream().anyMatch(i -> "horizontal".equals(i.get("orientation")));
    }

    @Test
    void desktopPutsMainAndAsideInAHorizontalRow() {
        List<Map<String, Object>> items = items(build(List.of(widget("main")), List.of(widget("stat")), 2));
        // The single top-level block is the [main | aside] row.
        assertThat(containsHorizontal(items)).isTrue();
    }

    @Test
    void mobileStacksAsideBelowMain_noHorizontalRow() {
        List<Map<String, Object>> items = items(build(List.of(widget("main")), List.of(widget("stat")), 1));
        assertThat(containsHorizontal(items)).isFalse();
        // Two stacked blocks: the main grid, then the aside grid.
        assertThat(items).hasSize(2);
    }

    @Test
    void noAsideStaysAPlainVerticalFlow() {
        List<Map<String, Object>> items = items(build(List.of(widget("main")), List.of(), 2));
        assertThat(containsHorizontal(items)).isFalse();
    }
}
