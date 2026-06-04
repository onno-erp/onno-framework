package com.onec.ui;

import java.util.List;

/**
 * Renderer-agnostic resolved list surface: a title and ordered columns. Produced
 * by {@link UiViewResolver} (merging an {@link EntityView} over the auto-generated
 * defaults) and consumed by the DivKit emitter — or any other renderer.
 */
public record ResolvedListView(String title, List<Column> columns) {

    /**
     * A resolved column: the header label, the data column it reads, and an optional
     * width hint (e.g. {@code "260"} px, from {@code .field(...).width(...)}). A blank
     * width means "size the column to its content" — see the DivKit emitter.
     */
    public record Column(String label, String columnName, String width) {}
}
