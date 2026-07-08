package su.onno.ui;

import java.util.List;

/**
 * A horizontal band of a page — one or more {@link PageColumn}s laid out side by side. The general
 * layout primitive: split a page into columns of any width, put any block in each, and nest further
 * rows inside a column for arbitrary structure. On a narrow (mobile) viewport a row collapses, its
 * columns stacking vertically. Authored via {@code PageBuilder.row(...)}.
 */
public record PageRow(List<PageColumn> columns) {
    public PageRow {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
