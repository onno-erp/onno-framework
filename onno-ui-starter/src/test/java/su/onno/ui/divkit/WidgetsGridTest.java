package su.onno.ui.divkit;

import su.onno.metadata.PageWidgetDescriptor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard/page grid's packing contract: widgets flow into rows by width fraction, an
 * authored {@code rowBreak} forces a new row even when the open row has room, and multi-cell rows
 * stretch their cells to the row height (equal-height cards).
 */
class WidgetsGridTest {

    private static PageWidgetDescriptor widget(String title, String width, boolean rowBreak) {
        return new PageWidgetDescriptor(title, "count", 0, width, null, null, 10,
                "", "", Map.of(), "", rowBreak);
    }

    private static Map<String, Object> grid(List<PageWidgetDescriptor> widgets, int columns) {
        return Widgets.grid(widgets, columns, w -> "0", w -> true, Palette.of("dark"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> grid) {
        return (List<Map<String, Object>>) grid.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cells(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("items");
    }

    @Test
    void widgetsPackIntoRowsByFraction() {
        Map<String, Object> grid = grid(List.of(
                widget("a", "1/2", false), widget("b", "1/2", false),
                widget("c", "full", false)), 2);
        List<Map<String, Object>> rows = rows(grid);
        assertThat(rows).hasSize(2);
        assertThat(cells(rows.get(0))).hasSize(2);
    }

    @Test
    void rowBreakClosesAnOpenRowWithRoomToSpare() {
        // Without the break, b (1/4) would join a's half-filled row.
        Map<String, Object> grid = grid(List.of(
                widget("a", "1/2", false), widget("b", "1/4", true)), 2);
        List<Map<String, Object>> rows = rows(grid);
        assertThat(rows).hasSize(2);
        assertThat(cells(rows.get(0))).hasSize(1);
        assertThat(cells(rows.get(1))).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiCellRowsStretchCellsToTheRowHeight() {
        Map<String, Object> grid = grid(List.of(
                widget("a", "1/2", false), widget("b", "1/2", false)), 2);
        for (Map<String, Object> cell : cells(rows(grid).get(0))) {
            Map<String, Object> height = (Map<String, Object>) cell.get("height");
            assertThat(height).containsEntry("type", "match_parent");
        }
    }

    @Test
    void mobileStacksEverythingIgnoringBreaksAndFractions() {
        Map<String, Object> grid = grid(List.of(
                widget("a", "1/2", false), widget("b", "1/2", true)), 1);
        // One block per widget, stacked — no row wrappers.
        assertThat(rows(grid)).hasSize(2);
    }
}
