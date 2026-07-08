package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page header toggle: a page renders its title/subtitle row by default, and {@code bare()}
 * ({@code header = false}) drops it so a chrome-less surface leads straight with its content.
 */
class PageDivBuilderHeaderTest {

    private static Map<String, Object> build(boolean header) {
        return PageDivBuilder.build("Sales Ops", "Subtitle", header,
                List.of(), List.of(), 2, w -> "0", w -> true, Palette.of("dark"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> root) {
        return (List<Map<String, Object>>) root.get("items");
    }

    @Test
    void headerRowRendersByDefault() {
        List<Map<String, Object>> items = items(build(true));
        // Nothing else was composed (no widgets, no components), so the header is the sole block.
        assertThat(items).hasSize(1);
        assertThat(items.toString()).contains("Sales Ops");
    }

    @Test
    void bareDropsTheHeaderRow() {
        // No header, no widgets, no components → an empty content column.
        assertThat(items(build(false))).isEmpty();
    }
}
