package com.onec.ui;

import java.util.List;

/**
 * Renderer-agnostic resolved list surface: a title and ordered columns. Produced
 * by {@link UiViewResolver} (merging an {@link EntityView} over the auto-generated
 * defaults) and consumed by the DivKit emitter — or any other renderer.
 */
public record ResolvedListView(String title, List<Column> columns,
                               boolean searchable, String sortColumn, boolean sortDescending,
                               List<Filter> filters) {

    public ResolvedListView {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** Back-compat: a non-searchable, default-sorted view. */
    public ResolvedListView(String title, List<Column> columns) {
        this(title, columns, false, null, false, List.of());
    }

    /** Back-compat: a view with no declarative filters. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending) {
        this(title, columns, searchable, sortColumn, sortDescending, List.of());
    }

    /**
     * A resolved list filter: a stable {@code key} (the field name, the client's state key), the
     * {@code label}, the data {@code columnName} the query filters on (resolved + validated against
     * the entity's columns), the control {@code type} ({@code "options"}, {@code "multiOptions"},
     * {@code "contains"}, {@code "startsWith"} or {@code "dateRange"}) and, for the (multi-)options
     * controls, its {@code options}.
     */
    public record Filter(String key, String label, String columnName, String type, List<String> options) {
        public Filter {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * A resolved column: the header label, the data column it reads, an optional width hint
     * (e.g. {@code "260"} px, from {@code .field(...).width(...)}; blank = size to content), and
     * the display hints carried over from the attribute — {@code widget} (so a list cell can
     * render an image thumbnail) and {@code format} (a date pattern or number spec applied to the
     * cell value). Both default to blank.
     */
    public record Column(String label, String columnName, String width, String widget, String format) {
        /** Back-compat: a column with no display widget/format hints. */
        public Column(String label, String columnName, String width) {
            this(label, columnName, width, "", "");
        }
    }
}
